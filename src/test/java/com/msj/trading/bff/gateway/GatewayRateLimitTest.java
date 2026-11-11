package com.msj.trading.bff.gateway;

import java.util.UUID;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gateway rate limiting, end to end: real routes, the real limiter and a real Redis, in front of a
 * {@link FakeBackend} standing in for trading-core. Limits are lowered (3 reads, 2 writes per window) to keep
 * the tests short. Each test uses its own random user, so counters left in the limiter's memory by a test (the
 * Spring context is cached across tests) never leak into another.
 */
@SpringBootTest
@WithFixedKeycloak
@AutoConfigureMockMvc
@Testcontainers
class GatewayRateLimitTest {

    private static final String PATH = "/api/v1/x";
    private static final int REDIS_PORT = 6379;
    private static final int READ_LIMIT = 3;
    private static final int WRITE_LIMIT = 2;

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
        // a list (routes[0]...) cannot be overridden item by item from another property source, hence these placeholders
        registry.add("app.rate-limit.read-capacity", () -> READ_LIMIT);
        registry.add("app.rate-limit.write-capacity", () -> WRITE_LIMIT);
        // windows are aligned on the clock: a long one keeps a test from straddling two windows
        registry.add("app.rate-limit.period", () -> "1h");
    }

    @Autowired
    MockMvc mockMvc;

    @AfterAll
    static void stopBackend() {
        BACKEND.close();
    }

    @BeforeEach
    void reset() {
        BACKEND.reset();
    }

    @Test
    void get_overTheLimit_isRefusedWith429_andBackendIsNotCalled() throws Exception {
        String user = newUser();

        mockMvc.perform(as(user, get(PATH)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Remaining", String.valueOf(READ_LIMIT - 1)));
        for (int i = 1; i < READ_LIMIT; i++) {
            mockMvc.perform(as(user, get(PATH))).andExpect(status().isOk());
        }
        mockMvc.perform(as(user, get(PATH)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429));

        // the refused request never reached trading-core
        assertThat(BACKEND.requests("GET", PATH)).isEqualTo(READ_LIMIT);
    }

    @Test
    void twoUsers_haveSeparateCounters() throws Exception {
        String heavyUser = newUser();
        String otherUser = newUser();

        for (int i = 0; i < READ_LIMIT; i++) {
            mockMvc.perform(as(heavyUser, get(PATH))).andExpect(status().isOk());
        }
        mockMvc.perform(as(heavyUser, get(PATH))).andExpect(status().isTooManyRequests());

        // another user is not affected by the first one's quota
        mockMvc.perform(as(otherUser, get(PATH))).andExpect(status().isOk());
    }

    @Test
    void writes_haveTheirOwnCounter_apartFromReads() throws Exception {
        String user = newUser();

        for (int i = 0; i < WRITE_LIMIT; i++) {
            mockMvc.perform(as(user, post(PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(as(user, post(PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isTooManyRequests());

        // writes are exhausted, but the same user can still read
        mockMvc.perform(as(user, get(PATH))).andExpect(status().isOk());
        assertThat(BACKEND.requests("POST", PATH)).isEqualTo(WRITE_LIMIT);
    }

    private static String newUser() {
        return UUID.randomUUID().toString();
    }

    /** Logged-in user with this Keycloak subject + an access token for TokenRelay to forward. */
    private static MockHttpServletRequestBuilder as(String subject, MockHttpServletRequestBuilder request) {
        return request.with(oidcLogin().idToken(token -> token.subject(subject))).with(oauth2Client("keycloak"));
    }
}