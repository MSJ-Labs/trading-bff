package com.msj.trading.bff.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The {@code secure-file} profile: its routes replace those of trading-core as a whole, reads are retried, writes are
 * not, and the file transfers (upload, download) bypass the circuit breaker, whose time limiter would cut them.
 */
@SpringBootTest
@ActiveProfiles("secure-file")
@WithFixedKeycloak
@AutoConfigureMockMvc
@Testcontainers
class SecureFileProfileTest {

    private static final int REDIS_PORT = 6379;

    @Container
    @SuppressWarnings("resource") // lifecycle managed by @Testcontainers
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(REDIS_PORT);

    private static final FakeBackend BACKEND = new FakeBackend();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("SECURE_FILE_URI", () -> "http://localhost:" + BACKEND.port());
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
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
    void list_isRetriedUntilTheServiceRecovers() throws Exception {
        BACKEND.script("/api/v1/files", FakeBackend.respond(503), FakeBackend.respond(200));

        mockMvc.perform(authenticated(get("/api/v1/files"))).andExpect(status().isOk());

        assertThat(BACKEND.requests("GET", "/api/v1/files")).isEqualTo(2);
    }

    @Test
    void upload_goesThroughWithoutCircuitBreakerNorRetry() throws Exception {
        BACKEND.thenAlways("/api/v1/files", FakeBackend.respond(503));

        // The 503 of the service is passed on as is: no fallback forward, and the body is sent once.
        mockMvc.perform(authenticated(put("/api/v1/files").with(csrf()).param("name", "a.txt").content("data")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(forwardedUrl(null));

        assertThat(BACKEND.requests("PUT", "/api/v1/files")).isEqualTo(1);
    }

    @Test
    void upload_withAFormContentType_isNotParsedAsAForm() throws Exception {
        // Axios sends a PUT with application/x-www-form-urlencoded unless told otherwise: the bytes of the file
        // must reach the service untouched, not be decoded (and held in memory) as a form.
        BACKEND.thenAlways("/api/v1/files", FakeBackend.respond(200));

        mockMvc.perform(authenticated(put("/api/v1/files").with(csrf()).param("name", "a.bin")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content(new byte[] {'%', 'Z', 0, (byte) 0xff})))
                .andExpect(status().isOk());

        assertThat(BACKEND.requests("PUT", "/api/v1/files")).isEqualTo(1);
    }

    @Test
    void upload_reachesTheServiceWithItsContentLength() throws Exception {
        // The service refuses a body of unknown length (411): the BFF must not turn the upload into a chunked one.
        BACKEND.thenAlways("/api/v1/files", FakeBackend.respond(200));
        byte[] body = new byte[100_000];

        mockMvc.perform(authenticated(put("/api/v1/files").with(csrf()).param("name", "a.bin")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header("Content-Length", body.length)
                        .content(body)))
                .andExpect(status().isOk());

        var received = BACKEND.lastHeaders("PUT", "/api/v1/files");
        assertThat(received.getFirst("Content-Length")).isEqualTo(String.valueOf(body.length));
        assertThat(received.getFirst("Transfer-Encoding")).isNull();
    }

    @Test
    void download_goesThroughWithoutCircuitBreakerNorRetry() throws Exception {
        BACKEND.thenAlways("/api/v1/files/42/content", FakeBackend.respond(503));

        mockMvc.perform(authenticated(get("/api/v1/files/42/content")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(forwardedUrl(null));

        assertThat(BACKEND.requests("GET", "/api/v1/files/42/content")).isEqualTo(1);
    }

    @Test
    void otherWrites_areProtectedByTheCircuitBreakerAndNeverReplayed() throws Exception {
        BACKEND.thenAlways("/api/v1/files/42", FakeBackend.respond(503));

        mockMvc.perform(authenticated(post("/api/v1/files/42").with(csrf())))
                .andExpect(forwardedUrl("/fallback/secure-file-service"));

        assertThat(BACKEND.requests("POST", "/api/v1/files/42")).isEqualTo(1);
    }

    @Test
    void tradingCoreRoutes_areGone() throws Exception {
        mockMvc.perform(authenticated(get("/api/orders"))).andExpect(status().isNotFound());

        assertThat(BACKEND.requests("GET", "/api/orders")).isZero();
    }

    private static MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return request.with(oidcLogin()).with(oauth2Client("keycloak"));
    }
}
