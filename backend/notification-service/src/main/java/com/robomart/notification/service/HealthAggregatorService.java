package com.robomart.notification.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.robomart.notification.web.ActuatorHealthResponse;
import com.robomart.notification.web.ActuatorMetricResponse;
import com.robomart.notification.web.ServiceHealthData;
import com.robomart.notification.web.SystemHealthResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class HealthAggregatorService {

    private static final Logger log = LoggerFactory.getLogger(HealthAggregatorService.class);

    @Value("${notification.product-service.url:http://localhost:8081}")
    private String productUrl;

    @Value("${notification.cart-service.url:http://localhost:8082}")
    private String cartUrl;

    @Value("${notification.order-service.url:http://localhost:8083}")
    private String orderUrl;

    @Value("${notification.inventory-service.url:http://localhost:8084}")
    private String inventoryUrl;

    @Value("${notification.payment-service.url:http://localhost:8086}")
    private String paymentUrl;

    @Value("${notification.notification-self.url:http://localhost:8087}")
    private String notifUrl;

    @Value("${notification.api-gateway.url:http://localhost:8080}")
    private String gatewayUrl;

    private List<ServiceConfig> serviceConfigs;
    private Map<String, RestClient> clients;
    private volatile SystemHealthResponse latestHealth;

    private record ServiceConfig(
            String name,
            String displayName,
            String baseUrl,
            boolean hasDb,
            boolean hasKafka,
            String consumerGroup) {}

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(3));

        serviceConfigs = List.of(
                new ServiceConfig("product-service", "Product Service", productUrl, true, true, "product-service-product-index-group"),
                new ServiceConfig("cart-service", "Cart Service", cartUrl, false, false, null),
                new ServiceConfig("order-service", "Order Service", orderUrl, true, false, null),
                new ServiceConfig("inventory-service", "Inventory Service", inventoryUrl, true, false, null),
                new ServiceConfig("payment-service", "Payment Service", paymentUrl, true, false, null),
                new ServiceConfig("notification-service", "Notification Service", notifUrl, true, true, "notification-order-status-group"),
                new ServiceConfig("api-gateway", "API Gateway", gatewayUrl, false, false, null)
        );

        clients = serviceConfigs.stream().collect(Collectors.toMap(
                ServiceConfig::name,
                c -> RestClient.builder().baseUrl(c.baseUrl()).requestFactory(factory).build()
        ));
    }

    public SystemHealthResponse aggregateHealth() {
        List<ServiceHealthData> results = serviceConfigs.parallelStream()
                .map(this::checkServiceHealth)
                .toList();
        latestHealth = new SystemHealthResponse(results, Instant.now());
        return latestHealth;
    }

    public SystemHealthResponse getLatestHealth() {
        return latestHealth;
    }

    private ServiceHealthData checkServiceHealth(ServiceConfig config) {
        try {
            RestClient client = clients.get(config.name());

            // Step 1: actuator health status
            ActuatorHealthResponse healthResp = client.get().uri("/actuator/health")
                    .retrieve()
                    .body(ActuatorHealthResponse.class);
            String actuatorStatus = healthResp != null ? healthResp.status() : "DOWN";

            // Step 2: p95 response time (value in seconds → ms)
            Double p95Seconds = fetchMetric(client, "http.server.requests", "quantile:0.95");
            Long p95ResponseTimeMs = (p95Seconds != null && Double.isFinite(p95Seconds))
                    ? (long) (p95Seconds * 1000) : null;

            // Step 3: CPU percent
            Double cpuUsage = fetchMetric(client, "process.cpu.usage");
            Double cpuPercent = cpuUsage != null ? cpuUsage * 100.0 : null;

            // Step 4: Memory percent
            Double memUsed = fetchMetric(client, "jvm.memory.used", "area:heap");
            Double memMax = fetchMetric(client, "jvm.memory.max", "area:heap");
            Double memoryPercent = (memUsed != null && memMax != null && memMax > 0)
                    ? (memUsed / memMax) * 100.0
                    : null;

            // Step 5: DB pool (only for services with DB)
            Integer dbPoolActive = null;
            Integer dbPoolMax = null;
            if (config.hasDb()) {
                Double active = fetchMetric(client, "hikaricp.connections.active");
                Double max = fetchMetric(client, "hikaricp.connections.max");
                dbPoolActive = active != null ? active.intValue() : null;
                dbPoolMax = max != null ? max.intValue() : null;
            }

            // Step 6: Kafka consumer lag (only for services with Kafka consumers)
            Long kafkaConsumerLag = null;
            if (config.hasKafka()) {
                Double lag = fetchMetric(client, "kafka.consumer.records-lag-max");
                kafkaConsumerLag = lag != null ? lag.longValue() : null;
            }

            return new ServiceHealthData(
                    config.name(),
                    config.displayName(),
                    actuatorStatus,
                    p95ResponseTimeMs,
                    cpuPercent,
                    memoryPercent,
                    dbPoolActive,
                    dbPoolMax,
                    kafkaConsumerLag,
                    config.consumerGroup(),
                    Instant.now()
            );
        } catch (Exception e) {
            log.warn("Health check failed for {}: {}", config.name(), e.getMessage());
            return new ServiceHealthData(
                    config.name(),
                    config.displayName(),
                    "DOWN",
                    null, null, null, null, null, null,
                    config.consumerGroup(),
                    Instant.now()
            );
        }
    }

    private Double fetchMetric(RestClient client, String metricName, String... tags) {
        try {
            String uri = "/actuator/metrics/" + metricName;
            if (tags.length > 0) {
                uri += "?tag=" + String.join("&tag=", tags);
            }
            ActuatorMetricResponse resp = client.get().uri(uri)
                    .retrieve()
                    .body(ActuatorMetricResponse.class);
            Double value = resp != null ? resp.firstValue() : null;
            if (value == null) {
                log.debug("Metric {} not available (no data or tag not matched)", metricName);
            }
            return value;
        } catch (Exception e) {
            log.debug("Metric {} fetch failed: {}", metricName, e.getMessage());
            return null;
        }
    }
}
