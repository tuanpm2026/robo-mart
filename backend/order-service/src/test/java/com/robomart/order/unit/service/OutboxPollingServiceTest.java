package com.robomart.order.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import com.robomart.events.order.OrderCancelledEvent;
import com.robomart.events.order.OrderStatusChangedEvent;
import com.robomart.order.config.OutboxProperties;
import com.robomart.order.event.producer.OrderEventProducer;
import com.robomart.order.service.OrderOutboxStore.Delivery;
import com.robomart.order.service.OrderOutboxStore;
import com.robomart.order.service.OutboxPollingService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class OutboxPollingServiceTest {
    @Mock private OrderOutboxStore store;
    @Mock private OrderEventProducer producer;
    private OutboxPollingService service;
    private OutboxProperties properties;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setup() {
        meters = new SimpleMeterRegistry();
        properties = new OutboxProperties();
        service = new OutboxPollingService(store, producer, new ObjectMapper(), properties, meters);
    }

    @AfterEach
    void cleanup() {
        service.shutdown();
        meters.close();
        MDC.clear();
    }

    private Delivery event(long id, String aggregate, String type) {
        return new Delivery(id, aggregate, type,
                "{\"orderId\":\"" + aggregate + "\",\"previousStatus\":\"PAYMENT_PROCESSING\","
                        + "\"newStatus\":\"CONFIRMED\",\"reason\":\"cancelled\",\"cancelledBy\":\"user-1\"}",
                Instant.now(), UUID.randomUUID());
    }

    @Test
    void mapsBothDomainTypesWithStableIdsAcrossRetries() {
        Delivery status = event(10, "1", "order_status_changed");
        Delivery cancelled = event(11, "2", "order_cancelled");
        when(store.claim(any(), any())).thenReturn(List.of(status, cancelled));
        when(producer.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        service.pollAndPublish();
        service.pollAndPublish();

        var statusCaptor = ArgumentCaptor.forClass(SpecificRecord.class);
        verify(producer, org.mockito.Mockito.times(2))
                .send(eq(OrderEventProducer.TOPIC_STATUS_CHANGED), eq("1"), statusCaptor.capture());
        var first = (OrderStatusChangedEvent) statusCaptor.getAllValues().get(0);
        var second = (OrderStatusChangedEvent) statusCaptor.getAllValues().get(1);
        assertThat(first.getNewStatus()).hasToString("CONFIRMED");
        assertThat(first.getEventId()).isEqualTo(second.getEventId());
        var cancelledCaptor = ArgumentCaptor.forClass(SpecificRecord.class);
        verify(producer, org.mockito.Mockito.times(2))
                .send(eq(OrderEventProducer.TOPIC_CANCELLED), eq("2"), cancelledCaptor.capture());
        assertThat(((OrderCancelledEvent) cancelledCaptor.getValue()).getReason()).hasToString("cancelled");
    }

    @Test
    void failedAggregateDoesNotBlockAcknowledgementOfIndependentAggregate() {
        Delivery failed = event(1, "1", "order_status_changed");
        Delivery succeeded = event(2, "2", "order_status_changed");
        when(store.claim(any(), any())).thenReturn(List.of(failed, succeeded));
        when(producer.send(anyString(), eq("1"), any())).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")));
        when(producer.send(anyString(), eq("2"), any())).thenReturn(CompletableFuture.completedFuture(null));
        service.pollAndPublish();
        verify(store).release(failed);
        verify(store, never()).acknowledge(failed);
        verify(store).acknowledge(succeeded);
    }

    @Test
    void unknownTypesAreRetainedAndCounted() {
        Delivery poison = event(1, "1", "unknown");
        when(store.claim(any(), any())).thenReturn(List.of(poison));
        service.pollAndPublish();
        verify(producer, never()).send(anyString(), anyString(), any());
        verify(store, never()).acknowledge(any());
        verify(store).release(poison);
        assertThat(meters.counter("robomart.outbox.unmappable").count()).isEqualTo(1);
    }

    @Test
    void oneBatchDeadlineRetainsUncertainLeaseAndAcknowledgesCompletedSend() {
        properties.setPublishTimeout(Duration.ofMillis(100));
        Delivery hanging = event(1, "1", "order_status_changed");
        Delivery complete = event(2, "2", "order_status_changed");
        when(store.claim(any(), any())).thenReturn(List.of(hanging, complete));
        when(producer.send(anyString(), eq("1"), any())).thenReturn(new CompletableFuture<>());
        when(producer.send(anyString(), eq("2"), any())).thenReturn(CompletableFuture.completedFuture(null));
        long started = System.nanoTime();
        service.pollAndPublish();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        verify(store).acknowledge(complete);
        verify(store, never()).acknowledge(hanging);
        verify(store, never()).release(hanging);
    }

    @Test
    void propagatesCorrelationMdcToSendExecutor() {
        Delivery event = event(1, "1", "order_status_changed");
        when(store.claim(any(), any())).thenReturn(List.of(event));
        when(producer.send(anyString(), anyString(), any())).thenAnswer(invocation -> {
            assertThat(MDC.get("correlationId")).isEqualTo("correlation-test");
            return CompletableFuture.completedFuture(null);
        });
        MDC.put("correlationId", "correlation-test");
        service.pollAndPublish();
        verify(store).acknowledge(event);
        assertThat(MDC.get("correlationId")).isEqualTo("correlation-test");
    }
}
