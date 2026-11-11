package com.msj.trading.bff.ratelimit.infrastructure.scheduling;

import com.msj.trading.bff.ratelimit.application.RateLimiter;
import com.msj.trading.bff.ratelimit.application.SynchronizationResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Triggers the periodic synchronisation of the rate limit counters with the shared store, outside of any request,
 * and measures it: this is the part that can degrade (Redis slow or down), unlike the in-memory decision.
 * {@code fixedDelay}: the next run starts one interval AFTER the previous one ended, so runs never overlap.
 *
 * <p>Metrics: {@code gateway.ratelimit.synchronization} (duration), {@code gateway.ratelimit.synchronization.failures}
 * and {@code gateway.ratelimit.counters} (counters held in memory).
 */
@Component
class RateLimitSynchronizationScheduler {

    private final RateLimiter rateLimiter;
    private final Timer duration;
    private final Counter failures;

    RateLimitSynchronizationScheduler(RateLimiter rateLimiter, MeterRegistry registry) {
        this.rateLimiter = rateLimiter;
        this.duration = Timer.builder("gateway.ratelimit.synchronization")
                .description("Time of one synchronisation of the rate limit counters with Redis")
                .register(registry);
        this.failures = Counter.builder("gateway.ratelimit.synchronization.failures")
                .description("Synchronisations that failed (Redis unreachable or too slow)")
                .register(registry);
        Gauge.builder("gateway.ratelimit.counters", rateLimiter, RateLimiter::trackedCounters)
                .description("Rate limit counters held in memory by this pod")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${app.rate-limit.sync-interval:1s}")
    void synchronize() {
        long start = System.nanoTime();
        SynchronizationResult result = rateLimiter.synchronize();
        duration.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        if (!result.succeeded()) {
            failures.increment();
        }
    }

    /** One last push on shutdown, so a pod that stops (rolling deploy) does not take its last counts with it. */
    @PreDestroy
    void flush() {
        rateLimiter.synchronize();
    }
}
