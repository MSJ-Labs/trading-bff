package com.msj.trading.bff.ratelimit.domain.valueobject;

import com.msj.trading.bff.shared.domain.ValueObject;

/** Who is calling the gateway: the Keycloak {@code sub} of the logged-in user. One rate limit bucket per caller. */
public record CallerId(String value) implements ValueObject {

    public CallerId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("CallerId must not be blank");
        }
    }
}