package com.robomart.product.unit.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import com.robomart.events.product.ProductDeletedEvent;
import com.robomart.events.product.ProductUpdatedEvent;
import com.robomart.product.config.CacheConfig;
import com.robomart.product.event.consumer.ProductCacheInvalidationConsumer;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductCacheInvalidationConsumerTest {

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache productDetailCache;

    @Mock
    private Cache productSearchCache;

    private ProductCacheInvalidationConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ProductCacheInvalidationConsumer(cacheManager);
    }

    @Test
    void shouldEvictProductDetailCacheWhenProductUpdated() {
        when(cacheManager.getCache(CacheConfig.CACHE_PRODUCT_DETAIL)).thenReturn(productDetailCache);
        when(cacheManager.getCache(CacheConfig.CACHE_PRODUCT_SEARCH)).thenReturn(productSearchCache);

        var event = ProductUpdatedEvent.newBuilder()
                .setEventId("evt-1")
                .setEventType("PRODUCT_UPDATED")
                .setAggregateId("42")
                .setAggregateType("PRODUCT")
                .setTimestamp(java.time.Instant.now())
                .setVersion(1)
                .setProductId(42L)
                .setSku("SKU-001")
                .setName("Updated Product")
                .setPrice("29.99")
                .setCategoryId(1L)
                .setCategoryName("Electronics")
                .setStockQuantity(100)
                .build();

        consumer.onProductUpdated(event);

        verify(productDetailCache).evict(42L);
        verify(productSearchCache).clear();
    }

    @Test
    void shouldEvictProductDetailCacheWhenProductDeleted() {
        when(cacheManager.getCache(CacheConfig.CACHE_PRODUCT_DETAIL)).thenReturn(productDetailCache);
        when(cacheManager.getCache(CacheConfig.CACHE_PRODUCT_SEARCH)).thenReturn(productSearchCache);

        var event = ProductDeletedEvent.newBuilder()
                .setEventId("evt-2")
                .setEventType("PRODUCT_DELETED")
                .setAggregateId("42")
                .setAggregateType("PRODUCT")
                .setTimestamp(java.time.Instant.now())
                .setVersion(1)
                .setProductId(42L)
                .setSku("SKU-001")
                .build();

        consumer.onProductDeleted(event);

        verify(productDetailCache).evict(42L);
        verify(productSearchCache).clear();
    }

    @Test
    void shouldClearAllSearchCacheWhenProductUpdated() {
        when(cacheManager.getCache(CacheConfig.CACHE_PRODUCT_DETAIL)).thenReturn(productDetailCache);
        when(cacheManager.getCache(CacheConfig.CACHE_PRODUCT_SEARCH)).thenReturn(productSearchCache);

        var event = ProductUpdatedEvent.newBuilder()
                .setEventId("evt-3")
                .setEventType("PRODUCT_UPDATED")
                .setAggregateId("10")
                .setAggregateType("PRODUCT")
                .setTimestamp(java.time.Instant.now())
                .setVersion(1)
                .setProductId(10L)
                .setSku("SKU-010")
                .setName("Another Product")
                .setPrice("19.99")
                .setCategoryId(2L)
                .setCategoryName("Toys")
                .setStockQuantity(50)
                .build();

        consumer.onProductUpdated(event);

        verify(productSearchCache).clear();
    }

    @Test
    void shouldEvictIdempotentlyWhenSameEventReceivedTwice() {
        when(cacheManager.getCache(CacheConfig.CACHE_PRODUCT_DETAIL)).thenReturn(productDetailCache);
        when(cacheManager.getCache(CacheConfig.CACHE_PRODUCT_SEARCH)).thenReturn(productSearchCache);

        var event = ProductUpdatedEvent.newBuilder()
                .setEventId("duplicate-evt")
                .setEventType("PRODUCT_UPDATED")
                .setAggregateId("42")
                .setAggregateType("PRODUCT")
                .setTimestamp(java.time.Instant.now())
                .setVersion(1)
                .setProductId(42L)
                .setSku("SKU-001")
                .setName("Product")
                .setPrice("29.99")
                .setCategoryId(1L)
                .setCategoryName("Electronics")
                .setStockQuantity(100)
                .build();

        consumer.onProductUpdated(event);
        consumer.onProductUpdated(event);

        // Cache eviction is idempotent — evicting twice is harmless
        verify(productDetailCache, org.mockito.Mockito.times(2)).evict(42L);
        verify(productSearchCache, org.mockito.Mockito.times(2)).clear();
    }

    @Test
    void shouldHandleNullCacheGracefully() {
        when(cacheManager.getCache(CacheConfig.CACHE_PRODUCT_DETAIL)).thenReturn(null);
        when(cacheManager.getCache(CacheConfig.CACHE_PRODUCT_SEARCH)).thenReturn(null);

        var event = ProductUpdatedEvent.newBuilder()
                .setEventId("evt-null-cache")
                .setEventType("PRODUCT_UPDATED")
                .setAggregateId("42")
                .setAggregateType("PRODUCT")
                .setTimestamp(java.time.Instant.now())
                .setVersion(1)
                .setProductId(42L)
                .setSku("SKU-001")
                .setName("Product")
                .setPrice("29.99")
                .setCategoryId(1L)
                .setCategoryName("Electronics")
                .setStockQuantity(100)
                .build();

        // Should not throw
        consumer.onProductUpdated(event);

        verify(productDetailCache, never()).evict(42L);
    }
}
