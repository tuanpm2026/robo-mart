package com.robomart.product.event.consumer;


import org.apache.avro.specific.SpecificRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.robomart.events.product.ProductCreatedEvent;
import com.robomart.events.product.ProductDeletedEvent;
import com.robomart.events.product.ProductUpdatedEvent;
import com.robomart.product.service.ProductProjectionStore;
import com.robomart.product.service.ProductIndexWriter;
import com.robomart.product.service.ProcessedEventService;

@Component
public class ProductIndexConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProductIndexConsumer.class);

    private static final String CONSUMER_GROUP = "product-service-product-index-group";

    private final ProductProjectionStore source;
    private final ProductIndexWriter writer;
    private final ProcessedEventService processedEventService;

    public ProductIndexConsumer(ProductProjectionStore source, ProductIndexWriter writer,
                                ProcessedEventService processedEventService) {
        this.source = source;
        this.writer = writer;
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
        writer.write(source.load(event.getProductId()));
        log.info("Reconciled product projection after deletion event: id={}", event.getProductId());
        processedEventService.markProcessed(CONSUMER_GROUP, eventId);
    }

    private void indexProduct(SpecificRecord record) {
        long id;
        if (record instanceof ProductCreatedEvent event) id = event.getProductId();
        else if (record instanceof ProductUpdatedEvent event) id = event.getProductId();
        else throw new IllegalArgumentException("Unsupported product projection event");
        writer.write(source.load(id));
    }
}
