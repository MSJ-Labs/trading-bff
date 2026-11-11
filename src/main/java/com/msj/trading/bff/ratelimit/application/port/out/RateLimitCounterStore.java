package com.msj.trading.bff.ratelimit.application.port.out;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Port: the shared counters (one per caller, policy and time window) that let every BFF pod see what the others
 * let through. Called once per synchronisation for ALL counters together, never from a request.
 */
public interface RateLimitCounterStore {

    /**
     * Adds each increment to its shared counter and returns the new global totals.
     * Throws a RuntimeException when the store cannot be reached or answers badly.
     */
    Map<String, Long> addAll(List<Increment> increments);

    /**
     * @param key    counter name
     * @param amount number of requests to add (0 is allowed, to just read the total)
     * @param ttl    how long the counter must live (it is useless once its window is over)
     */
    record Increment(String key, long amount, Duration ttl) {
    }
}
