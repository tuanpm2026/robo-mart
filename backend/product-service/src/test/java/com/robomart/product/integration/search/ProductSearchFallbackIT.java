package com.robomart.product.integration.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.net.ConnectException;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import com.robomart.product.document.ProductDocument;
import com.robomart.test.ProductIntegrationTest;
import tools.jackson.databind.json.JsonMapper;

@ProductIntegrationTest
class ProductSearchFallbackIT {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private ElasticsearchOperations elasticsearch;

    @Test
    void httpFallbackMapsLazyImagesInsideReadTransactionWithOsivDisabled() {
        String keyword = "fallback-" + UUID.randomUUID();
        long category = jdbc.queryForObject("INSERT INTO categories(name) VALUES (?) RETURNING id",
                Long.class, keyword);
        long product = jdbc.queryForObject("INSERT INTO products(sku,name,price,category_id,active) "
                + "VALUES (?,?,12.34,?,true) RETURNING id", Long.class, keyword, keyword, category);
        String image = "https://example.invalid/fallback.jpg";
        try {
            jdbc.update("INSERT INTO product_images(product_id,image_url,display_order) VALUES (?,?,0)", product, image);
            doAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                        .as("No database transaction held across Elasticsearch I/O").isFalse();
                throw new DataAccessResourceFailureException("search unavailable", new ConnectException("refused"));
            }).when(elasticsearch).search(any(NativeQuery.class), eq(ProductDocument.class));

            var response = RestClient.create("http://localhost:" + port).get()
                    .uri(builder -> builder.path("/api/v1/products/search").queryParam("keyword", keyword).build())
                    .retrieve().toEntity(String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            var body = JsonMapper.builder().build().readTree(response.getBody());
            assertThat(body.path("data").size()).isEqualTo(1);
            var found = body.path("data").get(0);
            assertThat(found.path("id").asLong()).isEqualTo(product);
            assertThat(found.path("categoryName").asString()).isEqualTo(keyword);
            assertThat(found.path("primaryImageUrl").asString()).isEqualTo(image);
            assertThat(body.path("pagination").path("totalElements").asLong()).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM product_images WHERE product_id=?", product);
            jdbc.update("DELETE FROM products WHERE id=?", product);
            jdbc.update("DELETE FROM categories WHERE id=?", category);
        }
    }
}
