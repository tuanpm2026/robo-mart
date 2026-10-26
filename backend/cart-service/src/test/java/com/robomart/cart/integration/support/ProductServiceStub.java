package com.robomart.cart.integration.support;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Minimal stand-in for product-service's {@code GET /api/v1/products/{id}} used by cart ITs, so the
 * real {@code @HttpExchange} client, HTTP service group configuration and timeouts are exercised.
 * Unknown products return 404 like product-service does.
 */
public final class ProductServiceStub {

    private static final String PATH_PREFIX = "/api/v1/products/";

    private final HttpServer server;
    private final Map<Long, Product> catalog = new ConcurrentHashMap<>();

    private record Product(String name, BigDecimal price) {
    }

    private ProductServiceStub(HttpServer server) {
        this.server = server;
    }

    public static ProductServiceStub start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ProductServiceStub stub = new ProductServiceStub(server);
            server.createContext("/", stub::handle);
            server.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public ProductServiceStub product(long id, String name, String price) {
        catalog.put(id, new Product(name, new BigDecimal(price)));
        return this;
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        Product product = null;
        long id = -1;
        if (path.startsWith(PATH_PREFIX)) {
            try {
                id = Long.parseLong(path.substring(PATH_PREFIX.length()));
                product = catalog.get(id);
            } catch (NumberFormatException ignored) {
                product = null;
            }
        }
        String body = product == null
                ? "{\"error\":{\"code\":\"PRODUCT_NOT_FOUND\",\"message\":\"not found\"},\"traceId\":\"stub\"}"
                : String.format("{\"data\":{\"id\":%d,\"sku\":\"SKU-%d\",\"name\":\"%s\",\"price\":%s,"
                        + "\"stockQuantity\":10,\"images\":[]},\"traceId\":\"stub\"}",
                        id, id, product.name(), product.price().toPlainString());
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(product == null ? 404 : 200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
