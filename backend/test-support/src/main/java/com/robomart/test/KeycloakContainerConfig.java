package com.robomart.test;

import dasniko.testcontainers.keycloak.KeycloakContainer;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

@TestConfiguration(proxyBeanMethods = false)
public class KeycloakContainerConfig {

    private static final KeycloakContainer KEYCLOAK =
            new KeycloakContainer("quay.io/keycloak/keycloak:26.1.4")
                    .withRealmImportFile("test-realm.json");

    static {
        KEYCLOAK.start();
    }

    @Bean
    DynamicPropertyRegistrar keycloakProperties() {
        return registry -> {
            registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> KEYCLOAK.getAuthServerUrl() + "/realms/robomart");
            registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> KEYCLOAK.getAuthServerUrl() + "/realms/robomart/protocol/openid-connect/certs");
        };
    }
}
