package com.robomart.test;

import dasniko.testcontainers.keycloak.KeycloakContainer;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

@TestConfiguration(proxyBeanMethods = false)
public class KeycloakContainerConfig {

    private static final KeycloakContainer KEYCLOAK =
            new KeycloakContainer("quay.io/keycloak/keycloak:26.1.4")
                    .withRealmImportFile("test-realm.json")
                    .withReuse(true);

    static {
        KEYCLOAK.start();
    }

    @Bean
    public KeycloakContainer keycloakContainer() {
        return KEYCLOAK;
    }

    @Bean
    DynamicPropertyRegistrar keycloakProperties(KeycloakContainer keycloak) {
        return registry -> {
            registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> keycloak.getAuthServerUrl() + "/realms/robomart");
        };
    }
}
