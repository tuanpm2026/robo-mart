package com.robomart.notification.unit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.robomart.notification.service.HealthAggregatorService;
import com.robomart.notification.web.ServiceHealthData;
import com.robomart.notification.web.SystemHealthResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class HealthAggregatorServiceTest {

    private HealthAggregatorService service;

    @BeforeEach
    void setUp() {
        service = new HealthAggregatorService();
        // Inject @Value fields with localhost defaults (no real HTTP calls in unit tests)
        ReflectionTestUtils.setField(service, "productUrl", "http://localhost:8081");
        ReflectionTestUtils.setField(service, "cartUrl", "http://localhost:8082");
        ReflectionTestUtils.setField(service, "orderUrl", "http://localhost:8083");
        ReflectionTestUtils.setField(service, "inventoryUrl", "http://localhost:8084");
        ReflectionTestUtils.setField(service, "paymentUrl", "http://localhost:8086");
        ReflectionTestUtils.setField(service, "notifUrl", "http://localhost:8087");
        ReflectionTestUtils.setField(service, "gatewayUrl", "http://localhost:8080");
        service.init();
        java.util.Map<String, org.springframework.web.client.RestClient> clients = new java.util.HashMap<>();
        for (String name : List.of("product-service", "cart-service", "order-service", "inventory-service",
                "payment-service", "notification-service", "api-gateway")) {
            var client = org.mockito.Mockito.mock(org.springframework.web.client.RestClient.class,
                    org.mockito.Mockito.RETURNS_DEEP_STUBS);
            org.mockito.Mockito.when(client.get().uri("/actuator/health").retrieve()
                    .body(com.robomart.notification.web.ActuatorHealthResponse.class))
                    .thenThrow(new org.springframework.web.client.ResourceAccessException("unreachable"));
            clients.put(name, client);
        }
        ReflectionTestUtils.setField(service, "clients", clients);
    }

    @Test
    void aggregateHealth_returns7ServiceEntries() {
        // Stubbed transport failures still produce all seven service entries.
        SystemHealthResponse response = service.aggregateHealth();

        assertThat(response).isNotNull();
        assertThat(response.services()).hasSize(7);
        assertThat(response.checkedAt()).isNotNull();
    }

    @Test
    void aggregateHealth_marksServicesDownWhenUnreachable() {
        SystemHealthResponse response = service.aggregateHealth();

        // Transport failures are ERROR, not an observed healthy/clean result.
        assertThat(response.services()).allMatch(s -> "ERROR".equals(s.actuatorStatus()));
    }

    @Test
    void aggregateHealth_downServicesHaveNullMetrics() {
        SystemHealthResponse response = service.aggregateHealth();

        for (ServiceHealthData data : response.services()) {
            assertThat(data.p95ResponseTimeMs()).isNull();
            assertThat(data.cpuPercent()).isNull();
            assertThat(data.memoryPercent()).isNull();
        }
    }

    @Test
    void aggregateHealth_downServicesHaveNullDbAndKafkaMetrics() {
        // DOWN services must have null dbPool and kafkaConsumerLag
        SystemHealthResponse response = service.aggregateHealth();

        for (ServiceHealthData data : response.services()) {
            assertThat(data.dbPoolActive()).isNull();
            assertThat(data.dbPoolMax()).isNull();
            assertThat(data.kafkaConsumerLag()).isNull();
        }
    }

    @Test
    void serviceConfigs_includeCorrectServiceNames() {
        SystemHealthResponse response = service.aggregateHealth();

        List<String> serviceNames = response.services().stream()
                .map(ServiceHealthData::service)
                .toList();

        assertThat(serviceNames).containsExactlyInAnyOrder(
                "product-service",
                "cart-service",
                "order-service",
                "inventory-service",
                "payment-service",
                "notification-service",
                "api-gateway"
        );
    }

    @Test
    void downServiceResponse_hasServiceNameAndTimestamp() {
        SystemHealthResponse response = service.aggregateHealth();

        for (ServiceHealthData data : response.services()) {
            assertThat(data.service()).isNotBlank();
            assertThat(data.displayName()).isNotBlank();
            assertThat(data.checkedAt()).isNotNull();
            assertThat(data.actuatorStatus()).isEqualTo("ERROR");
        }
    }
}
