package com.robomart.notification.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.robomart.notification.service.HealthAggregatorService;

class HealthMetricErrorsTest {
    private final HealthAggregatorService service = new HealthAggregatorService();
    @Test
    void optionalMetric404IsAllowedButAuthenticationFailureMakesHealthError() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var metricStatus = new java.util.concurrent.atomic.AtomicInteger(404);
        server.createContext("/", exchange -> {
            boolean health = exchange.getRequestURI().getPath().equals("/actuator/health");
            byte[] body = (health ? "{\"status\":\"UP\"}" : "{}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(health ? 200 : metricStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            for (String name : List.of("productUrl", "cartUrl", "orderUrl", "inventoryUrl", "paymentUrl", "notifUrl", "gatewayUrl")) {
                ReflectionTestUtils.setField(service, name, "http://127.0.0.1:" + server.getAddress().getPort());
            }
            service.init();
            assertThat(service.aggregateHealth().services()).allMatch(row -> "UP".equals(row.actuatorStatus()));
            metricStatus.set(401);
            assertThat(service.aggregateHealth().services()).allMatch(row -> "ERROR".equals(row.actuatorStatus()));
        } finally {
            server.stop(0);
        }
    }

}
