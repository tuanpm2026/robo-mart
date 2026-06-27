package com.robomart.order.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.robomart.events.order.OrderCancelledEvent;
import com.robomart.events.order.OrderStatusChangedEvent;
import com.robomart.order.entity.OutboxEvent;
import com.robomart.order.event.producer.OrderEventProducer;
import com.robomart.order.repository.OutboxEventRepository;
import com.robomart.order.service.OutboxPollingService;

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxPollingService - JSON to Avro publishing")
class OutboxPollingServiceTest {

    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private OrderEventProducer orderEventProducer;
    @Mock private TransactionTemplate transactionTemplate;

    @Captor private ArgumentCaptor<SpecificRecord> eventCaptor;

    private OutboxPollingService service;

    @BeforeEach
    void setUp() {
        service = new OutboxPollingService(
                outboxEventRepository, orderEventProducer, new ObjectMapper(), transactionTemplate);

        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        lenient().when(orderEventProducer.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    @DisplayName("publishes order_status_changed as an Avro OrderStatusChangedEvent")
    void publishesStatusChangedAsAvro() {
        OutboxEvent event = new OutboxEvent("Order", "1", "order_status_changed",
                "{\"orderId\":\"1\",\"previousStatus\":\"PAYMENT_PROCESSING\",\"newStatus\":\"CONFIRMED\"}");
        when(outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc()).thenReturn(List.of(event));

        service.pollAndPublish();

        verify(orderEventProducer).send(eq(OrderEventProducer.TOPIC_STATUS_CHANGED), eq("1"),
                eventCaptor.capture());
        SpecificRecord sent = eventCaptor.getValue();
        assertThat(sent).isInstanceOf(OrderStatusChangedEvent.class);
        OrderStatusChangedEvent avro = (OrderStatusChangedEvent) sent;
        assertThat(avro.getOrderId().toString()).isEqualTo("1");
        assertThat(avro.getPreviousStatus().toString()).isEqualTo("PAYMENT_PROCESSING");
        assertThat(avro.getNewStatus().toString()).isEqualTo("CONFIRMED");
        assertThat(avro.getEventType().toString()).isEqualTo("ORDER_STATUS_CHANGED");
        assertThat(avro.getAggregateType().toString()).isEqualTo("Order");

        assertThat(event.isPublished()).isTrue();
        verify(outboxEventRepository).save(event);
    }

    @Test
    @DisplayName("publishes order_cancelled as an Avro OrderCancelledEvent")
    void publishesCancelledAsAvro() {
        OutboxEvent event = new OutboxEvent("Order", "7", "order_cancelled",
                "{\"orderId\":\"7\",\"reason\":\"Customer cancelled\",\"cancelledBy\":\"user-1\"}");
        when(outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc()).thenReturn(List.of(event));

        service.pollAndPublish();

        verify(orderEventProducer).send(eq(OrderEventProducer.TOPIC_CANCELLED), eq("7"),
                eventCaptor.capture());
        SpecificRecord sent = eventCaptor.getValue();
        assertThat(sent).isInstanceOf(OrderCancelledEvent.class);
        OrderCancelledEvent avro = (OrderCancelledEvent) sent;
        assertThat(avro.getOrderId().toString()).isEqualTo("7");
        assertThat(avro.getReason().toString()).isEqualTo("Customer cancelled");
        assertThat(avro.getCancelledBy().toString()).isEqualTo("user-1");
        assertThat(avro.getEventType().toString()).isEqualTo("ORDER_CANCELLED");

        assertThat(event.isPublished()).isTrue();
    }
}
