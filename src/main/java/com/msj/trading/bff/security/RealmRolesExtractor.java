package com.msj.trading.bff.security;

import com.nimbusds.jwt.JWTParser;
import java.text.ParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads Keycloak realm roles (claim realm_access.roles) from an access token.
 * The token comes straight from Keycloak over the back channel and never leaves the server,
 * so it is parsed without re-verifying the signature.
 * <p>
 * Keycloak's built-in realm roles (offline_access, uma_authorization, default-roles-&lt;realm&gt;)
 * are dropped: they say nothing about what the user may do in Iris. Display purpose only, never
 * use this list to take an authorization decision.
 */
public final class RealmRolesExtractor {

    private static final Set<String> KEYCLOAK_BUILT_IN_ROLES = Set.of("offline_access", "uma_authorization");
    private static final String KEYCLOAK_DEFAULT_ROLES_PREFIX = "default-roles-";

    private RealmRolesExtractor() {
    }

    private static boolean isApplicationRole(String role) {
        return !KEYCLOAK_BUILT_IN_ROLES.contains(role) && !role.startsWith(KEYCLOAK_DEFAULT_ROLES_PREFIX);
    }

    public static List<String> extract(String accessToken) {
        try {
            Map<String, Object> realmAccess = JWTParser.parse(accessToken).getJWTClaimsSet()
                    .getJSONObjectClaim("realm_access");
            if (realmAccess != null && realmAccess.get("roles") instanceof List<?> roles) {
                return roles.stream().map(String::valueOf).filter(RealmRolesExtractor::isApplicationRole).toList();
            }
            return List.of();
        } catch (ParseException e) {
            throw new InvalidAccessTokenException(e);
        }
    }
}