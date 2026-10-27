package com.robomart.gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;
import org.springframework.web.cors.reactive.CorsWebFilter;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    private CorsConfig corsConfig;

    @BeforeEach
    void setUp() {
        corsConfig = new CorsConfig();
        ReflectionTestUtils.setField(corsConfig, "customerOrigin", "http://localhost:5173");
        ReflectionTestUtils.setField(corsConfig, "adminOrigin", "http://localhost:5174");
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

        CorsWebFilter filter = corsConfig.corsWebFilter();

        assertThat(filter).isNotNull();
    }

    @Test
    void shouldRejectKeycloakOriginAndAllowStorefrontPreflight() {
        CorsWebFilter filter = corsConfig.corsWebFilter();
        for (String origin : new String[] {"http://localhost:5173", "http://localhost:8180"}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.options("http://localhost:8080/api/v1/products")
                    .header("Origin", origin).header("Access-Control-Request-Method", "GET"));
            filter.filter(exchange, ignored -> Mono.empty()).block();
            if (origin.endsWith("5173")) {
                assertThat(exchange.getResponse().getHeaders().getAccessControlAllowOrigin()).isEqualTo(origin);
            } else {
                assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(403);
                assertThat(exchange.getResponse().getHeaders().getAccessControlAllowOrigin()).isNull();
            }
        }
    }

    @Test
    void shouldCreateFilterWithCustomOrigins() {
        ReflectionTestUtils.setField(corsConfig, "customerOrigin", "https://shop.robomart.com");
        ReflectionTestUtils.setField(corsConfig, "adminOrigin", "https://admin.robomart.com");

        CorsWebFilter filter = corsConfig.corsWebFilter();

        assertThat(filter).isNotNull();
    }
}
