package com.robomart.product.unit.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

import com.robomart.product.repository.OutboxEventRepository;
import com.robomart.product.service.OutboxCleanupService;

@ExtendWith(MockitoExtension.class)
class OutboxCleanupServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @InjectMocks
    private OutboxCleanupService outboxCleanupService;

    @Test
    void shouldDeletePublishedEventsOlderThan7Days() {
        when(outboxEventRepository.deleteByPublishedTrueAndPublishedAtBefore(any(Instant.class)))
                .thenReturn(5);

        outboxCleanupService.cleanupOldEvents();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(outboxEventRepository).deleteByPublishedTrueAndPublishedAtBefore(cutoffCaptor.capture());

        Instant cutoff = cutoffCaptor.getValue();
        Instant sevenDaysAgo = Instant.now().minusSeconds(7 * 24 * 60 * 60);
        assertThat(cutoff).isBetween(sevenDaysAgo.minusSeconds(5), sevenDaysAgo.plusSeconds(5));
    }

    @Test
    void shouldHandleZeroDeletedEvents() {
        when(outboxEventRepository.deleteByPublishedTrueAndPublishedAtBefore(any(Instant.class)))
                .thenReturn(0);

        outboxCleanupService.cleanupOldEvents();

        verify(outboxEventRepository).deleteByPublishedTrueAndPublishedAtBefore(any(Instant.class));
    }
}
