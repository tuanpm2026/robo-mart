package com.robomart.notification.event;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.robomart.notification.service.DlqRecordStore;

/** Database failures propagate so the monitor never commits an unstored DLQ record. */
@Component
public class DlqConsumer {
    private final DlqRecordStore store;

    public DlqConsumer(DlqRecordStore store) {
        this.store = store;
    }

    @KafkaListener(topics = "notification.dlq", groupId = "notification-dlq-monitor-group",
            containerFactory = "dlqListenerContainerFactory")
    public void onDlqMessage(ConsumerRecord<byte[], byte[]> record) {
        store.capture(record);
    }
}
