package com.msj.trading.bff.security;

/** The access token held in session cannot be read: the session is unusable, the user must log in again. */
public class InvalidAccessTokenException extends RuntimeException {

    public InvalidAccessTokenException(Throwable cause) {
        super("Unreadable access token", cause);
    }
}