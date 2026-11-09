package com.msj.trading.bff.api;

import com.msj.trading.bff.security.RealmRolesExtractor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bff")
class UserController {

    @GetMapping("/user")
    UserResponse user(@AuthenticationPrincipal OidcUser user,
                      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client) {
        return new UserResponse(
                user.getSubject(),
                user.getPreferredUsername(),
                user.getFullName(),
                user.getGivenName(),
                user.getFamilyName(),
                user.getEmail(),
                user.getEmailVerified(),
                RealmRolesExtractor.extract(client.getAccessToken().getTokenValue()));
    }
}