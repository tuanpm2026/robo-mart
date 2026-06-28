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
import com.robomart.product.service.ProcessedEventService;

@Component
public class ProductCacheInvalidationConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProductCacheInvalidationConsumer.class);

    private static final String CONSUMER_GROUP = "product-service-cache-invalidation-group";

    private final CacheManager cacheManager;
    private final ProcessedEventService processedEventService;

    public ProductCacheInvalidationConsumer(CacheManager cacheManager,
                                            ProcessedEventService processedEventService) {
        this.cacheManager = cacheManager;
        this.processedEventService = processedEventService;
    }

    @KafkaListener(
            topics = "product.product.updated",
            groupId = CONSUMER_GROUP
    )
    public void onProductUpdated(ProductUpdatedEvent event) {
        log.debug("Cache invalidation triggered by PRODUCT_UPDATED: productId={}, eventId={}",
                event.getProductId(), event.getEventId());
        String eventId = event.getEventId() != null ? event.getEventId().toString() : null;
        if (processedEventService.isProcessed(CONSUMER_GROUP, eventId)) {
            log.debug("Skipping duplicate cache-invalidation PRODUCT_UPDATED event: eventId={}", eventId);
            return;
        }
        evictProductCaches(event.getProductId());
        processedEventService.markProcessed(CONSUMER_GROUP, eventId);
    }

    @KafkaListener(
            topics = "product.product.deleted",
            groupId = CONSUMER_GROUP
    )
    public void onProductDeleted(ProductDeletedEvent event) {
        log.debug("Cache invalidation triggered by PRODUCT_DELETED: productId={}, eventId={}",
                event.getProductId(), event.getEventId());
        String eventId = event.getEventId() != null ? event.getEventId().toString() : null;
        if (processedEventService.isProcessed(CONSUMER_GROUP, eventId)) {
            log.debug("Skipping duplicate cache-invalidation PRODUCT_DELETED event: eventId={}", eventId);
            return;
        }
        evictProductCaches(event.getProductId());
        processedEventService.markProcessed(CONSUMER_GROUP, eventId);
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
