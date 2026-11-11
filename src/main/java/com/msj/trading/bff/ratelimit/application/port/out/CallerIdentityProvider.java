package com.msj.trading.bff.ratelimit.application.port.out;

import com.msj.trading.bff.ratelimit.domain.valueobject.CallerId;

import java.util.Optional;

/** Port: tells the rate limiting who the current caller is, without knowing how authentication works. */
public interface CallerIdentityProvider {

    /** The authenticated caller, or empty when the request cannot be attributed to anyone. */
    Optional<CallerId> currentCaller();
}
