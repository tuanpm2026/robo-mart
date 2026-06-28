package com.robomart.product.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.robomart.product.entity.ProcessedEvent;
import com.robomart.product.repository.ProcessedEventRepository;
import com.robomart.product.service.ProcessedEventService;

@ExtendWith(MockitoExtension.class)
class ProcessedEventServiceTest {

    private static final String GROUP = "product-service-product-index-group";

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @InjectMocks
    private ProcessedEventService processedEventService;

    @Test
    void isProcessedReturnsRepositoryResult() {
        when(processedEventRepository.existsByConsumerGroupAndEventId(GROUP, "evt-1")).thenReturn(true);

        assertThat(processedEventService.isProcessed(GROUP, "evt-1")).isTrue();
    }

    @Test
    void isProcessedReturnsFalseForBlankEventIdWithoutHittingRepository() {
        assertThat(processedEventService.isProcessed(GROUP, "  ")).isFalse();
        assertThat(processedEventService.isProcessed(GROUP, null)).isFalse();

        verifyNoInteractions(processedEventRepository);
    }

    @Test
    void markProcessedPersistsRecord() {
        processedEventService.markProcessed(GROUP, "evt-1");

        verify(processedEventRepository).saveAndFlush(any(ProcessedEvent.class));
    }

    @Test
    void markProcessedSwallowsDuplicateInsert() {
        when(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        // Concurrent duplicate must not propagate
        processedEventService.markProcessed(GROUP, "evt-1");
    }

    @Test
    void markProcessedSkipsBlankEventId() {
        processedEventService.markProcessed(GROUP, "");

        verify(processedEventRepository, never()).saveAndFlush(any());
    }
}
