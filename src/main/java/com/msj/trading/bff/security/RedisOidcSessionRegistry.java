package com.msj.trading.bff.security;

import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.client.oidc.authentication.logout.OidcLogoutToken;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionInformation;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionRegistry;

/**
 * Redis-backed {@link OidcSessionRegistry}: links a Keycloak SSO session (claims {@code sid} and
 * {@code sub} of the ID token) to the BFF's own HTTP session, so a back-channel logout received by
 * ANY pod can find and close the session wherever it lives.
 *
 * <p>Redis layout (all keys expire with the session timeout, so nothing leaks):
 * <ul>
 *   <li>{@code <p>session:<httpSessionId>}  → serialized {@link OidcSessionInformation}</li>
 *   <li>{@code <p>sid:<issuer>|<sid>}       → httpSessionId (one browser session)</li>
 *   <li>{@code <p>sub:<issuer>|<sub>}       → set of httpSessionIds (all sessions of a user)</li>
 * </ul>
 */
@NullMarked
@RequiredArgsConstructor
public class RedisOidcSessionRegistry implements OidcSessionRegistry {

    private static final String PREFIX = "trading-bff:oidc:";

    private final RedisOperations<String, OidcSessionInformation> sessions;
    private final StringRedisTemplate indexes;
    private final Duration ttl;

    @Override
    public void saveSessionInformation(OidcSessionInformation info) {
        var user = info.getPrincipal();
        String issuer = issuer(user.getIssuer());
        String sid = user.getClaimAsString("sid");

        sessions.opsForValue().set(sessionKey(info.getSessionId()), info, ttl);

        if (sid != null) {
            indexes.opsForValue().set(sidKey(issuer, sid), info.getSessionId(), ttl);
        }
        String subject = Objects.requireNonNull(user.getSubject(), "OIDC subject (sub claim) is missing");
        String subKey = subKey(issuer, subject);
        indexes.opsForSet().add(subKey, info.getSessionId());
        indexes.expire(subKey, ttl);
    }

    /** Normal logout of this BFF: the user clicked "log out", we only know the HTTP session id. */
    @Override
    public @Nullable OidcSessionInformation removeSessionInformation(String sessionId) {
        Objects.requireNonNull(sessionId, "HTTP session id must not be null");
        var info = sessions.opsForValue().getAndDelete(sessionKey(sessionId));
        if (info != null) {
            removeIndexes(info);
        }
        return info;
    }

    /** Back-channel logout: Keycloak tells us "this sid (or this user) is logged out". */
    @Override
    public Iterable<OidcSessionInformation> removeSessionInformation(OidcLogoutToken token) {
        String issuer = issuer(token.getIssuer());

        List<String> sessionIds = new ArrayList<>();
        String tokenSid = token.getSessionId();
        if (tokenSid != null) {
            String id = indexes.opsForValue().get(sidKey(issuer, tokenSid));
            if (id != null) {
                sessionIds.add(id);
            }
        } else {
            String subject = Objects.requireNonNull(token.getSubject(), "OIDC subject (sub claim) is missing");
            Set<String> ids = indexes.opsForSet().members(subKey(issuer, subject));
            if (ids != null) {
                sessionIds.addAll(ids);
            }
        }

        return sessionIds.stream()
                .map(this::removeSessionInformation)
                .filter(Objects::nonNull)
                .toList();
    }

    private void removeIndexes(OidcSessionInformation info) {
        var user = info.getPrincipal();
        String issuer = issuer(user.getIssuer());
        String sid = user.getClaimAsString("sid");
        if (sid != null) {
            indexes.delete(sidKey(issuer, sid));
        }
        String subject = Objects.requireNonNull(user.getSubject(), "OIDC subject (sub claim) is missing");
        indexes.opsForSet().remove(subKey(issuer, subject), info.getSessionId());
    }

    /** Fails with a readable message instead of an anonymous NPE (a token without iss is invalid anyway). */
    private static String issuer(@Nullable URL issuer) {
        return Objects.requireNonNull(issuer, "OIDC issuer (iss claim) is missing").toString();
    }

    private static String sessionKey(String sessionId) {
        return PREFIX + "session:" + sessionId;
    }

    private static String sidKey(String issuer, String sid) {
        return PREFIX + "sid:" + issuer + "|" + sid;
    }

    private static String subKey(String issuer, String sub) {
        return PREFIX + "sub:" + issuer + "|" + sub;
    }
}