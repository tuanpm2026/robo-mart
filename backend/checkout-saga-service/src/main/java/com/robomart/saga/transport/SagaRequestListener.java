package com.robomart.saga.transport;

import com.robomart.events.order.CheckoutSagaRequestedEvent;
import com.robomart.saga.SagaStore;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class SagaRequestListener {
    private final SagaStore store;

    public SagaRequestListener(SagaStore store) {
        this.store = store;
    }

    @KafkaListener(topics = "order.checkout.requests.v1", groupId = "checkout-saga-v1")
    public void receive(
            org.apache.kafka.clients.consumer.ConsumerRecord<String, CheckoutSagaRequestedEvent>
                    record) {
        var event = record.value();
        if (event == null
                || record.key() == null
                || !record.key().contentEquals(event.getOrderId()))
            throw new IllegalArgumentException("Workflow records must be keyed by order ID");
        store.accept(event);
    }
}
