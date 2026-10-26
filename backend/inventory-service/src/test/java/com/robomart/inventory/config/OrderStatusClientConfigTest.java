package com.robomart.inventory.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import org.springframework.web.service.registry.HttpServiceGroupConfigurer;
import org.springframework.web.service.registry.HttpServiceGroupConfigurer.ClientCallback;

import com.robomart.inventory.client.OrderStatusClient;

class OrderStatusClientConfigTest {
    @Test
    @SuppressWarnings("unchecked")
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
            RestClient.Builder builder = RestClient.builder().baseUrl(url);
            HttpServiceGroupConfigurer.Groups<RestClient.Builder> groups = mock(
                    HttpServiceGroupConfigurer.Groups.class, RETURNS_SELF);
            doAnswer(invocation -> {
                ClientCallback<RestClient.Builder> callback = invocation.getArgument(0);
                callback.withClient(null, builder);
                return null;
            }).when(groups).forEachClient(any(ClientCallback.class));
            new OrderStatusClientConfig().orderStatusAuthentication("fixture-secret", url + "/token")
                    .configureGroups(groups);
            OrderStatusClient client = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build()))
                    .build().createClient(OrderStatusClient.class);
            assertThat(client.status("1").data()).isEqualTo("CANCELLED");
            assertThat(client.status("1").data()).isEqualTo("CANCELLED");
            assertThat(tokens.get()).isEqualTo(1);
            assertThat(reads.get()).isEqualTo(2);
            server.removeContext("/token");
            RestClient.Builder rejected = RestClient.builder().baseUrl(url);
            doAnswer(invocation -> {
                ClientCallback<RestClient.Builder> callback = invocation.getArgument(0);
                callback.withClient(null, rejected);
                return null;
            }).when(groups).forEachClient(any(ClientCallback.class));
            new OrderStatusClientConfig().orderStatusAuthentication("wrong", url + "/token").configureGroups(groups);
            OrderStatusClient failed = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rejected.build()))
                    .build().createClient(OrderStatusClient.class);
            assertThatThrownBy(() -> failed.status("1")).isInstanceOf(RuntimeException.class);
            assertThat(reads.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }
}
