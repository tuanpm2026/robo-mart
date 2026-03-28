package com.robomart.product.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.robomart.product.entity.OutboxEvent;
import com.robomart.product.repository.OutboxEventRepository;

@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent saveEvent(String aggregateType, String aggregateId, String eventType, String payload) {
        var event = new OutboxEvent(aggregateType, aggregateId, eventType, payload);
        OutboxEvent saved = outboxEventRepository.save(event);
        log.debug("Outbox event saved: type={}, aggregateId={}, eventType={}",
                aggregateType, aggregateId, eventType);
        return saved;
    }
}
