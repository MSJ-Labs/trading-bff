package com.msj.trading.bff.security;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.security.oauth2.client.oidc.authentication.logout.OidcLogoutToken;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionInformation;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/** Real Redis (Docker): checks behaviour AND the JDK serialization round trip of the OIDC user. */
@Testcontainers
class RedisOidcSessionRegistryTest {

    private static final String ISSUER = "http://localhost:8180/realms/trading";
    private static final Duration TTL = Duration.ofMinutes(30);

    @Container
    @SuppressWarnings("resource") // lifecycle managed by @Testcontainers
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate indexes;
    private static RedisTemplate<String, OidcSessionInformation> sessions;

    private RedisOidcSessionRegistry registry;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();

        indexes = new StringRedisTemplate(connectionFactory);

        sessions = new RedisTemplate<>();
        sessions.setConnectionFactory(connectionFactory);
        sessions.setKeySerializer(new StringRedisSerializer());
        sessions.setValueSerializer(new JdkSerializationRedisSerializer(
                RedisOidcSessionRegistryTest.class.getClassLoader()));
        sessions.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        indexes.execute((org.springframework.data.redis.core.RedisCallback<Object>) c -> {
            c.serverCommands().flushAll();
            return null;
        });
        registry = new RedisOidcSessionRegistry(sessions, indexes, TTL);
    }

    @Test
    void removeByLogoutToken_withSid_returnsOnlyThatSession() {
        registry.saveSessionInformation(info("http-1", "alice", "sid-1"));
        registry.saveSessionInformation(info("http-2", "alice", "sid-2"));

        var removed = registry.removeSessionInformation(logoutToken("sid-1"));

        assertThat(removed).extracting(OidcSessionInformation::getSessionId).containsExactly("http-1");
        assertThat(sessions.hasKey("trading-bff:oidc:session:http-1")).isFalse();
        assertThat(sessions.hasKey("trading-bff:oidc:session:http-2")).isTrue();
    }

    @Test
    void removeByLogoutToken_withoutSid_removesAllSessionsOfTheUser() {
        registry.saveSessionInformation(info("http-1", "alice", "sid-1"));
        registry.saveSessionInformation(info("http-2", "alice", "sid-2"));

        var removed = registry.removeSessionInformation(logoutToken(null));

        assertThat(removed).extracting(OidcSessionInformation::getSessionId)
                .containsExactlyInAnyOrder("http-1", "http-2");
    }

    @Test
    void removeByLogoutToken_unknownSid_returnsEmpty() {
        assertThat(registry.removeSessionInformation(logoutToken("nope"))).isEmpty();
    }

    @Test
    void removeByLogoutToken_doesNotTouchOtherUsers() {
        registry.saveSessionInformation(info("http-a", "alice", "sid-a"));
        registry.saveSessionInformation(info("http-b", "bob", "sid-b"));

        registry.removeSessionInformation(logoutToken(null));

        assertThat(registry.removeSessionInformation("http-b")).isNotNull();
    }

    @Test
    void removeBySessionId_returnsInfoAndCleansIndexes() {
        registry.saveSessionInformation(info("http-1", "alice", "sid-1"));

        var removed = registry.removeSessionInformation("http-1");

        assertThat(removed).isNotNull();
        assertThat(removed.getPrincipal().getSubject()).isEqualTo("alice");
        assertThat(removed.getAuthorities()).containsEntry("Cookie", "SESSION=http-1");
        assertThat(indexes.keys("trading-bff:oidc:*")).isEmpty();
    }

    @Test
    void removeBySessionId_unknown_returnsNull() {
        assertThat(registry.removeSessionInformation("ghost")).isNull();
    }

    @Test
    void save_setsTtlOnEveryKey() {
        registry.saveSessionInformation(info("http-1", "alice", "sid-1"));

        var keys = indexes.keys("trading-bff:oidc:*");

        assertThat(keys).hasSize(3);
        keys.forEach(key -> assertThat(indexes.getExpire(key)).isPositive().isLessThanOrEqualTo(TTL.toSeconds()));
    }

    @Test
    @SuppressWarnings("DataFlowIssue") // deliberately violating the non-null contract
    void removeBySessionId_null_failsWithReadableMessage() {
        assertThatNullPointerException()
                .isThrownBy(() -> registry.removeSessionInformation((String) null))
                .withMessageContaining("HTTP session id");
    }

    @Test
    void save_withoutIssuer_failsWithReadableMessage() {
        var noIssuer = OidcIdToken.withTokenValue("t")
                .subject("alice").claim("sid", "sid-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        var info = new OidcSessionInformation("http-1", Map.of(), new DefaultOidcUser(List.of(), noIssuer));

        assertThatNullPointerException()
                .isThrownBy(() -> registry.saveSessionInformation(info))
                .withMessageContaining("issuer");
    }

    private static OidcSessionInformation info(String httpSessionId, String sub, String sid) {
        OidcUser user = new DefaultOidcUser(List.of(), OidcIdToken.withTokenValue("t")
                .issuer(ISSUER).subject(sub).claim("sid", sid)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build());
        return new OidcSessionInformation(httpSessionId, Map.of("Cookie", "SESSION=" + httpSessionId), user);
    }

    private static OidcLogoutToken logoutToken(String sid) {
        var builder = OidcLogoutToken.withTokenValue("lt")
                .issuer(ISSUER).audience(List.of("trading-bff")).subject("alice")
                .issuedAt(Instant.now()).jti("jti")
                .events(Map.of("http://schemas.openid.net/event/backchannel-logout", Map.of()));
        if (sid != null) {
            builder.sessionId(sid);
        }
        return builder.build();
    }
}
