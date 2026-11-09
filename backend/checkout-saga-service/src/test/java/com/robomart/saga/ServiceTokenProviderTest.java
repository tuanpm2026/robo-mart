package com.robomart.saga;

import static org.assertj.core.api.Assertions.*;

import com.robomart.saga.transport.ServiceTokenProvider;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

class ServiceTokenProviderTest {
    @Test
    void obtainsClientCredentialsTokenAndCachesIt() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/token",
                exchange -> {
                    assertThat(
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8))
                            .contains("client_id=checkout-saga-service", "client_secret=test-only");
                    requests.incrementAndGet();
                    byte[] bytes =
                            "{\"access_token\":\"signed-token\",\"expires_in\":300}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            ServiceTokenProvider tokens =
                    new ServiceTokenProvider(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/token",
                            "test-only");
            assertThat(tokens.get()).isEqualTo("signed-token");
            assertThat(tokens.get()).isEqualTo("signed-token");
            assertThat(requests.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }
}
