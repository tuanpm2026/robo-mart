package com.robomart.notification.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.robomart.notification.entity.FailedEvent;
import com.robomart.notification.kafka.DlqRecordCodec;
import com.robomart.notification.kafka.RawAvroDeserializer;
import com.robomart.notification.kafka.RawRecordRecoverer;
import com.robomart.notification.repository.FailedEventRepository;

/** Source-offset deduplication and replay claims commit before any broker send. */
@Service
public class DlqRecordStore {
    private final JdbcTemplate jdbc;
    private final FailedEventRepository events;
    private final DlqRecordCodec codec;

    public DlqRecordStore(JdbcTemplate jdbc, FailedEventRepository events, DlqRecordCodec codec) {
        this.jdbc = jdbc;
        this.events = events;
        this.codec = codec;
    }

    @Transactional
    public void capture(ConsumerRecord<byte[], byte[]> record) {
        Headers headers = record.headers();
        String topic = text(headers, "kafka_dlt-original-topic");
        Integer partition = integer(headers, "kafka_dlt-original-partition");
        Long offset = number(headers, "kafka_dlt-original-offset");
        boolean complete = topic != null && !topic.equals("notification.dlq") && partition != null && partition >= 0
                && offset != null && offset >= 0;
        boolean raw = complete && "1".equals(text(headers, RawRecordRecoverer.RAW_MARKER));
        if (!complete) {
            // Legacy/malformed envelopes remain reviewable and deduplicate by their capture position.
            topic = record.topic();
            partition = record.partition();
            offset = record.offset();
        }
        byte[] value = record.value();
        String preview = value == null ? "<null>" : "base64:" + Base64.getEncoder().encodeToString(
                java.util.Arrays.copyOf(value, Math.min(360, value.length)));
        String aggregate = displayKey(record.key());
        jdbc.update("INSERT INTO failed_events(event_type,aggregate_id,original_topic,error_class,error_message,"
                + "payload_preview,retry_count,status,original_partition,original_offset,original_timestamp,"
                + "record_key,record_value,original_headers,raw_record_available,source_event_id,source_consumer_group) "
                + "VALUES (?,?,?,?,?,?,0,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(original_topic,original_partition,original_offset) "
                + "WHERE original_partition IS NOT NULL AND original_offset IS NOT NULL DO NOTHING",
                topic, shorten(aggregate, 200), topic, shorten(text(headers, "kafka_dlt-exception-fqcn"), 500),
                text(headers, "kafka_dlt-exception-message"), preview, raw ? "PENDING" : "LEGACY_REVIEW", partition, offset,
                number(headers, "kafka_dlt-original-timestamp"), record.key(), value, codec.encode(headers), raw,
                shorten(text(headers, RawAvroDeserializer.EVENT_ID), 100),
                shorten(text(headers, "kafka_dlt-original-consumer-group"), 150));
    }

    @Transactional
    public ReplayClaim claim(Long id) {
        UUID token = UUID.randomUUID();
        int changed = jdbc.update("UPDATE failed_events SET status='REPLAYING',replay_token=?,"
                + "replay_claim_until=now()+interval '2 minutes',retry_count=retry_count+1,last_attempted_at=now() "
                + "WHERE id=? AND raw_record_available AND (status='PENDING' OR (status='REPLAYING' "
                + "AND replay_claim_until<=now()))", token, id);
        if (changed == 0) {
            FailedEvent event = events.findById(id).orElseThrow(() -> new NoSuchElementException("Event not found: " + id));
            throw new IllegalStateException(event.isRawRecordAvailable()
                    ? "Event already replayed or replay is in progress" : "Original bytes unavailable; manual review required");
        }
        return jdbc.queryForObject("SELECT id,replay_token,original_topic,original_partition,original_timestamp,"
                + "record_key,record_value,original_headers FROM failed_events WHERE id=? AND replay_token=?",
                (rs, row) -> new ReplayClaim(rs.getLong(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getInt(4),
                        rs.getObject(5, Long.class), rs.getBytes(6), rs.getBytes(7), rs.getString(8)), id, token);
    }

    @Transactional
    public boolean acknowledge(Long id, UUID token) {
        return jdbc.update("UPDATE failed_events SET status='REPLAYED',replay_token=NULL,replay_claim_until=NULL "
                + "WHERE id=? AND replay_token=? AND status='REPLAYING'", id, token) == 1;
    }

    @Transactional
    public void release(Long id, UUID token) {
        jdbc.update("UPDATE failed_events SET status='PENDING',replay_token=NULL,replay_claim_until=NULL "
                + "WHERE id=? AND replay_token=? AND status='REPLAYING'", id, token);
    }

    private String displayKey(byte[] key) {
        if (key == null) return null;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(key)).toString();
            if (text.indexOf(0) < 0) return text;
        } catch (java.nio.charset.CharacterCodingException binaryKey) {
            // Binary keys remain exact in BYTEA; display them safely in the bounded text preview.
        }
        return "base64:" + Base64.getEncoder().encodeToString(key);
    }

    private String text(Headers headers, String name) {
        var header = headers.lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8).replace("\u0000", "\\0");
    }

    private Long number(Headers headers, String name) {
        var header = headers.lastHeader(name);
        if (header == null || header.value() == null) return null;
        byte[] bytes = header.value();
        if (bytes.length == Long.BYTES) return ByteBuffer.wrap(bytes).getLong();
        try { return Long.valueOf(new String(bytes, StandardCharsets.UTF_8)); }
        catch (NumberFormatException malformed) { return null; }
    }

    private Integer integer(Headers headers, String name) {
        var header = headers.lastHeader(name);
        if (header == null || header.value() == null) return null;
        byte[] bytes = header.value();
        if (bytes.length == Integer.BYTES) return ByteBuffer.wrap(bytes).getInt();
        try { return Integer.valueOf(new String(bytes, StandardCharsets.UTF_8)); }
        catch (NumberFormatException malformed) { return null; }
    }

    private String shorten(String value, int limit) {
        return value == null ? null : value.substring(0, Math.min(value.length(), limit));
    }
    public record ReplayClaim(long id, UUID token, String topic, int partition, Long timestamp,
            byte[] key, byte[] value, String headers) { }

}
