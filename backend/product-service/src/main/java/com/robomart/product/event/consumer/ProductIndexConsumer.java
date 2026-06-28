package com.robomart.product.event.consumer;

import java.math.BigDecimal;
import java.time.Instant;

import org.apache.avro.specific.SpecificRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.robomart.events.product.ProductCreatedEvent;
import com.robomart.events.product.ProductDeletedEvent;
import com.robomart.events.product.ProductUpdatedEvent;
import com.robomart.product.document.ProductDocument;
import com.robomart.product.repository.ProductSearchRepository;
import com.robomart.product.service.ProcessedEventService;

@Component
public class ProductIndexConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProductIndexConsumer.class);

    private static final String CONSUMER_GROUP = "product-service-product-index-group";

    private final ProductSearchRepository productSearchRepository;
    private final ProcessedEventService processedEventService;

    public ProductIndexConsumer(ProductSearchRepository productSearchRepository,
                                ProcessedEventService processedEventService) {
        this.productSearchRepository = productSearchRepository;
        this.processedEventService = processedEventService;
    }

    @KafkaListener(
            topics = "product.product.created",
            groupId = CONSUMER_GROUP
    )
    public void onProductCreated(ProductCreatedEvent event) {
        log.debug("Received PRODUCT_CREATED event: aggregateId={}, eventId={}",
                event.getAggregateId(), event.getEventId());
        String eventId = event.getEventId() != null ? event.getEventId().toString() : null;
        if (processedEventService.isProcessed(CONSUMER_GROUP, eventId)) {
            log.debug("Skipping duplicate PRODUCT_CREATED event: eventId={}", eventId);
            return;
        }
        indexProduct(event);
        processedEventService.markProcessed(CONSUMER_GROUP, eventId);
    }

    @KafkaListener(
            topics = "product.product.updated",
            groupId = CONSUMER_GROUP
    )
    public void onProductUpdated(ProductUpdatedEvent event) {
        log.debug("Received PRODUCT_UPDATED event: aggregateId={}, eventId={}",
                event.getAggregateId(), event.getEventId());
        String eventId = event.getEventId() != null ? event.getEventId().toString() : null;
        if (processedEventService.isProcessed(CONSUMER_GROUP, eventId)) {
            log.debug("Skipping duplicate PRODUCT_UPDATED event: eventId={}", eventId);
            return;
        }
        indexProduct(event);
        processedEventService.markProcessed(CONSUMER_GROUP, eventId);
    }

    @KafkaListener(
            topics = "product.product.deleted",
            groupId = CONSUMER_GROUP
    )
    public void onProductDeleted(ProductDeletedEvent event) {
        log.debug("Received PRODUCT_DELETED event: aggregateId={}, productId={}",
                event.getAggregateId(), event.getProductId());
        String eventId = event.getEventId() != null ? event.getEventId().toString() : null;
        if (processedEventService.isProcessed(CONSUMER_GROUP, eventId)) {
            log.debug("Skipping duplicate PRODUCT_DELETED event: eventId={}", eventId);
            return;
        }
        productSearchRepository.deleteById(event.getProductId());
        log.info("Deleted product from Elasticsearch index: id={}", event.getProductId());
        processedEventService.markProcessed(CONSUMER_GROUP, eventId);
    }

    private void indexProduct(SpecificRecord record) {
        ProductDocument doc = new ProductDocument();
        Instant now = Instant.now();

        if (record instanceof ProductCreatedEvent event) {
            doc.setId(event.getProductId());
            doc.setSku(event.getSku());
            doc.setName(event.getName());
            doc.setDescription(event.getDescription());
            doc.setPrice(new BigDecimal(event.getPrice()));
            doc.setCategoryId(event.getCategoryId());
            doc.setCategoryName(event.getCategoryName());
            doc.setBrand(event.getBrand());
            doc.setRating(event.getRating() != null ? new BigDecimal(event.getRating()) : null);
            doc.setStockQuantity(event.getStockQuantity());
            doc.setCreatedAt(now);
            doc.setUpdatedAt(now);
        } else if (record instanceof ProductUpdatedEvent event) {
            doc.setId(event.getProductId());
            doc.setSku(event.getSku());
            doc.setName(event.getName());
            doc.setDescription(event.getDescription());
            doc.setPrice(new BigDecimal(event.getPrice()));
            doc.setCategoryId(event.getCategoryId());
            doc.setCategoryName(event.getCategoryName());
            doc.setBrand(event.getBrand());
            doc.setRating(event.getRating() != null ? new BigDecimal(event.getRating()) : null);
            doc.setStockQuantity(event.getStockQuantity());
            var existing = productSearchRepository.findById(event.getProductId());
            doc.setCreatedAt(existing.map(ProductDocument::getCreatedAt).orElse(now));
            doc.setUpdatedAt(now);
        }

        productSearchRepository.save(doc);
        log.info("Indexed product in Elasticsearch: id={}, name={}", doc.getId(), doc.getName());
    }
}
