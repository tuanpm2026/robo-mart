package com.robomart.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Exercises Product -> Inventory bootstrap and Inventory -> Product/index/cache snapshots. */
class InventoryCatalogE2EIT {
    @Test
    void newProductRestockCheckoutAndCancellationSynchronizeTheCatalog() {
        var api = E2eSupport.api();
        String admin = E2eSupport.adminToken();
        String customer = E2eSupport.customerToken();
        String name = "Stock" + UUID.randomUUID().toString().replace("-", "");
        var created =
                api.post()
                        .uri("/api/v1/admin/products")
                        .headers(headers -> headers.setBearerAuth(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(
                                Map.of(
                                        "name",
                                        name,
                                        "categoryId",
                                        1,
                                        "price",
                                        "19.95",
                                        "sku",
                                        name,
                                        "brand",
                                        "E2E"))
                        .retrieve()
                        .toEntity(String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        long id = E2eSupport.json(created.getBody()).path("data").path("id").asLong();
        assertThat(id).isPositive();
        long orderId = 0;
        try {
            await().atMost(Duration.ofSeconds(45))
                    .untilAsserted(
                            () ->
                                    assertThat(
                                                    E2eSupport.query(
                                                            "postgres",
                                                            "inventory_db",
                                                            "SELECT count(*) FROM inventory_items"
                                                                + " WHERE product_id="
                                                                    + id
                                                                    + " AND available_quantity=0"
                                                                    + " AND reserved_quantity=0 AND"
                                                                    + " total_quantity=0"))
                                            .isEqualTo("1"));
            assertCatalog(id, name, 0, customer);
            var restocked = api.put().uri("/api/v1/admin/inventory/" + id + "/restock")
                    .headers(headers -> headers.setBearerAuth(admin)).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("quantity", 3, "reason", "Catalog sync E2E fixture"))
                    .retrieve().toEntity(String.class);
            assertThat(restocked.getStatusCode().value()).isEqualTo(200);
            assertCatalog(id, name, 3, customer);

            var product = new E2eSupport.Product(id, name, new BigDecimal("19.95"));
            var checkout =
                    api.post()
                            .uri("/api/v1/orders")
                            .headers(headers -> headers.setBearerAuth(customer))
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(E2eSupport.orderPayload(product, 1))
                            .retrieve()
                            .toEntity(String.class);
            assertThat(checkout.getStatusCode().value()).isEqualTo(202);
            var order = E2eSupport.json(checkout.getBody()).path("data");
            orderId = order.path("id").asLong();
            assertThat(orderId).isPositive();
            assertThat(order.path("status").asString()).isEqualTo("PENDING");
            E2eSupport.awaitStatus(orderId, "CONFIRMED");
            assertCatalog(id, name, 2, customer);

            var cancelled =
                    api.post()
                            .uri("/api/v1/orders/" + orderId + "/cancel")
                            .headers(headers -> headers.setBearerAuth(customer))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("reason", "Catalog sync E2E cleanup"))
                            .retrieve()
                            .toBodilessEntity();
            assertThat(cancelled.getStatusCode().value()).isEqualTo(202);
            E2eSupport.awaitStatus(orderId, "CANCELLED");
            orderId = 0;
            assertCatalog(id, name, 3, customer);
            var anonymous =
                    api.get().uri("/api/v1/products/" + id).retrieve().toEntity(String.class);
            assertThat(anonymous.getStatusCode().value()).isEqualTo(200);
            assertThat(
                            E2eSupport.json(anonymous.getBody())
                                    .path("data")
                                    .path("stockQuantity")
                                    .asInt())
                    .isEqualTo(3);
        } finally {
            if (orderId > 0) {
                api.post()
                        .uri("/api/v1/orders/" + orderId + "/cancel")
                        .headers(headers -> headers.setBearerAuth(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("reason", "Failed catalog sync E2E cleanup"))
                        .retrieve()
                        .toBodilessEntity();
            }
            api.delete()
                    .uri("/api/v1/admin/products/" + id)
                    .headers(headers -> headers.setBearerAuth(admin))
                    .retrieve()
                    .toBodilessEntity();
        }
    }

    private void assertCatalog(long id, String name, int stock, String customer) {
        await().pollInterval(Duration.ofSeconds(1))
                .atMost(Duration.ofSeconds(45))
                .untilAsserted(
                        () -> {
                            var detail =
                                    E2eSupport.api()
                                            .get()
                                            .uri("/api/v1/products/" + id)
                                            .headers(headers -> headers.setBearerAuth(customer))
                                            .retrieve()
                                            .toEntity(String.class);
                            assertThat(detail.getStatusCode().value()).isEqualTo(200);
                            assertThat(
                                            E2eSupport.json(detail.getBody())
                                                    .path("data")
                                                    .path("stockQuantity")
                                                    .asInt())
                                    .isEqualTo(stock);
                            var found =
                                    E2eSupport.api()
                                            .get()
                                            .uri(
                                                    builder ->
                                                            builder.path("/api/v1/products/search")
                                                                    .queryParam("keyword", name)
                                                                    .build())
                                            .headers(headers -> headers.setBearerAuth(customer))
                                            .retrieve()
                                            .toEntity(String.class);
                            assertThat(found.getStatusCode().value()).isEqualTo(200);
                            var results = E2eSupport.json(found.getBody()).path("data");
                            assertThat(results.size()).isEqualTo(1);
                            assertThat(results.get(0).path("id").asLong()).isEqualTo(id);
                            assertThat(results.get(0).path("stockQuantity").asInt())
                                    .isEqualTo(stock);
                        });
    }
}
