package com.robomart.product.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.robomart.product.entity.OutboxEvent;
import com.robomart.product.repository.OutboxEventRepository;
import com.robomart.product.service.OutboxPublisher;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @InjectMocks
    private OutboxPublisher outboxPublisher;

    @Test
    void shouldSaveEventWithCorrectFields() {
        when(outboxEventRepository.save(any(OutboxEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        String payload = "{\"id\":1,\"name\":\"Test Product\"}";
        OutboxEvent result = outboxPublisher.saveEvent("PRODUCT", "1", "PRODUCT_CREATED", payload);

        assertThat(result.getAggregateType()).isEqualTo("PRODUCT");
        assertThat(result.getAggregateId()).isEqualTo("1");
        assertThat(result.getEventType()).isEqualTo("PRODUCT_CREATED");
        assertThat(result.getPayload()).isEqualTo(payload);
        assertThat(result.isPublished()).isFalse();
        assertThat(result.getCreatedAt()).isNotNull();

        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    void shouldPersistEventViaRepository() {
        when(outboxEventRepository.save(any(OutboxEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        outboxPublisher.saveEvent("PRODUCT", "42", "PRODUCT_UPDATED", "{}");

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());

        OutboxEvent saved = captor.getValue();
        assertThat(saved.getAggregateId()).isEqualTo("42");
        assertThat(saved.getEventType()).isEqualTo("PRODUCT_UPDATED");
    }
}
