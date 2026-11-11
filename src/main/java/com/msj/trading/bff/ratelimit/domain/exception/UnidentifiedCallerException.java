package com.msj.trading.bff.ratelimit.domain.exception;

/**
 * The request has no identifiable caller. Rate limits are per user, so there is nothing to count against: guessing an
 * identity (e.g. one shared "anonymous" bucket) would let one client starve all the others.
 */
public class UnidentifiedCallerException extends RuntimeException {

    public UnidentifiedCallerException() {
        super("Rate limiting needs an authenticated caller");
    }
}
