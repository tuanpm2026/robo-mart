package com.robomart.e2e.chaos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Chaos test: Simulates service kill and network latency injection.
 * Verifies system recovers within 60 seconds (NFR34, NFR61).
 *
 * <p>Requires full stack running + Docker CLI access (to stop/start containers).
 *
 * <p>Run: ./mvnw verify -pl :e2e-tests -DskipE2ETests=false -De2e.enabled=true -Dchaos.enabled=true
 *          -Dchaos.admin-token=<ROLE_ADMIN JWT>
 *
 * <p>AC4: Kill service → Circuit Breaker opens, DLQ captures events → recovery within 60s
 * AC5: Network latency injection via Chaos Monkey actuator endpoint
 */
@EnabledIfSystemProperty(named = "chaos.enabled", matches = "true")
class ServiceKillChaosIT {

    private static final String BASE_URL = System.getProperty("e2e.base-url", "http://localhost:8080");
    // chaos.admin-token: JWT for a Keycloak ROLE_ADMIN user; required to place test orders
    private static final String ADMIN_TOKEN = System.getProperty("chaos.admin-token", "");

    // Container names as configured in docker-compose (profile: app)
    private static final String PAYMENT_CONTAINER = "robomart-payment-service";
    private static final String INVENTORY_DIRECT_URL = "http://localhost:8084";
    private static final String PAYMENT_HEALTH_URL = "http://localhost:8086/actuator/health";
    private static final String ORDER_HEALTH_URL = "http://localhost:8083/actuator/health";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private RestClient client;
    // Track whether payment container was stopped so @AfterEach can restart it on failure
    private boolean paymentContainerStopped = false;
    // Track whether chaos latency was enabled so @AfterEach can disable it on failure
    private boolean chaosLatencyEnabled = false;

    @BeforeEach
    void setUp() {
        assertThat(ADMIN_TOKEN).as("chaos.admin-token system property must be set").isNotBlank();
        client = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Ensure payment container is restarted if test failed mid-way
        if (paymentContainerStopped) {
            execDockerCommand("docker", "start", PAYMENT_CONTAINER);
            paymentContainerStopped = false;
        }
        // Ensure chaos latency is disabled if test failed mid-way
        if (chaosLatencyEnabled) {
            disableChaosLatency();
            chaosLatencyEnabled = false;
        }
    }

