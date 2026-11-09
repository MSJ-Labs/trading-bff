package com.msj.trading.bff.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

import com.msj.trading.bff.security.JsonLogoutRedirectStrategy;
import com.msj.trading.bff.security.RedisOidcSessionRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.boot.session.autoconfigure.DefaultCookieSerializerCustomizer;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OidcBackChannelLogoutHandler;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionInformation;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionRegistry;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import tools.jackson.databind.json.JsonMapper;

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

    /**
     * Shared OIDC session registry (Redis): maps Keycloak's sid/sub to the BFF HTTP session. The
     * default in-memory one would only work on a single pod. TTL = HTTP session timeout.
     */
    @Bean
    OidcSessionRegistry oidcSessionRegistry(RedisConnectionFactory connectionFactory,
                                            StringRedisTemplate stringRedisTemplate,
                                            @Value("${spring.session.timeout}") Duration sessionTimeout) {
        var template = new RedisTemplate<String, OidcSessionInformation>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new JdkSerializationRedisSerializer(getClass().getClassLoader()));
        template.afterPropertiesSet();
        return new RedisOidcSessionRegistry(template, stringRedisTemplate, sessionTimeout);
    }

    /**
     * Raw session id in the SESSION cookie (no Base64): the back-channel handler puts the raw id in the
     * cookie it sends to /logout, and Spring Session would Base64-decode it into a wrong id.
     */
    @Bean
    DefaultCookieSerializerCustomizer rawSessionCookie() {
        return serializer -> serializer.setUseBase64Encoding(false);
    }

    /**
     * The back-channel handler closes a session with a server-side POST /logout that carries no
     * XSRF cookie, so CSRF would reject it (403). That one call is exempt: it must carry Spring's
     * internal marker AND come from the loopback interface, so a browser (or anyone through the
     * Ingress, whose source address is never loopback) cannot use it to skip CSRF.
     */
    private static boolean isInternalBackChannelLogout(HttpServletRequest request) {
        if (!"true".equals(request.getParameter("_spring_security_internal_logout"))) {
            return false;
        }
        try {
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (UnknownHostException _) {
            return false;
        }
    }

    /** Application chain (port 8081): everything requires an authenticated session. */
    @Bean
    @Order(2)
    SecurityFilterChain applicationChain(HttpSecurity http,
                                         ClientRegistrationRepository clientRegistrations,
                                         OidcSessionRegistry oidcSessionRegistry,
                                         @Value("${app.frontend-url}") String frontendUrl,
                                         @Value("${app.backchannel-logout-uri}") String backchannelLogoutUri,
                                         JsonMapper jsonMapper) {
        var oidcLogout = new OidcClientInitiatedLogoutSuccessHandler(clientRegistrations);
        oidcLogout.setPostLogoutRedirectUri(frontendUrl);
        // the SPA calls POST /logout by XHR: answer 200 {"logoutUrl"} instead of a 302 it cannot follow
        oidcLogout.setRedirectStrategy(new JsonLogoutRedirectStrategy(jsonMapper));

        // On back-channel logout the BFF closes the session by calling its OWN /logout with the session
        // cookie. By default it calls the host of the incoming request (host.docker.internal, unknown
        // outside Docker), so the target is fixed. Spring Session's cookie is SESSION, not JSESSIONID.
        var backChannelLogout = new OidcBackChannelLogoutHandler(oidcSessionRegistry);
        backChannelLogout.setLogoutUri(backchannelLogoutUri);
        backChannelLogout.setSessionCookieName("SESSION");

        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2Login(login -> login.defaultSuccessUrl(frontendUrl, true))
                // fetch/XHR to /api/** and /bff/** must get a 401, not a 302 to the Keycloak login page
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        new OrRequestMatcher(
                                PathPatternRequestMatcher.withDefaults().matcher("/api/**"),
                                PathPatternRequestMatcher.withDefaults().matcher("/bff/**"))))
                .logout(logout -> logout.logoutSuccessHandler(oidcLogout))
                // Keycloak -> POST /logout/connect/oidc/backchannel (signed logout_token, no cookie, no CSRF)
                .oidcLogout(oidc -> oidc.backChannel(backChannel -> backChannel.logoutHandler(backChannelLogout)))
                // XSRF-TOKEN cookie readable by the SPA, echoed back in the X-XSRF-TOKEN header
                .csrf(csrf -> {
                    csrf.spa();
                    csrf.ignoringRequestMatchers(SecurityConfig::isInternalBackChannelLogout);
                })
                .build();
    }
}