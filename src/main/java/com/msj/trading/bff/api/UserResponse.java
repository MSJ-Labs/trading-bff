package com.msj.trading.bff.api;

import java.util.List;

/** Current user as seen by the SPA. Identity claims and roles only, never a token. */
public record UserResponse(String sub, String username, String name, String givenName,
                           String familyName, String email, Boolean emailVerified, List<String> roles) {
}