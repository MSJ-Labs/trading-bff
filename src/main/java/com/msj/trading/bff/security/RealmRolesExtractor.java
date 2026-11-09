package com.msj.trading.bff.security;

import com.nimbusds.jwt.JWTParser;
import java.text.ParseException;
import java.util.List;
import java.util.Map;

/**
 * Reads Keycloak realm roles (claim realm_access.roles) from an access token.
 * The token comes straight from Keycloak over the back channel and never leaves the server,
 * so it is parsed without re-verifying the signature.
 */
public final class RealmRolesExtractor {

    private RealmRolesExtractor() {
    }

    public static List<String> extract(String accessToken) {
        try {
            Map<String, Object> realmAccess = JWTParser.parse(accessToken).getJWTClaimsSet()
                    .getJSONObjectClaim("realm_access");
            if (realmAccess != null && realmAccess.get("roles") instanceof List<?> roles) {
                return roles.stream().map(String::valueOf).toList();
            }
            return List.of();
        } catch (ParseException e) {
            throw new InvalidAccessTokenException(e);
        }
    }
}