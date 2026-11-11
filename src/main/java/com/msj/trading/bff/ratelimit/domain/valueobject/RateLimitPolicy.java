package com.msj.trading.bff.ratelimit.domain.valueobject;

import com.msj.trading.bff.shared.domain.ValueObject;

import java.time.Duration;

/**
 * The rule applied to a group of requests: at most {@code limit} requests per {@code window}, counted separately for
 * each caller and each {@code scope} (e.g. "read" or "write"). Windows are fixed and aligned on the clock, so every
 * pod computes the same window for the same instant.
 *
 * <p>Built once per route at startup, so a wrong configuration fails the startup instead of silently disabling the
 * limit (a bare {@code 60} in a duration property means 60 <em>milliseconds</em>).
 */
public record RateLimitPolicy(String scope, long limit, Duration window) implements ValueObject {

    public static final Duration MIN_WINDOW = Duration.ofSeconds(1);

    public RateLimitPolicy {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("Rate limit scope must not be blank");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("Rate limit must be positive for scope '" + scope + "', got " + limit);
        }
        if (window == null || window.compareTo(MIN_WINDOW) < 0) {
            throw new IllegalArgumentException("Rate limit window must be at least " + MIN_WINDOW + " for scope '"
                    + scope + "', got " + window + " (a bare number in a property means milliseconds: write 60s)");
        }
    }

    /** Number of the window this instant belongs to (the same on every pod). */
    public long windowId(long epochMillis) {
        return epochMillis / window.toMillis();
    }

    /** First instant after the given window. */
    public long windowEndMillis(long windowId) {
        return (windowId + 1) * window.toMillis();
    }

    /** How long a shared counter must live: a window is useless once over, twice its length is a safe margin. */
    public Duration counterTtl() {
        return window.multipliedBy(2);
    }

    /** Name of the counter of this caller in this window (the window length is part of it: no clash between policies). */
    public String counterKey(CallerId caller, long windowId) {
        return scope + ':' + window.toMillis() + ':' + caller.value() + ':' + windowId;
    }
}
