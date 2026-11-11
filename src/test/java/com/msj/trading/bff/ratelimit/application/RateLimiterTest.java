package com.msj.trading.bff.ratelimit.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.msj.trading.bff.ratelimit.application.port.out.CallerIdentityProvider;
import com.msj.trading.bff.ratelimit.application.port.out.RateLimitCounterStore;
import com.msj.trading.bff.ratelimit.domain.exception.UnidentifiedCallerException;
import com.msj.trading.bff.ratelimit.domain.valueobject.CallerId;
import com.msj.trading.bff.ratelimit.domain.valueobject.RateLimitDecision;
import com.msj.trading.bff.ratelimit.domain.valueobject.RateLimitPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The limiter without Spring or Redis: a fake shared store stands in for Redis, and two limiters sharing it stand in
 * for two BFF pods.
 */
class RateLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final long LIMIT = 10;
    private static final RateLimitPolicy READ = new RateLimitPolicy("read", LIMIT, WINDOW);
    private static final RateLimitPolicy WRITE = new RateLimitPolicy("write", LIMIT, WINDOW);
    private static final CallerId ALICE = new CallerId("alice");
    private static final CallerId BOB = new CallerId("bob");
    /** Alice's counter in the first window (the fake clock starts at the epoch, window 0). */
    private static final String ALICE_READ_KEY = READ.counterKey(ALICE, 0);

    private final FakeStore store = new FakeStore();
    private final FakeCallers callers = new FakeCallers();
    private final MutableClock clock = new MutableClock();
    private final RateLimiter podA = new RateLimiter(store, callers, clock);
    private final RateLimiter podB = new RateLimiter(store, callers, clock);

    @Test
    void refusesOnceTheLimitIsReached_withoutTouchingTheStore() {
        for (int i = 0; i < LIMIT; i++) {
            assertThat(check(podA, ALICE, READ).allowed()).isTrue();
        }

        assertThat(check(podA, ALICE, READ).allowed()).isFalse();
        assertThat(store.calls).isZero();
    }

    @Test
    void aRefusalSaysHowLongToWait_roundedUpToWholeSeconds() {
        for (int i = 0; i < LIMIT; i++) {
            check(podA, ALICE, READ);
        }
        assertThat(check(podA, ALICE, READ).retryAfter()).isEqualTo(Duration.ofSeconds(60));

        clock.advance(Duration.ofMillis(30_500)); // 29.5 s left in the window
        assertThat(check(podA, ALICE, READ).retryAfter()).isEqualTo(Duration.ofSeconds(30));

        clock.advance(Duration.ofMillis(29_400)); // 0.1 s left: never "0", the client must not retry at once
        assertThat(check(podA, ALICE, READ).retryAfter()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void remainingCountsDown() {
        assertThat(check(podA, ALICE, READ).remaining()).isEqualTo(LIMIT - 1);
        assertThat(check(podA, ALICE, READ).remaining()).isEqualTo(LIMIT - 2);
    }

    @Test
    void callersAndScopesHaveSeparateCounters() {
        for (int i = 0; i < LIMIT; i++) {
            check(podA, ALICE, READ);
        }

        assertThat(check(podA, ALICE, READ).allowed()).isFalse();
        assertThat(check(podA, BOB, READ).allowed()).isTrue();
        assertThat(check(podA, ALICE, WRITE).allowed()).isTrue();
    }

    @Test
    void aRequestWithoutCaller_isRejected_notGivenASharedBucket() {
        callers.current = Optional.empty();

        assertThatThrownBy(() -> podA.check(READ)).isInstanceOf(UnidentifiedCallerException.class);
    }

    @Test
    void aPodSeesWhatTheOtherPodLetThrough_afterSynchronising() {
        for (int i = 0; i < 6; i++) {
            check(podA, ALICE, READ);
        }
        podA.synchronize();
        // pod B has never seen Alice: its first request is counted blind (a pod only learns the global total at
        // its next synchronisation, which is part of the documented overshoot)
        assertThat(check(podB, ALICE, READ).allowed()).isTrue();
        podB.synchronize();

        // now pod B knows 7 requests went through (6 on pod A, 1 on itself): only 3 are left for it
        int allowedOnB = 0;
        for (int i = 0; i < LIMIT; i++) {
            if (check(podB, ALICE, READ).allowed()) {
                allowedOnB++;
            }
        }
        assertThat(allowedOnB).isEqualTo(3);
    }

    @Test
    void withoutSynchronising_eachPodOnlyKnowsItsOwnCount() {
        for (int i = 0; i < LIMIT; i++) {
            check(podA, ALICE, READ);
        }

        // documented trade-off: until the next synchronisation, pod B cannot know
        assertThat(check(podB, ALICE, READ).allowed()).isTrue();
    }

    @Test
    void synchronisingTwiceDoesNotCountRequestsTwice() {
        for (int i = 0; i < 3; i++) {
            check(podA, ALICE, READ);
        }

        podA.synchronize();
        podA.synchronize();

        assertThat(store.total(ALICE_READ_KEY)).isEqualTo(3);
        assertThat(check(podA, ALICE, READ).remaining()).isEqualTo(LIMIT - 4);
    }

    @Test
    void aRequestDuringASynchronisation_isCountedExactlyOnce() {
        for (int i = 0; i < 3; i++) {
            check(podA, ALICE, READ);
        }
        // a 4th request arrives while the batch is on its way to the store
        store.whileAddingAll = () -> check(podA, ALICE, READ);

        podA.synchronize();
        store.whileAddingAll = null;

        // the store got 3; the 4th is still "unsent": 5th request leaves 10 - 5 = 5
        assertThat(check(podA, ALICE, READ).remaining()).isEqualTo(LIMIT - 5);
        podA.synchronize();
        assertThat(store.total(ALICE_READ_KEY)).isEqualTo(5);
    }

    @Test
    void manyThreadsOnOneCaller_getExactlyTheLimit() throws Exception {
        RateLimitPolicy policy = new RateLimitPolicy("read", 100, WINDOW);
        int threads = 8;
        int attemptsPerThread = 50; // 400 attempts for 100 places
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                executor.submit(() -> {
                    go.await();
                    for (int i = 0; i < attemptsPerThread; i++) {
                        if (podA.check(policy).allowed()) {
                            allowed.incrementAndGet();
                        }
                    }
                    return null;
                });
            }
            go.countDown();
        }
        finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(allowed.get()).isEqualTo(100);
    }

    @Test
    void storeDown_keepsLimitingLocally_andLosesNothingOnceItIsBack() {
        store.down = true;
        for (int i = 0; i < LIMIT; i++) {
            check(podA, ALICE, READ);
        }

        SynchronizationResult failed = podA.synchronize(); // fails, must not throw
        assertThat(failed.succeeded()).isFalse();
        assertThat(check(podA, ALICE, READ).allowed()).isFalse();

        store.down = false;
        assertThat(podA.synchronize().succeeded()).isTrue();
        assertThat(store.total(ALICE_READ_KEY)).isEqualTo(LIMIT);
    }

    @Test
    void ifTheStoreAppliedTheBatchButTheAnswerWasLost_theBatchIsSentAgain_failingStrict() {
        for (int i = 0; i < 3; i++) {
            check(podA, ALICE, READ);
        }
        store.failAfterApplying = true; // e.g. a timeout: Redis did the work, the pod never saw the answer
        podA.synchronize();
        store.failAfterApplying = false;

        podA.synchronize();

        // documented: the 3 requests are counted twice, never forgotten
        assertThat(store.total(ALICE_READ_KEY)).isEqualTo(6);
    }

    @Test
    void aStoreThatLostItsData_doesNotMakeThePodForget() {
        for (int i = 0; i < 5; i++) {
            check(podA, ALICE, READ);
        }
        podA.synchronize();
        store.totals.clear(); // Redis restarted or flushed

        podA.synchronize();

        assertThat(check(podA, ALICE, READ).remaining()).isEqualTo(LIMIT - 6);
    }

    @Test
    void aNewWindowStartsFromZero() {
        for (int i = 0; i < LIMIT; i++) {
            check(podA, ALICE, READ);
        }
        assertThat(check(podA, ALICE, READ).allowed()).isFalse();

        clock.advance(WINDOW);

        assertThat(check(podA, ALICE, READ).allowed()).isTrue();
    }

    @Test
    void finishedWindowsAreForgotten_andNotSent() {
        check(podA, ALICE, READ);
        clock.advance(WINDOW);

        SynchronizationResult result = podA.synchronize();

        assertThat(podA.trackedCounters()).isZero();
        assertThat(result.counters()).isZero();
        assertThat(store.calls).isZero(); // nothing left to send, so no call at all
        assertThat(store.total(ALICE_READ_KEY)).isZero(); // useless to every pod: dropped, not sent
    }

    @Test
    void finishedWindowsAreForgotten_evenWhileTheStoreIsDown() {
        store.down = true;
        check(podA, ALICE, READ);
        clock.advance(WINDOW);

        podA.synchronize();

        assertThat(podA.trackedCounters()).isZero(); // memory does not grow during an outage
    }

    /** One request of this caller on this pod. */
    private RateLimitDecision check(RateLimiter pod, CallerId caller, RateLimitPolicy policy) {
        callers.current = Optional.of(caller);
        return pod.check(policy);
    }

    private static final class FakeCallers implements CallerIdentityProvider {

        private volatile Optional<CallerId> current = Optional.of(ALICE);

        @Override
        public Optional<CallerId> currentCaller() {
            return current;
        }
    }

    /** Shared counters in memory, with switches to simulate Redis being down or slow. */
    private static final class FakeStore implements RateLimitCounterStore {

        private final Map<String, Long> totals = new HashMap<>();
        private boolean down;
        private boolean failAfterApplying;
        private Runnable whileAddingAll;
        private int calls;

        @Override
        public Map<String, Long> addAll(List<Increment> increments) {
            if (down) {
                throw new IllegalStateException("store down");
            }
            calls++;
            if (whileAddingAll != null) {
                whileAddingAll.run();
            }
            Map<String, Long> result = new HashMap<>();
            increments.forEach(increment ->
                    result.put(increment.key(), totals.merge(increment.key(), increment.amount(), Long::sum)));
            if (failAfterApplying) {
                throw new IllegalStateException("answer lost");
            }
            return result;
        }

        long total(String key) {
            return totals.getOrDefault(key, 0L);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.EPOCH;

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
