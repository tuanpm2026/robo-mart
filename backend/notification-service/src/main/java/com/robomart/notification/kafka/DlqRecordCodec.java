package com.robomart.notification.kafka;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/** JSON preserves header order, duplicate names and null values; replay strips failure bookkeeping. */
@Component
public class DlqRecordCodec {
    private final ObjectMapper mapper;

    public DlqRecordCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(Headers headers) {
        List<SavedHeader> entries = new ArrayList<>();
        headers.forEach(header -> entries.add(new SavedHeader(header.key(), header.value() == null ? null
                : Base64.getEncoder().encodeToString(header.value()))));
        return mapper.writeValueAsString(entries);
    }

    public Headers replayHeaders(String json) {
        RecordHeaders headers = new RecordHeaders();
        for (SavedHeader saved : mapper.readValue(json, SavedHeader[].class)) {
            String name = saved.name();
            if (name.startsWith("kafka_dlt-") || name.startsWith("x-dlq-")
                    || name.equals(RawRecordRecoverer.RAW_MARKER) || name.equals(RawAvroDeserializer.RAW_VALUE)
                    || name.equals(RawAvroDeserializer.EVENT_ID) || name.equals(RawKeyDeserializer.RAW_KEY)
                    || name.equals("kafka_deliveryAttempt")) continue;
            headers.add(name, saved.value() == null ? null : Base64.getDecoder().decode(saved.value()));
        }
        return headers;
    }

    public record SavedHeader(String name, String value) { }
}
