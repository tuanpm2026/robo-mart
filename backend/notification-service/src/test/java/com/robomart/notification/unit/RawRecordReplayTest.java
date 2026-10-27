package com.robomart.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import com.robomart.notification.kafka.DlqRecordCodec;
import com.robomart.notification.kafka.RawAvroDeserializer;
import com.robomart.notification.kafka.RawKeyDeserializer;
import com.robomart.notification.kafka.RawRecordRecoverer;

import tools.jackson.databind.json.JsonMapper;

class RawRecordReplayTest {
    @Test
    void malformedAvroAndNonUtf8KeyRetainOriginalBytesBeforeDecoding() {
        Headers headers = new RecordHeaders();
        byte[] key = {(byte) 255, 0};
        byte[] value = {1, 2, 3};
        try (var keys = new RawKeyDeserializer(); var values = new RawAvroDeserializer()) {
            keys.configure(Map.of(), true);
            var buffer = java.nio.ByteBuffer.wrap(new byte[]{9, key[0], key[1]});
            buffer.position(1);
            keys.deserialize("source", headers, buffer);
            assertThat(buffer.position()).isEqualTo(1);
            values.configure(Map.of("schema.registry.url", "mock://raw-record-unit", "specific.avro.reader", true), false);
            assertThatThrownBy(() -> values.deserialize("source", headers, value)).isInstanceOf(RuntimeException.class);
            assertThat(headers.lastHeader(RawKeyDeserializer.RAW_KEY).value()).isEqualTo(key);
            assertThat(headers.lastHeader(RawAvroDeserializer.RAW_VALUE).value()).isEqualTo(value);
        }
    }

    @Test
    void recoveryUsesWireBytesRatherThanDecodedObjectAndPreservesNullPayload() {
        var recoverer = new ExposedRecoverer();
        var source = new ConsumerRecord<String, Object>("source", 2, 10L, "decoded-key", "decoded-value");
        source.headers().add(RawKeyDeserializer.RAW_KEY, new byte[]{(byte) 255});
        source.headers().add(RawAvroDeserializer.RAW_VALUE, new byte[]{0, 1});
        ProducerRecord<Object, Object> result = recoverer.record(source);
        assertThat((byte[]) result.key()).isEqualTo(new byte[]{(byte) 255});
        assertThat((byte[]) result.value()).isEqualTo(new byte[]{0, 1});
        assertThat(result.headers().lastHeader(RawRecordRecoverer.RAW_MARKER)).isNotNull();
        source.headers().remove(RawAvroDeserializer.RAW_VALUE);
        source.headers().add(RawAvroDeserializer.RAW_VALUE, null);
        assertThat(recoverer.record(source).value()).isNull();
    }

    @Test
    void headerCodecPreservesOrderDuplicatesAndNullButRemovesFailureBookkeepingOnReplay() {
        var codec = new DlqRecordCodec(JsonMapper.builder().build());
        Headers source = new RecordHeaders().add("custom", new byte[]{1}).add("custom", new byte[]{2})
                .add("nullable", null).add("x-correlation-id", "trace".getBytes(StandardCharsets.UTF_8))
                .add("kafka_dlt-original-topic", "source".getBytes(StandardCharsets.UTF_8))
                .add(RawRecordRecoverer.RAW_MARKER, new byte[]{1});
        Headers restored = codec.replayHeaders(codec.encode(source));
        assertThat(restored.headers("custom")).hasSize(2);
        assertThat(restored.lastHeader("custom").value()).isEqualTo(new byte[]{2});
        assertThat(restored.lastHeader("nullable").value()).isNull();
        assertThat(restored.lastHeader("x-correlation-id").value()).isEqualTo("trace".getBytes(StandardCharsets.UTF_8));
        assertThat(restored.lastHeader("kafka_dlt-original-topic")).isNull();
        assertThat(restored.lastHeader(RawRecordRecoverer.RAW_MARKER)).isNull();
    }

    static class ExposedRecoverer extends RawRecordRecoverer {
        @SuppressWarnings("unchecked")
        ExposedRecoverer() { super(mock(KafkaTemplate.class)); }
        ProducerRecord<Object, Object> record(ConsumerRecord<?, ?> source) {
            return createProducerRecord(source, new TopicPartition("notification.dlq", 0),
                    new RecordHeaders(source.headers().toArray()), null, null);
        }
    }
}
