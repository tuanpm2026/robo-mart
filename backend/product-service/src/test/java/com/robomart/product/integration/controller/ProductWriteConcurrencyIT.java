package com.robomart.product.integration.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.robomart.test.ProductIntegrationTest;
import com.robomart.test.security.TestJwt;
import com.robomart.test.security.TestJwtDecoderConfig;

@ProductIntegrationTest
@Import(TestJwtDecoderConfig.class)
class ProductWriteConcurrencyIT {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    private final JsonMapper mapper = JsonMapper.builder().build();

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", TestJwt.adminBearer())
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { }).build();
    }

    private Map<String, Object> createBody(String sku) {
        return Map.of("name", "Concurrency product", "sku", sku, "categoryId", 1, "price", new BigDecimal("19.95"));
    }

    private JsonNode create() {
        var response = client().post().uri("/api/v1/admin/products").contentType(MediaType.APPLICATION_JSON)
                .body(createBody(UUID.randomUUID().toString())).retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return mapper.readTree(response.getBody()).path("data");
    }

    private Map<String, Object> edit(String name, int version) {
        return Map.of("name", name, "categoryId", 1, "price", new BigDecimal("21.25"), "version", version);
    }

    @Test
    void returnedVersionCanBeUsedForTheNextEditAndStaleEditCannotChangeRatingOrOutbox() {
        var created = create();
        long id = created.path("id").asLong();
        int version = created.path("version").asInt(-1);
        assertThat(version).isGreaterThanOrEqualTo(0);
        jdbc.update("UPDATE products SET rating=4.25 WHERE id=?", id);
        var response = client().put().uri("/api/v1/admin/products/" + id).contentType(MediaType.APPLICATION_JSON)
                .body(edit("New name", version)).retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(mapper.readTree(response.getBody()).path("data").path("version").asInt()).isEqualTo(version + 1);
        Integer events = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=?", Integer.class, String.valueOf(id));
        var stale = client().put().uri("/api/v1/admin/products/" + id).contentType(MediaType.APPLICATION_JSON)
                .body(edit("Stale overwrite", version)).retrieve().toEntity(String.class);
        assertThat(stale.getStatusCode().value()).isEqualTo(409);
        assertThat(stale.getBody()).contains("PRODUCT_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT name FROM products WHERE id=?", String.class, id)).isEqualTo("New name");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=?", Integer.class, String.valueOf(id)))
                .isEqualTo(events);
        assertThat(jdbc.queryForObject("SELECT payload->>'rating' FROM outbox_events WHERE aggregate_id=? "
                + "AND event_type='PRODUCT_UPDATED' ORDER BY id DESC LIMIT 1", String.class, String.valueOf(id))).isEqualTo("4.25");
    }

    @Test
    void parallelEditorsUsingOneVersionCannotBothSucceed() throws Exception {
        var product = create();
        long id = product.path("id").asLong();
        int version = product.path("version").asInt();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var requests = List.of("Editor A", "Editor B").stream().map(name -> executor.submit(() -> {
                start.await();
                return client().put().uri("/api/v1/admin/products/" + id).contentType(MediaType.APPLICATION_JSON)
                        .body(edit(name, version)).retrieve().toBodilessEntity().getStatusCode().value();
            })).toList();
            start.countDown();
            assertThat(List.of(requests.get(0).get(15, TimeUnit.SECONDS), requests.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
    }

    @Test
    void concurrentSkuCreationLeavesOneProductAndOneCreationEvent() throws Exception {
        String sku = UUID.randomUUID().toString();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var requests = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                start.await();
                return client().post().uri("/api/v1/admin/products").contentType(MediaType.APPLICATION_JSON)
                        .body(createBody(sku)).retrieve().toBodilessEntity().getStatusCode().value();
            })).toList();
            start.countDown();
            assertThat(List.of(requests.get(0).get(15, TimeUnit.SECONDS), requests.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE sku=?", Integer.class, sku)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type='PRODUCT_CREATED' "
                + "AND payload->>'sku'=?", Integer.class, sku)).isEqualTo(1);
    }
}
