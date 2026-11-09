package com.msj.trading.bff.security;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.RedirectStrategy;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replaces the 302 to Keycloak's end-session endpoint by a 200 {"logoutUrl": "..."}.
 * An XHR/fetch cannot follow a cross-origin redirect nor read its Location header, so the SPA
 * gets the URL and navigates to it itself (full page).
 */
public final class JsonLogoutRedirectStrategy implements RedirectStrategy {

    private final JsonMapper jsonMapper;

    public JsonLogoutRedirectStrategy(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void sendRedirect(HttpServletRequest request, HttpServletResponse response, String url)
            throws IOException {
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), Map.of("logoutUrl", url));
    }
}