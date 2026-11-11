package com.msj.trading.bff.ratelimit.infrastructure.config;

import com.msj.trading.bff.ratelimit.application.RateLimiter;
import com.msj.trading.bff.ratelimit.application.port.out.CallerIdentityProvider;
import com.msj.trading.bff.ratelimit.application.port.out.RateLimitCounterStore;
import com.msj.trading.bff.ratelimit.infrastructure.gateway.UserRateLimitFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.filter.FilterSupplier;
import org.springframework.cloud.gateway.server.mvc.filter.SimpleFilterSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * Wiring of the Gateway rate limiter. The limiter is a plain class (no Spring annotation in the application layer),
 * declared here with its two ports; scheduling is enabled for the periodic synchronisation.
 */
@Configuration
@EnableScheduling
class RateLimitConfig {

    @Bean
    RateLimiter rateLimiter(RateLimitCounterStore store, CallerIdentityProvider callers, Clock rateLimitClock) {
        return new RateLimiter(store, callers, rateLimitClock);
    }

    /** Makes the "UserRateLimit" filter available to the routes declared in application.properties. */
    @Bean
    FilterSupplier userRateLimitFilterSupplier() {
        return new SimpleFilterSupplier(UserRateLimitFilterFunctions.class);
    }

    /** The time source of the limiter (windows are aligned on it); tests can use their own. */
    @Bean
    Clock rateLimitClock() {
        return Clock.systemUTC();
    }
}
