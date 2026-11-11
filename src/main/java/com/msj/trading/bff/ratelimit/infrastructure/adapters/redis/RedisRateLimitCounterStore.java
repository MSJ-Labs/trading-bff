package com.msj.trading.bff.ratelimit.infrastructure.adapters.redis;

import com.msj.trading.bff.ratelimit.application.port.out.RateLimitCounterStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared rate limit counters in Redis (same Redis and DB as the sessions). All counters of a synchronisation go in
 * ONE Lua script: a single network round trip and a single atomic step, whatever the number of users, on the shared
 * Lettuce connection (a pipeline would open a dedicated connection at every call).
 *
 * <p>The script touches several keys at once, so it needs a standalone Redis (or one hash slot): with Redis Cluster,
 * the counters would have to share a hash tag.
 */
@Component
@RequiredArgsConstructor
class RedisRateLimitCounterStore implements RateLimitCounterStore {

    static final String KEY_PREFIX = "trading-bff:ratelimit:";

    /** KEYS[i] = counter, ARGV[2i-1] = amount to add, ARGV[2i] = ttl in seconds; returns the new totals, in order. */
    private static final String ADD_ALL_SCRIPT = """
            local totals = {}
            for i, key in ipairs(KEYS) do
              totals[i] = redis.call('INCRBY', key, ARGV[2 * i - 1])
              redis.call('EXPIRE', key, ARGV[2 * i])
            end
            return totals
            """;

    @SuppressWarnings("rawtypes") // Spring maps a Lua table to a raw List
    private static final RedisScript<List> ADD_ALL = RedisScript.of(ADD_ALL_SCRIPT, List.class);

    private final StringRedisTemplate redis;

    @Override
    public Map<String, Long> addAll(List<Increment> increments) {
        if (increments.isEmpty()) {
            return Map.of();
        }
        List<String> keys = new ArrayList<>(increments.size());
        Object[] args = new Object[2 * increments.size()];
        for (int i = 0; i < increments.size(); i++) {
            Increment increment = increments.get(i);
            keys.add(KEY_PREFIX + increment.key());
            args[2 * i] = String.valueOf(increment.amount());
            args[2 * i + 1] = String.valueOf(Math.max(1, increment.ttl().toSeconds()));
        }

        List<?> totals = redis.execute(ADD_ALL, keys, args);
        if (totals == null || totals.size() != increments.size()) {
            throw new IllegalStateException("Unexpected answer from Redis for " + increments.size() + " counters: "
                    + totals);
        }
        Map<String, Long> result = new HashMap<>();
        for (int i = 0; i < increments.size(); i++) {
            result.put(increments.get(i).key(), ((Number) totals.get(i)).longValue());
        }
        return result;
    }
}
