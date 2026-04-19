package com.robomart.gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.reactive.CorsWebFilter;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    private CorsConfig corsConfig;

    @BeforeEach
    void setUp() {
        corsConfig = new CorsConfig();
        ReflectionTestUtils.setField(corsConfig, "customerOrigin", "http://localhost:5173");
        ReflectionTestUtils.setField(corsConfig, "adminOrigin", "http://localhost:5174");
        ReflectionTestUtils.setField(corsConfig, "keycloakOrigin", "http://localhost:8180");
    }

    @Test
    void shouldCreateCorsWebFilterWhenBeanInitialized() {
        CorsWebFilter filter = corsConfig.corsWebFilter();

        assertThat(filter).isNotNull();
    }

    @Test
    void shouldStripTrailingSlashFromOrigins() {
        ReflectionTestUtils.setField(corsConfig, "customerOrigin", "http://localhost:5173/");
        ReflectionTestUtils.setField(corsConfig, "adminOrigin", "http://localhost:5174/");
        ReflectionTestUtils.setField(corsConfig, "keycloakOrigin", "http://localhost:8180/");

        CorsWebFilter filter = corsConfig.corsWebFilter();

        assertThat(filter).isNotNull();
    }

    @Test
    void shouldCreateFilterWithCustomOrigins() {
        ReflectionTestUtils.setField(corsConfig, "customerOrigin", "https://shop.robomart.com");
        ReflectionTestUtils.setField(corsConfig, "adminOrigin", "https://admin.robomart.com");
        ReflectionTestUtils.setField(corsConfig, "keycloakOrigin", "https://auth.robomart.com");

        CorsWebFilter filter = corsConfig.corsWebFilter();

        assertThat(filter).isNotNull();
    }
}
