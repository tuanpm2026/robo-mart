package com.robomart.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.robomart.notification.event.DlqConsumer;
import com.robomart.notification.kafka.RawAvroDeserializer;
import com.robomart.notification.kafka.RawRecordRecoverer;
import com.robomart.notification.repository.FailedEventRepository;
import com.robomart.notification.service.DlqRecordStore;
import com.robomart.notification.service.DlqRecordStore.ReplayClaim;
import com.robomart.test.PostgresContainerConfig;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.listener.auto-startup=false", "notification.scheduling.enabled=false"})
@ActiveProfiles("test")
@Import(PostgresContainerConfig.class)
class DlqRecordStoreIT {
    @Autowired private DlqRecordStore store;
    @Autowired private DlqConsumer consumer;
    @Autowired private FailedEventRepository events;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clear() { events.deleteAll(); }

    private ConsumerRecord<byte[], byte[]> record(long offset, byte[] value) {
        var record = new ConsumerRecord<>("notification.dlq", 0, offset, new byte[]{(byte) 255, 0}, value);
        record.headers().add("kafka_dlt-original-topic", "source.topic".getBytes(StandardCharsets.UTF_8));
        record.headers().add("kafka_dlt-original-partition", ByteBuffer.allocate(4).putInt(2).array());
        record.headers().add("kafka_dlt-original-offset", ByteBuffer.allocate(8).putLong(offset).array());
        record.headers().add("kafka_dlt-original-timestamp", ByteBuffer.allocate(8).putLong(1234).array());
        record.headers().add(RawRecordRecoverer.RAW_MARKER, "1".getBytes(StandardCharsets.UTF_8));
        record.headers().add(RawAvroDeserializer.EVENT_ID, ("event-" + offset).getBytes(StandardCharsets.UTF_8));
        record.headers().add("custom", new byte[]{1}).add("custom", new byte[]{2});
        return record;
    }

    @Test
    void sourceOffsetDedupPreservesDifferentEventsWithSameKeyAndExactBytes() {
        var first = record(10, new byte[]{0, 1, (byte) 255});
        consumer.onDlqMessage(first);
        consumer.onDlqMessage(first);
        consumer.onDlqMessage(record(11, new byte[]{2, 3}));
        assertThat(events.count()).isEqualTo(2);
        var saved = events.findAll().stream().filter(e -> e.getOriginalOffset() == 10).findFirst().orElseThrow();
        assertThat(saved.getRecordKey()).isEqualTo(first.key());
        assertThat(saved.getRecordValue()).isEqualTo(first.value());
        assertThat(saved.getOriginalPartition()).isEqualTo(2);
        assertThat(saved.getOriginalTimestamp()).isEqualTo(1234);
        assertThat(saved.getSourceEventId()).isEqualTo("event-10");
        assertThat(saved.getOriginalHeaders()).contains("custom");
        assertThat(events.findSummariesByStatusNot("REPLAYED", PageRequest.of(0, 25))).hasSize(2);
    }

    @Test
    void concurrentReplayClaimsAndLeaseRecoveryFenceOldAcknowledgements() throws Exception {
        consumer.onDlqMessage(record(20, new byte[]{1}));
        Long id = events.findAll().getFirst().getId();
        CountDownLatch start = new CountDownLatch(1);
        ReplayClaim owner = null;
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<ReplayClaim>> claims = List.of(pool.submit(() -> claimAfter(start, id)),
                    pool.submit(() -> claimAfter(start, id)));
            start.countDown();
            int successes = 0;
            for (var claim : claims) {
                ReplayClaim result = claim.get(10, TimeUnit.SECONDS);
                if (result != null) { successes++; owner = result; }
            }
            assertThat(successes).isEqualTo(1);
        }
        jdbc.update("UPDATE failed_events SET replay_claim_until=now()-interval '1 second' WHERE id=?", id);
        ReplayClaim recovered = store.claim(id);
        assertThat(recovered.token()).isNotEqualTo(owner.token());
        assertThat(store.acknowledge(id, owner.token())).isFalse();
        assertThat(store.acknowledge(id, recovered.token())).isTrue();
        assertThat(events.findById(id).orElseThrow().getStatus()).isEqualTo("REPLAYED");
        assertThatThrownBy(() -> store.claim(id)).isInstanceOf(IllegalStateException.class);
    }

    private ReplayClaim claimAfter(CountDownLatch start, Long id) throws Exception {
        start.await();
        try { return store.claim(id); }
        catch (IllegalStateException busy) { return null; }
    }

    @Test
    void databaseFailurePropagatesAndSameRecordCanBeCapturedAfterRecovery() {
        var record = record(30, new byte[]{1});
        jdbc.execute("ALTER TABLE failed_events ADD CONSTRAINT reject_capture CHECK (original_offset<>30)");
        try {
            assertThatThrownBy(() -> consumer.onDlqMessage(record)).isInstanceOf(RuntimeException.class);
            assertThat(events.count()).isZero();
        } finally {
            jdbc.execute("ALTER TABLE failed_events DROP CONSTRAINT reject_capture");
        }
        consumer.onDlqMessage(record);
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void nullPayloadIsReplayableButLegacySerializedEnvelopeRequiresManualReview() {
        consumer.onDlqMessage(record(40, null));
        Long id = events.findAll().getFirst().getId();
        assertThat(store.claim(id).value()).isNull();
        var legacy = record(41, new byte[]{1});
        legacy.headers().remove(RawRecordRecoverer.RAW_MARKER);
        consumer.onDlqMessage(legacy);
        var review = events.findAll().stream().filter(e -> e.getOriginalOffset() == 41).findFirst().orElseThrow();
        assertThat(review.getStatus()).isEqualTo("LEGACY_REVIEW");
        assertThat(review.isRawRecordAvailable()).isFalse();
        assertThatThrownBy(() -> store.claim(review.getId())).isInstanceOf(IllegalStateException.class);
    }
}
