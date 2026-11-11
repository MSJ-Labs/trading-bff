package com.msj.trading.bff.ratelimit.domain.valueobject;

import com.msj.trading.bff.shared.domain.ValueObject;

import java.time.Duration;

/**
 * Outcome of a rate limit check: whether the request may go on, how many requests the caller has left, and, when
 * refused, how long to wait before the next window starts.
 */
public record RateLimitDecision(boolean allowed, long remaining, Duration retryAfter) implements ValueObject {

    public static RateLimitDecision allowed(long remaining) {
        return new RateLimitDecision(true, Math.max(0, remaining), Duration.ZERO);
    }

    public static RateLimitDecision denied(Duration retryAfter) {
        return new RateLimitDecision(false, 0, retryAfter);
    }
}
