package com.msj.trading.bff.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Full-context tests without a Keycloak: registers a fixed "keycloak" client registration and the
 * {@code BFF_CLIENT_SECRET} placeholder the main properties need to resolve.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(FixedKeycloakConfig.class)
@TestPropertySource(properties = "BFF_CLIENT_SECRET=test")
public @interface WithFixedKeycloak {
}