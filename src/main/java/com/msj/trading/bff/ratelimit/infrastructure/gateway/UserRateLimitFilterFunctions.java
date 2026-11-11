package com.msj.trading.bff.ratelimit.infrastructure.gateway;

import com.msj.trading.bff.ratelimit.application.RateLimiter;
import com.msj.trading.bff.ratelimit.domain.exception.UnidentifiedCallerException;
import com.msj.trading.bff.ratelimit.domain.valueobject.RateLimitDecision;
import com.msj.trading.bff.ratelimit.domain.valueobject.RateLimitPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.cloud.gateway.server.mvc.common.MvcUtils;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayServerResponse;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Filter "UserRateLimit" usable from application.properties with simple args only:
 * {@code scope} (a name, e.g. read or write: one counter per user and scope), {@code capacity} and {@code period}.
 * Over the limit it answers 429 (with {@code Retry-After}) and the downstream service is never called; otherwise
 * the response carries {@code X-RateLimit-Remaining}. A request that cannot be attributed to a user gets a 401.
 *
 * <p>Public static methods of this class are exposed as filters (name = method name capitalized). The Gateway finds
 * them by reflection once at startup and builds one filter per route; nothing is reflective per request.
 *
 * <p>The decision itself is a few in-memory operations (see {@link RateLimiter}), on the request thread: no Redis, no
 * thread hop. It is timed in {@value #OVERHEAD_TIMER} (tag {@code scope}), refusals are counted in
 * {@value #DENIED_COUNTER}.
 */
public final class UserRateLimitFilterFunctions {

    public static final String OVERHEAD_TIMER = "gateway.ratelimit.overhead";
    public static final String DENIED_COUNTER = "gateway.ratelimit.denied";
    public static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private UserRateLimitFilterFunctions() {
    }

    /** What the filter needs from the application context, looked up once (on the first request of the route). */
    private record Collaborators(RateLimiter limiter, Timer overhead, Counter denied) {
    }

    /** Fails at startup when the configuration is wrong (see {@link RateLimitPolicy}). */
    public static HandlerFilterFunction<ServerResponse, ServerResponse> userRateLimit(String scope, long capacity,
                                                                                      Duration period) {
        RateLimitPolicy policy = new RateLimitPolicy(scope, capacity, period);
        // One memo per filter instance (= per route): the application context is only reachable from a request,
        // not when the route is built at startup, so the collaborators are fetched on the first request.
        AtomicReference<Collaborators> memo = new AtomicReference<>();
        return (request, next) -> {
            Collaborators collaborators = collaborators(memo, request, scope);

            RateLimitDecision decision;
            long start = System.nanoTime();
            try {
                decision = collaborators.limiter().check(policy);
            }
            catch (UnidentifiedCallerException e) {
                return unauthorized(e.getMessage());
            }
            finally {
                collaborators.overhead().record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            }

            if (!decision.allowed()) {
                collaborators.denied().increment();
                return tooManyRequests(decision);
            }
            ServerResponse response = next.handle(request);
            response.headers().set(REMAINING_HEADER, String.valueOf(decision.remaining()));
            return response;
        };
    }

    private static ServerResponse unauthorized(String detail) {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication required", detail, headers -> {
        });
    }

    private static ServerResponse tooManyRequests(RateLimitDecision decision) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded", "Too many requests, please retry later",
                headers -> {
                    headers.set(REMAINING_HEADER, "0");
                    headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfter().toSeconds()));
                });
    }

    /**
     * A JSON problem (RFC 9457), the same shape as the other errors of the BFF. Built with the Gateway's own response
     * type: unlike a plain {@code ServerResponse}, its headers stay writable, so a filter further out can still add
     * its own.
     */
    private static ServerResponse problem(HttpStatus status, String title, String detail,
                                          Consumer<HttpHeaders> headers) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return GatewayServerResponse.status(status)
                .headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static Collaborators collaborators(AtomicReference<Collaborators> memo, ServerRequest request,
                                               String scope) {
        Collaborators collaborators = memo.get();
        if (collaborators == null) {
            ApplicationContext context = MvcUtils.getApplicationContext(request);
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            collaborators = new Collaborators(
                    context.getBean(RateLimiter.class),
                    Timer.builder(OVERHEAD_TIMER)
                            .description("Time spent deciding the rate limit of a request")
                            .tag("scope", scope)
                            .publishPercentileHistogram()
                            .register(registry),
                    Counter.builder(DENIED_COUNTER)
                            .description("Requests refused with 429")
                            .tag("scope", scope)
                            .register(registry));
            memo.set(collaborators);
        }
        return collaborators;
    }
}
