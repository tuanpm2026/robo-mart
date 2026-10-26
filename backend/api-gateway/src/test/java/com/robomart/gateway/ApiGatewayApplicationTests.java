package com.robomart.gateway;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
            "management.health.redis.enabled=false",
            "management.endpoint.health.group.readiness.include=readinessState"
        }
)
class ApiGatewayApplicationTests {

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    WebTestClient webTestClient;

    WebTestClient managementClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        managementClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + managementPort)
                .build();
    }

    @Test
    void contextLoads() {
    }

    @Test
    void shouldBindGatewaySettingsUnderServerWebfluxPrefix(@Autowired HttpClientProperties httpClient,
                                                          @Autowired GatewayProperties gateway) {
        assertThat(httpClient.getConnectTimeout()).isEqualTo(2000);
        assertThat(httpClient.getResponseTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(gateway.getDefaultFilters())
                .extracting(FilterDefinition::getName)
                .containsExactly("DedupeResponseHeader");
    }

    @Test
    void shouldServeActuatorOnSeparateManagementPort() {
        assertThat(managementPort).isPositive().isNotEqualTo(port);
    }

    @Test
    void shouldReturnHealthUpOnManagementPort() {
        managementClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void shouldServeProbesOnManagementPort() {
        managementClient.get().uri("/actuator/health/liveness")
                .exchange()
                .expectStatus().isOk();
        managementClient.get().uri("/actuator/health/readiness")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void shouldAllowPrometheusScrapeWithoutTokenOnManagementPort() {
        managementClient.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void shouldNotExposeGatewayRouteEndpoint() {
        managementClient.get().uri("/actuator")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$._links.prometheus").exists()
                .jsonPath("$._links.gateway").doesNotExist();
        managementClient.post().uri("/actuator/gateway/routes/hijack")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"uri\":\"http://attacker.example\",\"predicates\":[]}")
                .exchange()
                .expectStatus().value(status -> assertThat(status).isIn(401, 403, 404));
    }

    @Test
    void shouldNotServeActuatorOnPublicPort() {
        webTestClient.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.post().uri("/actuator/gateway/routes/hijack")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void shouldIncludeCorsHeadersOnPreflightRequest() {
        webTestClient.options().uri("/api/v1/products")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173")
                .expectHeader().exists("Access-Control-Allow-Methods");
    }
}
