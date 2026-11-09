package com.robomart.e2e;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** A shipped order is completed, never rolled back by a losing cancellation. */
class SagaShippingRaceE2EIT {
    @Test
    void concurrentHttpShippingAndCancellationHaveOneFinancialOutcome() throws Exception {
        var api = E2eSupport.api();
        String customer = E2eSupport.customerToken(), admin = E2eSupport.adminToken();
        var product = E2eSupport.availableProduct();
        var response =
                api.post()
                        .uri("/api/v1/orders")
                        .headers(h -> h.setBearerAuth(customer))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(E2eSupport.orderPayload(product, 1))
                        .retrieve()
                        .toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        long id = E2eSupport.json(response.getBody()).path("data").path("id").asLong();
        E2eSupport.awaitStatus(id, "CONFIRMED");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var shipping =
                    pool.submit(
                            () -> {
                                start.await();
                                return api.put()
                                        .uri("/api/v1/admin/orders/" + id + "/status")
                                        .headers(h -> h.setBearerAuth(admin))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .body(Map.of("status", "SHIPPED"))
                                        .retrieve()
                                        .toBodilessEntity()
                                        .getStatusCode()
                                        .value();
                            });
            var cancelling =
                    pool.submit(
                            () -> {
                                start.await();
                                return api.post()
                                        .uri("/api/v1/orders/" + id + "/cancel")
                                        .headers(h -> h.setBearerAuth(customer))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .body(Map.of("reason", "Race shipping"))
                                        .retrieve()
                                        .toBodilessEntity()
                                        .getStatusCode()
                                        .value();
                            });
            start.countDown();
            int shipped = shipping.get(15, TimeUnit.SECONDS),
                    cancelled = cancelling.get(15, TimeUnit.SECONDS);
            assertThat((shipped == 200) ^ (cancelled == 200 || cancelled == 202)).isTrue();
            if (shipped == 200) {
                assertThat(cancelled).isEqualTo(409);
                assertThat(
                                E2eSupport.query(
                                        "postgres",
                                        "payment_db",
                                        "SELECT status || '|' || refunded_amount FROM payments"
                                            + " WHERE order_id='"
                                                + id
                                                + "'"))
                        .isEqualTo("COMPLETED|0.00");
                // Finish the accepted delivery. Business rules correctly prohibit refund/stock
                // return after shipping.
                assertThat(
                                api.put()
                                        .uri("/api/v1/admin/orders/" + id + "/status")
                                        .headers(h -> h.setBearerAuth(admin))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .body(Map.of("status", "DELIVERED"))
                                        .retrieve()
                                        .toBodilessEntity()
                                        .getStatusCode()
                                        .value())
                        .isEqualTo(200);
                E2eSupport.awaitStatus(id, "DELIVERED");
            } else {
                assertThat(shipped).isIn(400, 409);
                E2eSupport.awaitStatus(id, "CANCELLED");
                assertThat(
                                E2eSupport.query(
                                        "postgres",
                                        "payment_db",
                                        "SELECT status FROM payments WHERE order_id='" + id + "'"))
                        .isEqualTo("REFUNDED");
            }
        }
        E2eSupport.recordSagaEvidence("E06", id, "concurrent-http-shipping-and-cancellation");
    }
}
