package com.msj.trading.bff.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class JsonLogoutRedirectStrategyTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final JsonLogoutRedirectStrategy strategy = new JsonLogoutRedirectStrategy(jsonMapper);

    @Test
    void answers200WithLogoutUrlInsteadOfRedirecting() throws Exception {
        var response = new MockHttpServletResponse();

        strategy.sendRedirect(new MockHttpServletRequest(), response,
                "http://localhost:8180/realms/trading/protocol/openid-connect/logout?id_token_hint=abc");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getRedirectedUrl()).isNull();
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(jsonMapper.readTree(response.getContentAsString()).get("logoutUrl").asString())
                .isEqualTo("http://localhost:8180/realms/trading/protocol/openid-connect/logout?id_token_hint=abc");
    }

    @Test
    void escapesSpecialCharactersInUrl() throws Exception {
        var response = new MockHttpServletResponse();
        var url = "http://kc/logout?redirect=\"x\"&a=b";

        strategy.sendRedirect(new MockHttpServletRequest(), response, url);

        assertThat(jsonMapper.readTree(response.getContentAsString()).get("logoutUrl").asString())
                .isEqualTo(url);
    }
}