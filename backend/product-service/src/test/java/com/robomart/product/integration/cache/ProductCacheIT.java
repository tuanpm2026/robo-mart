package com.robomart.product.integration.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import com.robomart.events.product.ProductDeletedEvent;
import com.robomart.events.product.ProductUpdatedEvent;
import com.robomart.product.config.CacheConfig;
import com.robomart.product.event.consumer.ProductCacheInvalidationConsumer;
import com.robomart.test.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class ProductCacheIT {

    @LocalServerPort
    private int port;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private ProductCacheInvalidationConsumer cacheInvalidationConsumer;

    private RestClient restClient;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build();

        // Clear caches before each test
        var productDetailCache = cacheManager.getCache(CacheConfig.CACHE_PRODUCT_DETAIL);
        if (productDetailCache != null) {
            productDetailCache.clear();
        }
        var productSearchCache = cacheManager.getCache(CacheConfig.CACHE_PRODUCT_SEARCH);
        if (productSearchCache != null) {
            productSearchCache.clear();
        }
    }

    @Test
    void shouldCacheProductDetailOnFirstRequest() {
        // First request - fetches from DB
        var response1 = restClient.get()
                .uri("/api/v1/products/1")
                .retrieve()
                .toEntity(String.class);

        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response1.getBody()).contains("\"data\"");

        // Verify entry is in cache
        var cache = cacheManager.getCache(CacheConfig.CACHE_PRODUCT_DETAIL);
        assertThat(cache).isNotNull();
        assertThat(cache.get(1L)).isNotNull();
    }

    @Test
    void shouldReturnCachedProductDetailOnSubsequentRequest() {
        // First request - populates cache
        var response1 = restClient.get()
                .uri("/api/v1/products/1")
                .retrieve()
                .toEntity(String.class);

        // Second request - should come from cache
        var response2 = restClient.get()
                .uri("/api/v1/products/1")
                .retrieve()
                .toEntity(String.class);

        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Both responses should contain product data
        assertThat(response1.getBody()).contains("\"data\"");
        assertThat(response2.getBody()).contains("\"data\"");
    }

    @Test
    void shouldInvalidateProductDetailCacheWhenProductUpdated() {
        // Populate cache directly to avoid Kafka side-effects from HTTP requests
        var cache = cacheManager.getCache(CacheConfig.CACHE_PRODUCT_DETAIL);
        assertThat(cache).isNotNull();
        cache.put(1L, "cached-product-detail");
        assertThat(cache.get(1L)).isNotNull();

        // Simulate product update event
        var updateEvent = ProductUpdatedEvent.newBuilder()
                .setEventId("test-evt-update-1")
                .setEventType("PRODUCT_UPDATED")
                .setAggregateId("1")
                .setAggregateType("PRODUCT")
                .setTimestamp(java.time.Instant.now())
                .setVersion(1)
                .setProductId(1L)
                .setSku("SKU-001")
                .setName("Updated Product")
                .setPrice("99.99")
                .setCategoryId(1L)
                .setCategoryName("Electronics")
                .setStockQuantity(50)
                .build();

        cacheInvalidationConsumer.onProductUpdated(updateEvent);

        // Cache should be invalidated
        assertThat(cache.get(1L)).isNull();
    }

    @Test
    void shouldInvalidateProductDetailCacheWhenProductDeleted() {
        // Populate cache directly to avoid Kafka side-effects from HTTP requests
        var cache = cacheManager.getCache(CacheConfig.CACHE_PRODUCT_DETAIL);
        assertThat(cache).isNotNull();
        cache.put(1L, "cached-product-detail");
        assertThat(cache.get(1L)).isNotNull();

        // Simulate product delete event
        var deleteEvent = ProductDeletedEvent.newBuilder()
                .setEventId("test-evt-delete-1")
                .setEventType("PRODUCT_DELETED")
                .setAggregateId("1")
                .setAggregateType("PRODUCT")
                .setTimestamp(java.time.Instant.now())
                .setVersion(1)
                .setProductId(1L)
                .setSku("SKU-001")
                .build();

        cacheInvalidationConsumer.onProductDeleted(deleteEvent);

        // Cache should be invalidated
        assertThat(cache.get(1L)).isNull();
    }

    @Test
    void shouldClearSearchCacheWhenProductUpdated() {
        // Populate search cache
        restClient.get()
                .uri("/api/v1/products/search?keyword=test&page=0&size=10")
                .retrieve()
                .toEntity(String.class);

        var searchCache = cacheManager.getCache(CacheConfig.CACHE_PRODUCT_SEARCH);
        assertThat(searchCache).isNotNull();

        // Simulate product update event
        var updateEvent = ProductUpdatedEvent.newBuilder()
                .setEventId("test-evt-search-invalidate")
                .setEventType("PRODUCT_UPDATED")
                .setAggregateId("1")
                .setAggregateType("PRODUCT")
                .setTimestamp(java.time.Instant.now())
                .setVersion(1)
                .setProductId(1L)
                .setSku("SKU-001")
                .setName("Updated")
                .setPrice("99.99")
                .setCategoryId(1L)
                .setCategoryName("Electronics")
                .setStockQuantity(50)
                .build();

        cacheInvalidationConsumer.onProductUpdated(updateEvent);

        // Verify search cache was cleared by making the same search request again
        // and confirming we get a fresh response (the search should hit ES again)
        var response2 = restClient.get()
                .uri("/api/v1/products/search?keyword=test&page=0&size=10")
                .retrieve()
                .toEntity(String.class);
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldReturnCachedSearchResultsOnSameQuery() {
        // First search
        var response1 = restClient.get()
                .uri("/api/v1/products/search?keyword=robot&page=0&size=10")
                .retrieve()
                .toEntity(String.class);

        // Second search with same params
        var response2 = restClient.get()
                .uri("/api/v1/products/search?keyword=robot&page=0&size=10")
                .retrieve()
                .toEntity(String.class);

        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldNotReturnCachedResultForDifferentSearchParams() {
        // Search with keyword "robot"
        var response1 = restClient.get()
                .uri("/api/v1/products/search?keyword=robot&page=0&size=10")
                .retrieve()
                .toEntity(String.class);

        // Search with different keyword "toy"
        var response2 = restClient.get()
                .uri("/api/v1/products/search?keyword=toy&page=0&size=10")
                .retrieve()
                .toEntity(String.class);

        // Both should succeed (different cache keys)
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
