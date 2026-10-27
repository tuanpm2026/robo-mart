package com.robomart.notification.kafka;

import org.apache.avro.generic.IndexedRecord;
import org.apache.kafka.common.header.Headers;

import io.confluent.kafka.serializers.KafkaAvroDeserializer;

/** Preserve the original wire envelope before Avro decoding or schema projection can change it. */
public class RawAvroDeserializer extends KafkaAvroDeserializer {
    public static final String RAW_VALUE = "x-robomart-original-value";
    public static final String EVENT_ID = "x-robomart-event-id";

    @Override
    public Object deserialize(String topic, Headers headers, byte[] value) {
        headers.remove(RAW_VALUE);
        headers.add(RAW_VALUE, value);
        Object result = super.deserialize(topic, headers, value);
        if (result instanceof IndexedRecord record && record.getSchema().getField("eventId") != null) {
            headers.remove(EVENT_ID);
            headers.add(EVENT_ID, String.valueOf(record.get(record.getSchema().getField("eventId").pos())).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return result;
    }
}
