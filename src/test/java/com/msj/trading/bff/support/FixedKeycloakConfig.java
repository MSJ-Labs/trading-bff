package com.msj.trading.bff.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * Replaces OIDC discovery (no Keycloak in tests): Boot backs off when this bean exists.
 * Use it through {@link WithFixedKeycloak}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FixedKeycloakConfig {

    @Bean
    ClientRegistrationRepository clientRegistrationRepository() {
        return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("keycloak")
                .clientId("trading-bff")
                .clientSecret("test")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid")
                .authorizationUri("http://localhost:8180/realms/trading/protocol/openid-connect/auth")
                .tokenUri("http://localhost:8180/realms/trading/protocol/openid-connect/token")
                .jwkSetUri("http://localhost:8180/realms/trading/protocol/openid-connect/certs")
                .userNameAttributeName("sub")
                .build());
    }
}