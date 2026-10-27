package com.robomart.e2e;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Full-stack checkout and cancellation, including real persistence and asynchronous notifications. */
class FullOrderFlowE2EIT {

    @Test
    void shouldCompleteFullOrderFlowAndRefundCancellation() {
        var api = E2eSupport.api();
        var product = E2eSupport.availableProduct();
        String stockBefore = E2eSupport.query("postgres", "inventory_db",
                "SELECT available_quantity || '|' || reserved_quantity || '|' || total_quantity FROM inventory_items WHERE product_id=" + product.id());

        await().atMost(45, TimeUnit.SECONDS).untilAsserted(() -> {
            var search = api.get().uri(builder -> builder.path("/api/v1/products/search")
                            .queryParam("keyword", product.name()).queryParam("size", 100).build())
                    .retrieve().toEntity(String.class);
            assertThat(search.getStatusCode()).as("Product search status").isEqualTo(HttpStatus.OK);
            var items = E2eSupport.json(search.getBody()).path("data");
            assertThat(items.isArray()).isTrue();
            boolean found = false;
            for (var item : items) {
                found |= item.path("id").asLong() == product.id();
            }
            assertThat(found).as("Search must return selected stocked product %s", product.id()).isTrue();
        });

        var cart = api.post().uri("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("productId", product.id(), "productName", product.name(),
                        "price", product.price(), "quantity", 1))
                .retrieve().toEntity(String.class);
        assertThat(cart.getStatusCode().value()).isIn(200, 201);
        String cartId = cart.getHeaders().getFirst("X-Cart-Id");
        assertThat(cartId).as("Anonymous cart identifier").isNotBlank();
        assertThat(E2eSupport.json(cart.getBody()).path("data").path("cartId").asString()).isEqualTo(cartId);

        String token = E2eSupport.customerToken();
        var mergedCart = api.post().uri("/api/v1/cart/merge")
                .headers(headers -> headers.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("anonymousCartId", cartId)).retrieve().toEntity(String.class);
        assertThat(mergedCart.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(E2eSupport.json(mergedCart.getBody()).path("data").path("items").size()).isGreaterThanOrEqualTo(1);

        var forbiddenAdmin = api.get().uri("/api/v1/admin/orders")
                .headers(headers -> headers.setBearerAuth(token)).retrieve().toBodilessEntity();
        assertThat(forbiddenAdmin.getStatusCode()).as("Customer JWT cannot access admin orders").isEqualTo(HttpStatus.FORBIDDEN);

        var created = api.post().uri("/api/v1/orders").headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(E2eSupport.orderPayload(product, 1))
                .retrieve().toEntity(String.class);
        assertThat(created.getStatusCode()).as("Synchronous checkout status").isEqualTo(HttpStatus.CREATED);
        var order = E2eSupport.json(created.getBody()).path("data");
        long orderId = order.path("id").asLong();
        assertThat(orderId).isPositive();
        assertThat(order.path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(order.path("totalAmount").decimalValue()).isEqualByComparingTo(product.price());

        var detail = api.get().uri("/api/v1/orders/" + orderId)
                .headers(headers -> headers.setBearerAuth(token)).retrieve().toEntity(String.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(E2eSupport.json(detail.getBody()).path("data").path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(E2eSupport.query("postgres", "payment_db",
                "SELECT count(*) FROM payments WHERE order_id='" + orderId + "' AND status='COMPLETED'"))
                .isEqualTo("1");
        assertThat(E2eSupport.query("postgres", "inventory_db",
                "SELECT count(*) FROM stock_movements WHERE order_id='" + orderId + "' AND type='RESERVE' AND quantity=1"))
                .isEqualTo("1");

        String[] stockQuantities = stockBefore.split("[|]");
        String committedStock = (Integer.parseInt(stockQuantities[0]) - 1) + "|" + stockQuantities[1]
                + "|" + (Integer.parseInt(stockQuantities[2]) - 1);
        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(E2eSupport.query("postgres", "inventory_db",
                    "SELECT status FROM reservations WHERE order_id='" + orderId + "'")).isEqualTo("COMMITTED");
            assertThat(E2eSupport.query("postgres", "inventory_db",
                    "SELECT count(*) FROM stock_movements WHERE order_id='" + orderId + "' AND type='COMMIT' AND quantity=1"))
                    .isEqualTo("1");
            assertThat(E2eSupport.query("postgres", "inventory_db",
                    "SELECT available_quantity || '|' || reserved_quantity || '|' || total_quantity FROM inventory_items WHERE product_id=" + product.id()))
                    .as("Confirmed checkout commits exactly one unit").isEqualTo(committedStock);
        });


        await().atMost(60, TimeUnit.SECONDS).pollInterval(2, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(E2eSupport.query("postgres", "notification_db",
                        "SELECT count(*) FROM notification_log WHERE order_id='" + orderId
                                + "' AND notification_type='ORDER_CONFIRMED' AND status='SENT'"))
                        .as("Sent confirmation for newly created order %s", orderId).isEqualTo("1"));

        var cancelled = api.post().uri("/api/v1/orders/" + orderId + "/cancel")
                .headers(headers -> headers.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", "E2E cancellation and refund verification"))
                .retrieve().toBodilessEntity();
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(E2eSupport.query("postgres", "order_db",
                "SELECT status FROM orders WHERE id=" + orderId)).isEqualTo("CANCELLED");
        assertThat(E2eSupport.query("postgres", "payment_db",
                "SELECT count(*) FROM payments WHERE order_id='" + orderId + "' AND status='REFUNDED'"))
                .isEqualTo("1");
        assertThat(E2eSupport.query("postgres", "inventory_db",
                "SELECT count(*) FROM stock_movements WHERE order_id='" + orderId + "' AND type='RELEASE' AND quantity=1"))
                .isEqualTo("1");
        assertThat(E2eSupport.query("postgres", "inventory_db",
                "SELECT available_quantity || '|' || reserved_quantity || '|' || total_quantity FROM inventory_items WHERE product_id=" + product.id()))
                .as("Refunded cancellation restores original available/reserved/total stock").isEqualTo(stockBefore);

        String admin = E2eSupport.adminToken();
        var reconciliation = api.post().uri("/api/v1/admin/reconciliation/run")
                .headers(headers -> headers.setBearerAuth(admin)).retrieve().toEntity(String.class);
        assertThat(reconciliation.getStatusCode()).as("Real service-credential reconciliation").isEqualTo(HttpStatus.OK);
        var results = E2eSupport.json(reconciliation.getBody()).path("data");
        assertThat(results.size()).isEqualTo(2);
        for (var result : results) {
            assertThat(result.path("status").asString()).as("Reconciliation %s", result.path("type").asString())
                    .isEqualTo("OK");
        }
        var audits = api.get().uri("/api/v1/admin/audit-logs?page=0&size=10")
                .headers(headers -> headers.setBearerAuth(admin)).retrieve().toEntity(String.class);
        assertThat(audits.getStatusCode()).as("Real service-credential audit aggregation").isEqualTo(HttpStatus.OK);
        assertThat(E2eSupport.json(audits.getBody()).path("data").path("status").asString()).isEqualTo("OK");
    }
}
