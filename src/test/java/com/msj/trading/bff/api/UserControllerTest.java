package com.msj.trading.bff.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

class UserControllerTest {

    private final UserController controller = new UserController();

    @Test
    void user_mapsIdentityClaimsAndRoles() {
        var idToken = new OidcIdToken("id-token", Instant.now(), Instant.now().plusSeconds(60), Map.of(
                "sub", "11111111-1111-1111-1111-111111111111",
                "preferred_username", "jdoe",
                "name", "John Doe",
                "given_name", "John",
                "family_name", "Doe",
                "email", "john@doe.com",
                "email_verified", true));
        var jwt = new PlainJWT(new JWTClaimsSet.Builder()
                .claim("realm_access", Map.of("roles", List.of("trader")))
                .build()).serialize();
        var accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, jwt,
                Instant.now(), Instant.now().plusSeconds(60));
        var client = mock(OAuth2AuthorizedClient.class);
        when(client.getAccessToken()).thenReturn(accessToken);

        var response = controller.user(new DefaultOidcUser(List.of(), idToken), client);

        assertThat(response).isEqualTo(new UserResponse(
                "11111111-1111-1111-1111-111111111111", "jdoe", "John Doe", "John", "Doe",
                "john@doe.com", true, List.of("trader")));
    }
}