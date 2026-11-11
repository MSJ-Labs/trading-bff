package com.msj.trading.bff.ratelimit.application;

import com.msj.trading.bff.ratelimit.application.port.out.CallerIdentityProvider;
import com.msj.trading.bff.ratelimit.application.port.out.RateLimitCounterStore;
import com.msj.trading.bff.ratelimit.application.port.out.RateLimitCounterStore.Increment;
import com.msj.trading.bff.ratelimit.domain.exception.UnidentifiedCallerException;
import com.msj.trading.bff.ratelimit.domain.model.WindowCounter;
import com.msj.trading.bff.ratelimit.domain.valueobject.CallerId;
import com.msj.trading.bff.ratelimit.domain.valueobject.RateLimitDecision;
import com.msj.trading.bff.ratelimit.domain.valueobject.RateLimitPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fixed-window rate limiter that never calls the shared store while serving a request.
 *
 * <p>Each pod counts the requests it lets through in memory ({@link WindowCounter}). {@link #synchronize()}, called
 * periodically, pushes those counts to the shared store in one batch and learns the global totals, so a pod also
 * sees what the other pods let through. If the store is down, pods keep limiting on their own counts and retry at
 * the next synchronisation: nobody is blocked because of the store.
 *
 * <p>This protects against abuse, it is not an exact quota. A pod only learns about the requests of the others
 * after two steps (the other pod pushes, then this pod pulls), so a caller can exceed the limit by what the other
 * pods let through in about two synchronisation periods, and by at most pods x limit per window in the worst case.
 * A pod that has never seen a caller counts its first requests blind until its first synchronisation. After a
 * store timeout the batch may have been applied although the pod never saw the answer: it is then sent again and
 * counted twice (the limiter fails strict, never lenient).
 *
 * <p>Plain class, no framework: it is declared as a bean in {@code RateLimitConfig}.
 */
@Slf4j
@RequiredArgsConstructor
public class RateLimiter {

    private final RateLimitCounterStore store;
    private final CallerIdentityProvider callers;
    private final Clock clock;
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private final AtomicBoolean storeReachable = new AtomicBoolean(true);

    /**
     * Counts one request of the current caller under this policy and tells whether it may go on.
     *
     * @throws UnidentifiedCallerException when the request cannot be attributed to a caller
     */
    public RateLimitDecision check(RateLimitPolicy policy) {
        CallerId caller = callers.currentCaller().orElseThrow(UnidentifiedCallerException::new);
        long now = clock.millis();
        long windowId = policy.windowId(now);
        String key = policy.counterKey(caller, windowId);
        // get() first: computeIfAbsent may lock even when the key is there, and nearly every request finds it
        WindowCounter counter = counters.get(key);
        if (counter == null) {
            counter = counters.computeIfAbsent(key, k -> new WindowCounter(policy, windowId));
        }
        return counter.tryAcquire(now);
    }

    /**
     * Forgets the finished windows, pushes the local counts to the shared store and refreshes the global totals.
     * Never throws: a failure is logged once, reported in the result and retried at the next call.
     */
    public SynchronizationResult synchronize() {
        long now = clock.millis();
        // a finished window is useless to every pod: dropped even if it was never sent (and even if the store is down)
        counters.values().removeIf(counter -> counter.isFinished(now));

        List<Increment> increments = counters.entrySet().stream()
                .map(entry -> new Increment(entry.getKey(), entry.getValue().unsent(), entry.getValue().ttl()))
                .toList();
        if (increments.isEmpty()) {
            return new SynchronizationResult(0, true);
        }
        try {
            Map<String, Long> totals = store.addAll(increments);
            for (Increment increment : increments) {
                Long total = totals.get(increment.key());
                WindowCounter counter = counters.get(increment.key());
                if (total != null && counter != null) {
                    counter.confirm(increment.amount(), total);
                }
            }
            if (!storeReachable.getAndSet(true)) {
                log.info("Rate limit store reachable again, counters are shared again");
            }
            return new SynchronizationResult(increments.size(), true);
        }
        catch (RuntimeException e) {
            // What was not confirmed stays "unsent" and goes out at the next run. One warning per outage, not per run.
            if (storeReachable.getAndSet(false)) {
                log.warn("Rate limit store unreachable, limiting on local counts only: {}", e.toString());
            }
            return new SynchronizationResult(increments.size(), false);
        }
    }

    /** Number of counters currently held in memory (for monitoring: bounded by active callers x policies x 2 windows). */
    public int trackedCounters() {
        return counters.size();
    }
}
