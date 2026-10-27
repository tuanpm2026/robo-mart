package com.robomart.product.integration.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import com.robomart.test.ProductIntegrationTest;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/** Real OTel HTTP/Kafka scopes, including consecutive records with different/absent baggage. */
@ProductIntegrationTest
@AutoConfigureTracing
@Import(PlatformBaggageIT.Probes.class)
@TestPropertySource(properties = {
        "spring.kafka.producer.properties.interceptor.classes="
                + "com.robomart.product.integration.event.PlatformBaggageIT$ProbeInterceptor",
        "spring.kafka.consumer.properties.client.rack=platform-test-rack",
        "management.tracing.export.otlp.enabled=false"
})
class PlatformBaggageIT {
    private static final String TOPIC = "platform.baggage-" + UUID.randomUUID();
    @Autowired private Tracer tracer;
    @Autowired private KafkaTemplate<String, byte[]> kafka;
    @Autowired private ConsumerFactory<Object, Object> consumers;
    @Autowired private KafkaListenerEndpointRegistry listeners;
    @Autowired private Probe probe;
    @Value("${spring.kafka.bootstrap-servers}") private String bootstrap;
    @LocalServerPort private int port;

    @Test
    void kafkaScopesBaggagePerRecordAndPreservesConfiguredInterceptors() throws Exception {
        assertThat(consumers.getConfigurationProperties()).containsEntry("client.rack", "platform-test-rack");
        try (var admin = Admin.create(Map.of("bootstrap.servers", bootstrap))) {
            admin.createTopics(java.util.List.of(new NewTopic(TOPIC, 1, (short) 1)))
                    .all().get(10, TimeUnit.SECONDS);
        }
        int before = ProbeInterceptor.SENDS.get();
        // Queue all records before polling so the same partition can deliver them together.
        send("first", "correlation-first");
        send("second", "correlation-second");
        send("absent", null);
        var listener = listeners.getListenerContainer("platformBaggageProbe");
        assertThat(listener).isNotNull();
        listener.start();
        try {
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(probe.records).hasSize(3);
                assertThat(probe.records).as("Per-record snapshots").allSatisfy((key, snapshot) ->
                        assertThat(snapshot.traceId()).as(key + " has a real tracing scope").doesNotContain("00000000000000000000000000000000", "no-trace"));
                assertThat(probe.records.get("first").baggage()).isEqualTo("correlation-first");
                assertThat(probe.records.get("first").mdc()).isEqualTo("correlation-first");
                assertThat(probe.records.get("second").baggage()).isEqualTo("correlation-second");
                assertThat(probe.records.get("second").mdc()).isEqualTo("correlation-second");
                assertThat(probe.records.get("absent").baggage()).isEqualTo("absent");
                assertThat(probe.records.get("absent").mdc()).isEqualTo("absent");
            });
            assertThat(ProbeInterceptor.SENDS.get() - before).isEqualTo(3);
            assertThat(MDC.get("correlationId")).isNull();
        } finally {
            listener.stop();
        }
    }

    private void send(String value, String correlation) throws Exception {
        Span span = tracer.nextSpan().name("platform-send").start();
        try (var ignored = tracer.withSpan(span)) {
            if (correlation == null) {
                kafka.send(TOPIC, value, value.getBytes(StandardCharsets.UTF_8)).get(10, TimeUnit.SECONDS);
            } else {
                try (var baggage = tracer.createBaggageInScope("correlationId", correlation)) {
                    kafka.send(TOPIC, value, value.getBytes(StandardCharsets.UTF_8)).get(10, TimeUnit.SECONDS);
                }
            }
        } finally {
            span.end();
        }
    }

    @Test
    void httpUsesScopedBaggageOnVirtualThreadsWithoutTheLegacyFilter() {
        var http = RestClient.create("http://localhost:" + port);
        var result = http.get().uri("/api/v1/products/platform-baggage-probe")
                .header("correlationId", "http-correlation").retrieve().body(Snapshot.class);
        assertThat(result).isNotNull();
        assertThat(result.baggage()).isEqualTo("http-correlation");
        assertThat(result.mdc()).isEqualTo("http-correlation");
        assertThat(result.virtualThread()).isTrue();
        assertThat(result.traceId()).matches("[0-9a-f]{32}").isNotEqualTo("00000000000000000000000000000000");
        var absent = http.get().uri("/api/v1/products/platform-baggage-probe").retrieve().body(Snapshot.class);
        assertThat(absent).isNotNull();
        assertThat(absent.baggage()).isEqualTo("absent");
        assertThat(absent.mdc()).isEqualTo("absent");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Probes {
        @Bean
        String platformBaggageTopic() {
            return TOPIC;
        }

        @Bean(name = "platformBaggageProbe")
        Probe probe(Tracer tracer) {
            return new Probe(tracer);
        }
    }

    @TestComponent
    @RestController
    static class Probe {
        private final Tracer tracer;
        private final Map<String, Snapshot> records = new ConcurrentHashMap<>();

        Probe(Tracer tracer) {
            this.tracer = tracer;
        }

        @KafkaListener(id = "platformBaggageProbe", topics = "#{@platformBaggageTopic}",
                groupId = "platform-baggage-probe", autoStartup = "false", properties = {
                    "key.deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer",
                    "value.deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer"
                })
        void consume(ConsumerRecord<byte[], byte[]> record) {
            records.put(new String(record.value(), StandardCharsets.UTF_8), snapshot());
        }

        @GetMapping("/api/v1/products/platform-baggage-probe")
        public Snapshot snapshot() {
            var baggage = tracer.getBaggage("correlationId");
            String value = baggage != null ? baggage.get() : null;
            var span = tracer.currentSpan();
            return new Snapshot(value != null ? value : "absent",
                    MDC.get("correlationId") != null ? MDC.get("correlationId") : "absent",
                    span != null ? span.context().traceId() : "no-trace", Thread.currentThread().isVirtual());
        }
    }

    record Snapshot(String baggage, String mdc, String traceId, boolean virtualThread) { }

    public static class ProbeInterceptor implements ProducerInterceptor<Object, Object> {
        static final AtomicInteger SENDS = new AtomicInteger();
        @Override
        public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) {
            SENDS.incrementAndGet();
            return record;
        }
        @Override
        public void onAcknowledgement(RecordMetadata metadata, Exception exception) { }
        @Override
        public void close() { }
        @Override
        public void configure(Map<String, ?> configs) { }
    }
}
