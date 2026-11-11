package com.msj.trading.bff.ratelimit.domain.valueobject;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitPolicyTest {

    @Test
    void aBlankScopeIsRejected() {
        assertThatThrownBy(() -> new RateLimitPolicy(" ", 10, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitPolicy(null, 10, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLimitThatIsNotPositiveIsRejected() {
        assertThatThrownBy(() -> new RateLimitPolicy("read", 0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitPolicy("read", -5, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aWindowUnderOneSecondIsRejected_whichCatchesABareNumberMeantAsSeconds() {
        // "period=60" in a property is read as 60 milliseconds
        assertThatThrownBy(() -> new RateLimitPolicy("read", 10, Duration.ofMillis(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("60s");
        assertThatThrownBy(() -> new RateLimitPolicy("read", 10, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitPolicy("read", 10, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void windowsAreAlignedOnTheClock() {
        RateLimitPolicy policy = new RateLimitPolicy("read", 10, Duration.ofMinutes(1));

        assertThat(policy.windowId(0)).isZero();
        assertThat(policy.windowId(59_999)).isZero();
        assertThat(policy.windowId(60_000)).isEqualTo(1);
        assertThat(policy.windowEndMillis(0)).isEqualTo(60_000);
        assertThat(policy.counterTtl()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void aCounterKeyNamesTheScopeTheWindowLengthTheCallerAndTheWindow() {
        RateLimitPolicy policy = new RateLimitPolicy("read", 10, Duration.ofMinutes(1));

        assertThat(policy.counterKey(new CallerId("alice"), 7)).isEqualTo("read:60000:alice:7");
    }
}
