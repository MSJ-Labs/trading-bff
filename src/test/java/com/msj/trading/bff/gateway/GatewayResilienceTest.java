package com.msj.trading.bff.gateway;

import java.time.Duration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.msj.trading.bff.support.WithFixedKeycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Client;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gateway resilience, end to end: full BFF context (real routes, circuit breaker, retry) in front of a
 * {@link FakeBackend} standing in for trading-core, with a real Redis for sessions.
 *
 * <p>MockMvc does not execute servlet forwards: when the circuit breaker falls back, it only records the target
 * ({@code forwardedUrl}). The fallback JSON itself belongs to a FallbackController test.
 */
@SpringBootTest
@WithFixedKeycloak
@AutoConfigureMockMvc
@Testcontainers
class GatewayResilienceTest {

    private static final String PATH = "/api/v1/x";
    private static final int REDIS_PORT = 6379;

    @Container
    @SuppressWarnings("resource") // lifecycle managed by @Testcontainers
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(REDIS_PORT);

    private static final FakeBackend BACKEND = new FakeBackend();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("TRADING_CORE_URI", () -> "http://localhost:" + BACKEND.port());
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
        // small window so a circuit-opening test stays short
        registry.add("resilience4j.circuitbreaker.configs.trading-core.sliding-window-size", () -> "4");
        registry.add("resilience4j.circuitbreaker.configs.trading-core.minimum-number-of-calls", () -> "4");
        // 1s instead of 10s so the slow-backend test stays short
        registry.add("resilience4j.timelimiter.configs.trading-core.timeout-duration", () -> "1s");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    @AfterAll
    static void stopBackend() {
        BACKEND.close();
    }

    @BeforeEach
    void reset() {
        BACKEND.reset();
        // the circuit is shared by all tests: start each one closed and empty
        circuitBreakers.find("trading-core").ifPresent(CircuitBreaker::reset);
    }

    @Test
    void get_retriedUntilBackendRecovers() throws Exception {
        BACKEND.script(PATH, FakeBackend.respond(503), FakeBackend.respond(503), FakeBackend.respond(200));

        mockMvc.perform(authenticated(get(PATH))).andExpect(status().isOk());

        // 1 call + 2 retries
        assertThat(BACKEND.requests("GET", PATH)).isEqualTo(3);
    }

    @Test
    void post_isNeverReplayed() throws Exception {
        BACKEND.thenAlways(PATH, FakeBackend.respond(503));

        mockMvc.perform(authenticated(post(PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(forwardedUrl("/fallback/trading-core"));

        // a non-idempotent call must reach trading-core exactly once
        assertThat(BACKEND.requests("POST", PATH)).isEqualTo(1);
    }

    @Test
    void get_alwaysFailing_fallsBackAfterRetries() throws Exception {
        BACKEND.thenAlways(PATH, FakeBackend.respond(503));

        mockMvc.perform(authenticated(get(PATH)))
                .andExpect(forwardedUrl("/fallback/trading-core"));

        // 1 call + 2 retries, then the fallback: a real HTTP answer is never replayed by the client itself
        assertThat(BACKEND.requests("GET", PATH)).isEqualTo(3);
    }

    @Test
    void circuit_opensAfterRepeatedFailures_andStopsCallingBackend() throws Exception {
        BACKEND.thenAlways(PATH, FakeBackend.respond(503));

        // window of 4 calls, all failing (the retries inside one call count as a single failure)
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(authenticated(get(PATH))).andExpect(forwardedUrl("/fallback/trading-core"));
        }
        assertThat(circuitBreakers.circuitBreaker("trading-core").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int hitsWhenOpened = BACKEND.requests("GET", PATH);

        // the backend is healthy again, but the open circuit must not even try it
        BACKEND.thenAlways(PATH, FakeBackend.respond(200));
        mockMvc.perform(authenticated(get(PATH))).andExpect(forwardedUrl("/fallback/trading-core"));

        assertThat(BACKEND.requests("GET", PATH)).isEqualTo(hitsWhenOpened);
    }

    @Test
    void get_slowBackend_fallsBackAtTimeLimit() throws Exception {
        BACKEND.thenAlways(PATH, FakeBackend.slow(Duration.ofSeconds(5), 200));

        long start = System.nanoTime();
        mockMvc.perform(authenticated(get(PATH)))
                .andExpect(forwardedUrl("/fallback/trading-core"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // the 1s time limiter caps all attempts together: the client is not left waiting for the 5s answer
        assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void get_clientError_passesThroughUntouched() throws Exception {
        BACKEND.thenAlways(PATH, FakeBackend.respond(404));

        mockMvc.perform(authenticated(get(PATH)))
                .andExpect(status().isNotFound())
                .andExpect(forwardedUrl(null));

        // a 4xx is a business answer: not retried, not replaced by the fallback, not a circuit failure
        assertThat(BACKEND.requests("GET", PATH)).isEqualTo(1);
        assertThat(circuitBreakers.circuitBreaker("trading-core").getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void get_retriedWhenConnectionIsDropped() throws Exception {
        BACKEND.thenAlways(PATH, FakeBackend.drop());

        mockMvc.perform(authenticated(get(PATH)))
                .andExpect(forwardedUrl("/fallback/trading-core"));

        // 3 gateway attempts (1 call + 2 retries). The JDK HTTP client under RestClient also replays an idempotent
        // request once when the connection is cut, so the backend sees 6 hits, not 3: assert "more than the 2 hits
        // a single attempt produces" instead of an exact count that depends on the client's internals.
        assertThat(BACKEND.requests("GET", PATH)).isGreaterThanOrEqualTo(3);
    }

    @Test
    void post_connectionDropped_isNeverReplayed() throws Exception {
        BACKEND.thenAlways(PATH, FakeBackend.drop());

        mockMvc.perform(authenticated(post(PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(forwardedUrl("/fallback/trading-core"));

        // the worst case for a POST: trading-core may have processed it before the cut, so no replay, neither by
        // the gateway retry (GET only) nor by the HTTP client (must not replay a non-idempotent method)
        assertThat(BACKEND.requests("POST", PATH)).isEqualTo(1);
    }

    /** Logged-in user + an access token for TokenRelay to forward. */
    private static MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return request.with(oidcLogin()).with(oauth2Client("keycloak"));
    }
}