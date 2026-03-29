package com.robomart.product.event.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.robomart.events.product.ProductDeletedEvent;
import com.robomart.events.product.ProductUpdatedEvent;
import com.robomart.product.config.CacheConfig;

@Component
public class ProductCacheInvalidationConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProductCacheInvalidationConsumer.class);

    private final CacheManager cacheManager;

    public ProductCacheInvalidationConsumer(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @KafkaListener(
            topics = "product.product.updated",
            groupId = "product-service-cache-invalidation-group"
    )
    public void onProductUpdated(ProductUpdatedEvent event) {
        log.debug("Cache invalidation triggered by PRODUCT_UPDATED: productId={}, eventId={}",
                event.getProductId(), event.getEventId());
        evictProductCaches(event.getProductId());
    }

    @KafkaListener(
            topics = "product.product.deleted",
            groupId = "product-service-cache-invalidation-group"
    )
    public void onProductDeleted(ProductDeletedEvent event) {
        log.debug("Cache invalidation triggered by PRODUCT_DELETED: productId={}, eventId={}",
                event.getProductId(), event.getEventId());
        evictProductCaches(event.getProductId());
    }

    private void evictProductCaches(long productId) {
        Cache productDetailCache = cacheManager.getCache(CacheConfig.CACHE_PRODUCT_DETAIL);
        if (productDetailCache != null) {
            productDetailCache.evict(productId);
            log.debug("Evicted productDetail cache for productId={}", productId);
        }

        Cache productSearchCache = cacheManager.getCache(CacheConfig.CACHE_PRODUCT_SEARCH);
        if (productSearchCache != null) {
            productSearchCache.clear();
            log.debug("Cleared all productSearch cache entries");
        }
    }
}
