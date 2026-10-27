package com.robomart.notification.kafka;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

/** DLT publication forwards original bytes; it never reserializes an Avro object. */
public class RawRecordRecoverer extends DeadLetterPublishingRecoverer {
    public static final String RAW_MARKER = "x-robomart-raw-record";

    public RawRecordRecoverer(KafkaTemplate<byte[], byte[]> template) {
        super(template, (record, failure) -> new TopicPartition("notification.dlq", 0));
        setFailIfSendResultIsError(true);
        setWaitForSendResultTimeout(java.time.Duration.ofSeconds(40));
        setAppendOriginalHeaders(false);
    }

    @Override
    protected ProducerRecord<Object, Object> createProducerRecord(ConsumerRecord<?, ?> record,
            TopicPartition destination, Headers headers, byte[] key, byte[] value) {
        var rawKey = record.headers().lastHeader(RawKeyDeserializer.RAW_KEY);
        var rawValue = record.headers().lastHeader(RawAvroDeserializer.RAW_VALUE);
        if (rawKey == null || rawValue == null) {
            throw new IllegalStateException("Original Kafka wire bytes are unavailable; retain source record for retry");
        }
        byte[] originalKey = rawKey.value();
        byte[] originalValue = rawValue.value();
        headers.remove(RawKeyDeserializer.RAW_KEY);
        headers.remove(RawAvroDeserializer.RAW_VALUE);
        headers.remove(RAW_MARKER);
        headers.add(RAW_MARKER, "1".getBytes(StandardCharsets.UTF_8));
        return new ProducerRecord<>(destination.topic(), destination.partition(), record.timestamp() < 0 ? null : record.timestamp(),
                originalKey, originalValue, headers);
    }
}
