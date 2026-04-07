package com.robomart.inventory.integration;

import com.robomart.inventory.repository.InventoryItemRepository;
import com.robomart.test.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link com.robomart.inventory.controller.InventoryAdminRestController}.
 *
 * <p>Uses real PostgreSQL and Redis via Testcontainers (from @IntegrationTest).
 * Seed data provides ~50 inventory items (product IDs 1-50).
 * No JWT required — gateway enforces auth in production; tests call service directly.
 */
@IntegrationTest
class InventoryAdminRestIT {

    @LocalServerPort
    private int port;

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    private RestClient restClient;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    // Don't throw on error status codes — we assert them directly
                })
                .build();
    }

    @Test
    void shouldListInventoryWithPagination() {
        var response = restClient.get()
                .uri("/api/v1/admin/inventory?page=0&size=10")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("\"data\"");
        assertThat(response.getBody()).contains("\"pagination\"");
        assertThat(response.getBody()).contains("\"totalElements\"");
        assertThat(response.getBody()).contains("\"availableQuantity\"");
        assertThat(response.getBody()).contains("\"productId\"");
    }

    @Test
    void shouldRestockItemAndReturn200() {
        // Use product ID 10 (seeded with known initial stock)
        Long productId = 10L;
        int originalAvailable = inventoryItemRepository.findByProductId(productId)
                .map(i -> i.getAvailableQuantity()).orElseThrow();

        String body = """
                {
                  "quantity": 50,
                  "reason": "IT test restock"
                }
                """;

        var response = restClient.put()
                .uri("/api/v1/admin/inventory/{productId}/restock", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("\"data\"");
        assertThat(response.getBody()).contains("\"availableQuantity\"");

        // Verify DB updated
        int newAvailable = inventoryItemRepository.findByProductId(productId)
                .map(i -> i.getAvailableQuantity()).orElseThrow();
        assertThat(newAvailable).isEqualTo(originalAvailable + 50);
    }

    @Test
    void shouldReturn400WhenRestockQuantityIsZeroOrNegative() {
        String body = """
                {
                  "quantity": 0
                }
                """;

        var response = restClient.put()
                .uri("/api/v1/admin/inventory/{productId}/restock", 1L)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldReturn404WhenProductNotFound() {
        String body = """
                {
                  "quantity": 10
                }
                """;

        var response = restClient.put()
                .uri("/api/v1/admin/inventory/{productId}/restock", 9999L)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void shouldBulkRestockMultipleItems() {
        // Use product IDs 20 and 21 (seeded)
        Long productId20 = 20L;
        Long productId21 = 21L;
        int original20 = inventoryItemRepository.findByProductId(productId20)
                .map(i -> i.getAvailableQuantity()).orElseThrow();
        int original21 = inventoryItemRepository.findByProductId(productId21)
                .map(i -> i.getAvailableQuantity()).orElseThrow();

        String body = """
                {
                  "productIds": [20, 21],
                  "quantity": 30,
                  "reason": "IT bulk restock"
                }
                """;

        var response = restClient.post()
                .uri("/api/v1/admin/inventory/bulk-restock")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        // Verify both products updated in DB
        int new20 = inventoryItemRepository.findByProductId(productId20)
                .map(i -> i.getAvailableQuantity()).orElseThrow();
        int new21 = inventoryItemRepository.findByProductId(productId21)
                .map(i -> i.getAvailableQuantity()).orElseThrow();
        assertThat(new20).isEqualTo(original20 + 30);
        assertThat(new21).isEqualTo(original21 + 30);
    }
}
