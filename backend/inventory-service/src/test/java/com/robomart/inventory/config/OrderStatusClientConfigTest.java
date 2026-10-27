package com.robomart.inventory.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import java.util.Map;

import com.robomart.inventory.client.OrderStatusClient;

class OrderStatusClientConfigTest {
    @Test
    void usesClientCredentialsCachesTokenAndFailsClosedWhenAuthorizationFails() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger tokens = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        server.createContext("/token", exchange -> {
            tokens.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                    .startsWith("Basic ");
            assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .contains("grant_type=client_credentials");
            byte[] body = "{\"access_token\":\"test-token\",\"token_type\":\"Bearer\",\"expires_in\":300}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/v1/internal/inventory/orders/1/status", exchange -> {
            reads.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-token");
            byte[] body = "{\"data\":\"CANCELLED\",\"traceId\":null}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            try (var context = context(url, "fixture-secret")) {
                OrderStatusClient client = context.getBean(OrderStatusClient.class);
                assertThat(client.status("1").data()).isEqualTo("CANCELLED");
                assertThat(client.status("1").data()).isEqualTo("CANCELLED");
                assertThat(tokens.get()).isEqualTo(1);
                assertThat(reads.get()).isEqualTo(2);
            }
            server.removeContext("/token");
            try (var context = context(url, "wrong")) {
                OrderStatusClient failed = context.getBean(OrderStatusClient.class);
                assertThatThrownBy(() -> failed.status("1")).isInstanceOf(RuntimeException.class);
                assertThat(reads.get()).isEqualTo(2);
            }
        } finally {
            server.stop(0);
        }
    }
    private AnnotationConfigApplicationContext context(String url, String secret) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                "INVENTORY_SERVICE_CLIENT_SECRET", secret, "INVENTORY_SERVICE_TOKEN_URI", url + "/token")));
        context.registerBean("baseUrl", RestClientHttpServiceGroupConfigurer.class,
                () -> groups -> groups.forEachClient((group, builder) -> builder.baseUrl(url)));
        context.register(OrderStatusClientConfig.class);
        context.refresh();
        return context;
    }
}
