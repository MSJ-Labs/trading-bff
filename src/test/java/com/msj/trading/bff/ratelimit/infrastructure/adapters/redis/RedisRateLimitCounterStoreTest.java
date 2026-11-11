package com.msj.trading.bff.ratelimit.infrastructure.adapters.redis;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.msj.trading.bff.ratelimit.application.port.out.RateLimitCounterStore.Increment;

import static org.assertj.core.api.Assertions.assertThat;

/** The Redis adapter against a real Redis: the Lua script, the key prefix, the totals and the expiry. */
@Testcontainers
class RedisRateLimitCounterStoreTest {

    private static final int REDIS_PORT = 6379;

    @Container
    @SuppressWarnings("resource") // lifecycle managed by @Testcontainers
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(REDIS_PORT);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private final RedisRateLimitCounterStore store = new RedisRateLimitCounterStore(redis);

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(REDIS_PORT));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @Test
    void returnsTheNewTotalOfEachCounter() {
        String a = uniqueKey();
        String b = uniqueKey();

        Map<String, Long> totals = store.addAll(List.of(
                new Increment(a, 3, Duration.ofMinutes(2)),
                new Increment(b, 5, Duration.ofMinutes(2))));

        assertThat(totals).containsEntry(a, 3L).containsEntry(b, 5L).hasSize(2);
    }

    @Test
    void accumulatesAcrossCalls_asTwoPodsWould() {
        String key = uniqueKey();

        store.addAll(List.of(new Increment(key, 3, Duration.ofMinutes(2))));
        Map<String, Long> totals = store.addAll(List.of(new Increment(key, 2, Duration.ofMinutes(2))));

        assertThat(totals).containsEntry(key, 5L);
    }

    @Test
    void anIncrementOfZeroJustReadsTheTotal() {
        String key = uniqueKey();
        store.addAll(List.of(new Increment(key, 4, Duration.ofMinutes(2))));

        Map<String, Long> totals = store.addAll(List.of(new Increment(key, 0, Duration.ofMinutes(2))));

        assertThat(totals).containsEntry(key, 4L);
    }

    @Test
    void eachCounterGetsItsOwnExpiry_underTheKeyPrefix() {
        String longLived = uniqueKey();
        String shortLived = uniqueKey();

        store.addAll(List.of(
                new Increment(longLived, 1, Duration.ofSeconds(120)),
                new Increment(shortLived, 1, Duration.ofSeconds(10))));

        assertThat(redis.getExpire(RedisRateLimitCounterStore.KEY_PREFIX + longLived)).isBetween(11L, 120L);
        assertThat(redis.getExpire(RedisRateLimitCounterStore.KEY_PREFIX + shortLived)).isBetween(1L, 10L);
    }

    @Test
    void noIncrementMeansNoCall() {
        assertThat(store.addAll(List.of())).isEmpty();
    }

    private static String uniqueKey() {
        return "read:60000:" + UUID.randomUUID() + ":0";
    }
}
