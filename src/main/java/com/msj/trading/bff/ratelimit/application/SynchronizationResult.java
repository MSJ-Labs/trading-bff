package com.msj.trading.bff.ratelimit.application;

/**
 * What a synchronisation did, for monitoring: how many counters it covered and whether the shared store answered.
 * A failure is reported here rather than thrown: the limiter keeps working on local counts.
 */
public record SynchronizationResult(int counters, boolean succeeded) {
}
