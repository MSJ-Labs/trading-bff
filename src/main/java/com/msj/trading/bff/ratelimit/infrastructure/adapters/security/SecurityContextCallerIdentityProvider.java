package com.msj.trading.bff.ratelimit.infrastructure.adapters.security;

import com.msj.trading.bff.ratelimit.application.port.out.CallerIdentityProvider;
import com.msj.trading.bff.ratelimit.domain.valueobject.CallerId;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Reads the caller from the Spring Security context: the user logged in through Keycloak (OIDC) has the {@code sub}
 * as subject. Anything else (anonymous, no subject) gives an empty result, never a guessed identity.
 */
@Component
class SecurityContextCallerIdentityProvider implements CallerIdentityProvider {

    @Override
    public Optional<CallerId> currentCaller() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof OidcUser user
                && user.getSubject() != null && !user.getSubject().isBlank()) {
            return Optional.of(new CallerId(user.getSubject()));
        }
        return Optional.empty();
    }
}