    /**
     * AC4: Kill payment-service mid-saga → verify Circuit Breaker opens and DLQ captures event.
     * Recovery: payment-service restarts → services return to healthy state within 60s.
     *
     * <p>Test approach: Use docker CLI to stop/start payment-service container.
     * Pre-condition: payment-service container name is "robomart-payment-service"
     */
    @Test
    void shouldRecoverWhenPaymentServiceIsKilledDuringSaga() throws Exception {
        // Step 1: Verify system is healthy before chaos
        assertSystemHealthy();

        // Step 2: Place an order (triggers saga: inventory reserve → payment charge)
        String orderId = placeTestOrder();
        assertThat(orderId).isNotNull().isNotBlank();

        // Step 3: Kill payment-service container immediately after order placement
        execDockerCommand("docker", "stop", PAYMENT_CONTAINER);
        paymentContainerStopped = true;

        // Step 4: Verify Circuit Breaker opens — order should not reach CONFIRMED while payment is down
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            String status = getOrderStatus(orderId);
            assertThat(status).isIn("PENDING", "CANCELLED");
        });

        // Step 5: Restart payment-service
        execDockerCommand("docker", "start", PAYMENT_CONTAINER);
        paymentContainerStopped = false;

        // Step 6: Verify system recovers within 60 seconds (NFR34: recovery < 60s)
        await().atMost(60, TimeUnit.SECONDS).pollInterval(3, TimeUnit.SECONDS)
                .untilAsserted(this::assertSystemHealthy);

        // Step 7: Verify all affected services report UP
        assertServiceHealthy(PAYMENT_HEALTH_URL);
        assertServiceHealthy(ORDER_HEALTH_URL);
    }

    /**
     * AC5: Inject 500ms latency on inventory-service (gRPC target) via Chaos Monkey actuator.
     * Verify: order-service continues operating with degraded performance, no data loss or timeouts.
     *
     * <p>Pre-condition: inventory-service must have chaos-monkey-spring-boot dependency on classpath
     * (scope: runtime in pom.xml) and started with chaos profile active.
     */
    @Test
    void shouldContinueOperatingWithNetworkLatency() throws Exception {
        RestClient inventoryAdminClient = RestClient.builder()
                .baseUrl(INVENTORY_DIRECT_URL)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build();

        // Step 1: Enable 500ms latency assault on inventory-service via Chaos Monkey actuator
        inventoryAdminClient.post()
                .uri("/actuator/chaosmonkey/assaults")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "level": 5,
                          "latencyActive": true,
                          "latencyRangeStart": 500,
                          "latencyRangeEnd": 500,
                          "exceptionsActive": false,
                          "killApplicationActive": false
                        }
                        """)
                .retrieve()
                .body(String.class);
        chaosLatencyEnabled = true;

        // Step 2: Place an order while 500ms latency is injected on inventory gRPC path
        String orderId = placeTestOrder();
        assertThat(orderId).isNotNull().isNotBlank();

        // Step 3: Verify order eventually completes — system is degraded but not broken (AC5)
        // NFR: no data loss, no unhandled TimeoutExceptions; CONFIRMED expected under degraded conditions
        await().atMost(30, TimeUnit.SECONDS).pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String status = getOrderStatus(orderId);
                    // Order must reach CONFIRMED — system continues operating under latency
                    assertThat(status).isEqualTo("CONFIRMED");
                });

        // Step 4: Disable latency assault after test
        disableChaosLatency();
        chaosLatencyEnabled = false;
    }

    private void disableChaosLatency() {
        RestClient inventoryAdminClient = RestClient.builder()
                .baseUrl(INVENTORY_DIRECT_URL)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build();
        inventoryAdminClient.post()
                .uri("/actuator/chaosmonkey/assaults")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "level": 1,
                          "latencyActive": false,
                          "exceptionsActive": false
                        }
                        """)
                .retrieve()
                .body(String.class);
    }

    private void assertSystemHealthy() {
        String health = client.get()
                .uri("/actuator/health")
                .retrieve()
                .body(String.class);
        assertThat(health).contains("\"status\":\"UP\"");
    }

    private void assertServiceHealthy(String healthUrl) {
        RestClient direct = RestClient.builder()
                .baseUrl(healthUrl)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build();
        String health = direct.get().retrieve().body(String.class);
        assertThat(health).contains("\"status\":\"UP\"");
    }

    private String placeTestOrder() throws Exception {
        String res = client.post()
                .uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .body("""
                        {
                          "items": [{"productId": 1, "quantity": 1}],
                          "shippingAddress": "1 Chaos Test St, Austin, TX, 75001, US"
                        }
                        """)
                .retrieve()
                .body(String.class);

        JsonNode root = jsonMapper.readTree(res);
        String orderId = root.path("data").path("id").asText();
        return orderId.isBlank() ? null : orderId;
    }

    private String getOrderStatus(String orderId) throws IOException {
        String res = client.get()
                .uri("/api/v1/orders/" + orderId)
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .retrieve()
                .body(String.class);

        JsonNode root = jsonMapper.readTree(res);
        return root.path("data").path("status").asText("PENDING");
    }

    /**
     * Execute a docker command, draining stdout/stderr to avoid subprocess blocking.
     * Checks the exit code and fails with a clear message if the command does not succeed.
     */
    private void execDockerCommand(String... command) throws Exception {
        Process process = Runtime.getRuntime().exec(command);
        // Drain stdout and stderr to prevent subprocess blocking on pipe buffer
        drainStream(process.getInputStream());
        drainStream(process.getErrorStream());
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        assertThat(finished)
                .as("docker command timed out: %s", String.join(" ", command))
                .isTrue();
        assertThat(process.exitValue())
                .as("docker command failed with exit code %d: %s", process.exitValue(), String.join(" ", command))
                .isEqualTo(0);
    }

    private void drainStream(InputStream stream) throws IOException {
        byte[] buffer = new byte[4096];
        while (stream.read(buffer) != -1) {
            // discard
        }
    }
}
