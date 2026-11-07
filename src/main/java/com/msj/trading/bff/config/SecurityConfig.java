package com.msj.trading.bff.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
public class SecurityConfig {

    /**
     * Actuator chain: only matches actuator endpoints, which live on the management port (9091).
     * No login here: Kubernetes probes cannot authenticate. Protection is network isolation,
     * the Ingress never routes this port.
     */
    @Bean
    @Order(1)
    SecurityFilterChain actuatorChain(HttpSecurity http) {
        return http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    /** Application chain (port 8081): everything requires an authenticated session. */
    @Bean
    @Order(2)
    SecurityFilterChain applicationChain(HttpSecurity http,
                                         ClientRegistrationRepository clientRegistrations,
                                         @Value("${app.frontend-url}") String frontendUrl) {
        var oidcLogout = new OidcClientInitiatedLogoutSuccessHandler(clientRegistrations);
        oidcLogout.setPostLogoutRedirectUri(frontendUrl);

        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2Login(login -> login.defaultSuccessUrl(frontendUrl, true))
                // fetch/XHR to /api/** must get a 401, not a 302 to the Keycloak login page
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        PathPatternRequestMatcher.withDefaults().matcher("/api/**")))
                .logout(logout -> logout.logoutSuccessHandler(oidcLogout))
                // XSRF-TOKEN cookie readable by the SPA, echoed back in the X-XSRF-TOKEN header
                .csrf(CsrfConfigurer::spa)
                .build();
    }
}