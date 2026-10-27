package com.robomart.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.robomart.notification.client.ProductServiceClient;

class ProductServiceClientTest {
    @Test
    void boundedCacheEvictionRefetchesCurrentNameAndExpiryDoesNotCacheFailures() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, String> names = new ConcurrentHashMap<>();
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        Map<String, AtomicInteger> reads = new ConcurrentHashMap<>();
        server.createContext("/", exchange -> {
            String id = exchange.getRequestURI().getPath().replace("/api/v1/products/", "");
            reads.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
            if ("4".equals(id) && fail.get()) {
                byte[] failure = "{\"data\":null}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(503, failure.length);
                exchange.getResponseBody().write(failure);
                exchange.close();
                return;
            }
            String name = names.getOrDefault(id, "Robot " + id);
            byte[] body = ("{\"data\":{\"id\":" + id + ",\"name\":\"" + name + "\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var client = new ProductServiceClient(url, 1, Duration.ofMinutes(10));
            client.getProductName("1");
            client.getProductName("2");
            Cache<?, ?> cache = (Cache<?, ?>) ReflectionTestUtils.getField(client, "productNameCache");
            cache.cleanUp();
            assertThat(cache.estimatedSize()).isEqualTo(1);
            String evicted = cache.asMap().containsKey("1") ? "2" : "1";
            names.put(evicted, "New name");
            assertThat(client.getProductName(evicted)).isEqualTo("New name");
            assertThat(reads.get(evicted).get()).isEqualTo(2);
            assertThat(client.getProductName("4")).isEqualTo("Product #4");
            fail.set(false);
            assertThat(client.getProductName("4")).isEqualTo("Robot 4");
            assertThat(reads.get("4").get()).isEqualTo(2);
            var expiring = new ProductServiceClient(url, 1, Duration.ofMillis(30));
            assertThat(expiring.getProductName("3")).isEqualTo("Robot 3");
            names.put("3", "Changed");
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(expiring.getProductName("3")).isEqualTo("Changed"));
        } finally {
            server.stop(0);
        }
    }
}
