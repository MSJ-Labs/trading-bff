package com.msj.trading.bff.ratelimit.domain.model;

import com.msj.trading.bff.ratelimit.domain.valueobject.RateLimitDecision;
import com.msj.trading.bff.ratelimit.domain.valueobject.RateLimitPolicy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What one pod knows about the requests of one caller in one window of one policy.
 *
 * <p>Both numbers live in ONE immutable {@link State} replaced atomically (compare-and-set), so a request is always
 * counted in exactly one of them, even while a synchronisation with the shared store is in flight:
 * <ul>
 *   <li>{@code knownGlobal}: the total (all pods) at the last confirmed synchronisation;</li>
 *   <li>{@code unsent}: what this pod let through since then and has not told the store yet.</li>
 * </ul>
 * Requests are checked without any lock: a failed compare-and-set just retries with the fresh state.
 */
public final class WindowCounter {

    private record State(long knownGlobal, long unsent) {

        long used() {
            return knownGlobal + unsent;
        }
    }

    private final AtomicReference<State> state = new AtomicReference<>(new State(0, 0));
    private final long limit;
    private final long windowEndMillis;
    private final Duration ttl;

    public WindowCounter(RateLimitPolicy policy, long windowId) {
        this.limit = policy.limit();
        this.windowEndMillis = policy.windowEndMillis(windowId);
        this.ttl = policy.counterTtl();
    }

    /** Counts one more request if the limit is not reached yet; otherwise refuses it without counting it. */
    public RateLimitDecision tryAcquire(long nowMillis) {
        while (true) {
            State current = state.get();
            if (current.used() >= limit) {
                return RateLimitDecision.denied(retryAfter(nowMillis));
            }
            State next = new State(current.knownGlobal(), current.unsent() + 1);
            if (state.compareAndSet(current, next)) {
                return RateLimitDecision.allowed(limit - next.used());
            }
        }
    }

    /** Requests let through here and not yet sent to the shared store. */
    public long unsent() {
        return state.get().unsent();
    }

    /**
     * The store now knows {@code sent} more requests of ours and the global total is {@code total}. The known total
     * never goes backwards: if the store lost its data (restart, flush), this pod keeps what it already knew.
     */
    public void confirm(long sent, long total) {
        state.updateAndGet(current ->
                new State(Math.max(current.knownGlobal() + sent, total), current.unsent() - sent));
    }

    /** A finished window is useless to every pod (they all moved on to the next one). */
    public boolean isFinished(long nowMillis) {
        return nowMillis >= windowEndMillis;
    }

    public Duration ttl() {
        return ttl;
    }

    /** Time left until the next window, rounded up to whole seconds (at least 1), as the HTTP Retry-After wants. */
    private Duration retryAfter(long nowMillis) {
        long leftMillis = Math.max(0, windowEndMillis - nowMillis);
        return Duration.ofSeconds(Math.max(1, (leftMillis + 999) / 1000));
    }
}
