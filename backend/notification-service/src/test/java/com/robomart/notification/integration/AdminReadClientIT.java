package com.robomart.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.robomart.notification.client.InventoryAdminReadClient;
import com.robomart.notification.client.OrderAdminReadClient;
import com.robomart.notification.client.OrderServiceClient;
import com.robomart.notification.client.PaymentAdminReadClient;
import com.robomart.notification.client.ProductAdminReadClient;
import com.robomart.notification.service.ReconciliationService;
import com.robomart.test.PostgresContainerConfig;
import com.robomart.test.security.TestJwt;
import com.robomart.test.security.TestJwtDecoderConfig;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.listener.auto-startup=false", "notification.scheduling.enabled=false"})
@ActiveProfiles("test")
@Import(PostgresContainerConfig.class)
class AdminReadClientIT {
    private static final AtomicInteger TOKENS = new AtomicInteger();
    private static final AtomicInteger AUTH_FAILURES = new AtomicInteger();
    private static final AtomicInteger INVENTORY_STATUS = new AtomicInteger(200);
    private static final AtomicInteger AUDIT_STATUS = new AtomicInteger(200);
    private static final AtomicBoolean SLOW = new AtomicBoolean();
    private static final List<Integer> PAYMENT_PAGES = new CopyOnWriteArrayList<>();
    private static final List<String> ACTORS = new CopyOnWriteArrayList<>();
    private static final java.util.concurrent.ExecutorService REQUESTS = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer DOWNSTREAM = start();
    @Autowired private OrderAdminReadClient orders;
    @Autowired private InventoryAdminReadClient inventory;
    @Autowired private PaymentAdminReadClient payments;
    @Autowired private ProductAdminReadClient products;
    @Autowired private OrderServiceClient details;
    @Autowired private ReconciliationService reconciliation;
    @LocalServerPort private int port;
    @MockitoBean private JwtDecoder jwtDecoder;

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(REQUESTS);
            server.createContext("/", AdminReadClientIT::respond);
            server.start();
            return server;
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry properties) {
        String base = "http://127.0.0.1:" + DOWNSTREAM.getAddress().getPort();
        for (String service : List.of("order", "inventory", "payment", "product")) {
            properties.add("notification." + service + "-service.url", () -> base);
        }
        properties.add("spring.security.oauth2.client.registration.notification.client-id", () -> "notification-service");
        properties.add("spring.security.oauth2.client.registration.notification.client-secret", () -> "fixture-secret");
        properties.add("spring.security.oauth2.client.registration.notification.authorization-grant-type", () -> "client_credentials");
        properties.add("spring.security.oauth2.client.registration.notification.provider", () -> "keycloak");
        properties.add("spring.security.oauth2.client.provider.keycloak.token-uri", () -> base + "/token");
    }

    private static String param(HttpExchange request, String name, String fallback) {
        String query = request.getRequestURI().getRawQuery();
        if (query != null) for (String entry : query.split("&")) {
            String[] pair = entry.split("=", 2);
            if (pair[0].equals(name)) return URLDecoder.decode(pair.length == 2 ? pair[1] : "", StandardCharsets.UTF_8);
        }
        return fallback;
    }

    private static void respond(HttpExchange request) throws IOException {
        String path = request.getRequestURI().getPath();
        int status = 200;
        String body;
        if (path.equals("/token")) {
            TOKENS.incrementAndGet();
            String form = new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!form.contains("grant_type=client_credentials")) throw new IOException("Wrong grant");
            body = "{\"access_token\":\"ops-token\",\"token_type\":\"Bearer\",\"expires_in\":300}";
        } else if (!"Bearer ops-token".equals(request.getRequestHeaders().getFirst("Authorization"))) {
            AUTH_FAILURES.incrementAndGet(); status = 401; body = "{}";
        } else if (path.endsWith("/audit-logs")) {
            status = AUDIT_STATUS.get();
            ACTORS.add(param(request, "actor", ""));
            body = "{\"data\":[],\"pagination\":{\"page\":" + param(request, "page", "0")
                    + ",\"size\":" + param(request, "size", "100") + ",\"totalElements\":0,\"totalPages\":0}}";
        } else if (path.equals("/api/v1/admin/orders/42")) {
            body = "{\"data\":{\"id\":42,\"userId\":\"customer\",\"items\":[]}}";
        } else if (path.equals("/api/v1/admin/orders/reconciliation-summary")) {
            body = "{\"data\":[{\"orderId\":\"1\",\"status\":\"CONFIRMED\",\"items\":[]},"
                    + "{\"orderId\":\"2\",\"status\":\"CONFIRMED\",\"items\":[]}]}";
        } else if (path.equals("/api/v1/admin/inventory/reconciliation-summary")) {
            if (SLOW.get()) {
                try { Thread.sleep(3000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); request.close(); return; }
            }
            status = INVENTORY_STATUS.get();
            body = "{\"data\":{\"items\":[]}}";
        } else if (path.equals("/api/v1/admin/payments/reconciliation-summary")) {
            int page = Integer.parseInt(param(request, "page", "0"));
            PAYMENT_PAGES.add(page);
            body = "{\"data\":{\"payments\":[{\"orderId\":\"" + (page + 1)
                    + "\",\"status\":\"COMPLETED\",\"amount\":\"10.00\"}],\"page\":" + page
                    + ",\"size\":100,\"hasNext\":" + (page == 0) + "}}";
        } else { status = 404; body = "{}"; }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        request.getResponseHeaders().set("Content-Type", "application/json");
        request.sendResponseHeaders(status, bytes.length);
        request.getResponseBody().write(bytes);
        request.close();
    }

    @BeforeEach
    void reset() {
        INVENTORY_STATUS.set(200); AUDIT_STATUS.set(200); SLOW.set(false); PAYMENT_PAGES.clear(); ACTORS.clear();
        org.mockito.Mockito.when(jwtDecoder.decode(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> new TestJwtDecoderConfig().jwtDecoder().decode(call.getArgument(0)));
    }
    @AfterAll
    static void stop() { DOWNSTREAM.stop(0); REQUESTS.shutdownNow(); }

    @Test
    void importedClientsAuthenticateEveryAdminReadAndReuseOneToken() {
        assertThat(orders.reconciliation().data()).hasSize(2);
        assertThat(inventory.reconciliation().data().items()).isEmpty();
        assertThat(payments.reconciliation(0, 100).data().hasNext()).isTrue();
        assertThat(details.getOrderDetail("42").id()).isEqualTo(42L);
        Map<String, String> filters = Map.of("actor", "Nguyễn A & B", "page", "0", "size", "100");
        assertThat(orders.audits(filters).data()).isEmpty();
        assertThat(inventory.audits(filters).data()).isEmpty();
        assertThat(payments.audits(filters).data()).isEmpty();
        assertThat(products.audits(filters).data()).isEmpty();
        assertThat(ACTORS).containsOnly("Nguyễn A & B");
        assertThat(TOKENS.get()).isEqualTo(1);
        assertThat(AUTH_FAILURES.get()).isZero();
    }

    @Test
    void actualPaymentClientPagingFindsBothSettlements() {
        assertThat(reconciliation.runPaymentReconciliation().status()).isEqualTo("OK");
        assertThat(PAYMENT_PAGES).containsExactly(0, 1);
    }

    @Test
    void downstreamUnauthorizedIsExplicitErrorAndExecutionApiReturns502() {
        INVENTORY_STATUS.set(401);
        assertThat(reconciliation.runInventoryReconciliation().status()).isEqualTo("ERROR");
        var client = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", TestJwt.adminBearer())
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { }).build();
        var response = client.post().uri("/api/v1/admin/reconciliation/run").retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody()).contains("\"status\":\"ERROR\"");
    }

    @Test
    void auditFailureIsVisibleInHttpResponse() {
        AUDIT_STATUS.set(401);
        var client = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", TestJwt.adminBearer())
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { }).build();
        var response = client.get().uri("/api/v1/admin/audit-logs?size=100").retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody()).contains("\"status\":\"ERROR\"");
    }

    @Test
    void groupedClientReadTimeoutIsBounded() {
        SLOW.set(true);
        long start = System.nanoTime();
        assertThatThrownBy(inventory::reconciliation).isInstanceOf(RestClientException.class);
        assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(5000);
    }
}
