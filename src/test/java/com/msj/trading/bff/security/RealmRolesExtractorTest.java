package com.msj.trading.bff.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RealmRolesExtractorTest {

    private static String token(JWTClaimsSet claims) {
        return new PlainJWT(claims).serialize();
    }

    @Test
    void extract_returnsRealmRoles() {
        var claims = new JWTClaimsSet.Builder()
                .claim("realm_access", Map.of("roles", List.of("trader", "offline_access")))
                .build();

        assertThat(RealmRolesExtractor.extract(token(claims))).containsExactly("trader", "offline_access");
    }

    @Test
    void extract_withoutRealmAccess_returnsEmptyList() {
        var claims = new JWTClaimsSet.Builder().subject("abc").build();

        assertThat(RealmRolesExtractor.extract(token(claims))).isEmpty();
    }

    @Test
    void extract_withoutRolesKey_returnsEmptyList() {
        var claims = new JWTClaimsSet.Builder().claim("realm_access", Map.of()).build();

        assertThat(RealmRolesExtractor.extract(token(claims))).isEmpty();
    }

    @Test
    void extract_malformedToken_throwsInvalidAccessToken() {
        assertThatThrownBy(() -> RealmRolesExtractor.extract("not-a-jwt"))
                .isInstanceOf(InvalidAccessTokenException.class);
    }
}