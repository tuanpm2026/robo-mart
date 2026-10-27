package com.robomart.product.unit.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import com.robomart.product.config.OutboxProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

import com.robomart.product.service.ProductOutboxStore;
import com.robomart.product.service.OutboxCleanupService;

@ExtendWith(MockitoExtension.class)
class OutboxCleanupServiceTest {

    @Mock
    private ProductOutboxStore store;

    private OutboxCleanupService outboxCleanupService;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setup() {
        meters = new SimpleMeterRegistry();
        outboxCleanupService = new OutboxCleanupService(store, new OutboxProperties(), meters);
    }

    @AfterEach
    void cleanup() { meters.close(); }

    @Test
    void shouldDeletePublishedEventsOlderThan7Days() {
        when(store.deletePublishedBefore(any(Instant.class)))
                .thenReturn(5);

        outboxCleanupService.cleanupOldEvents();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(store).deletePublishedBefore(cutoffCaptor.capture());

        Instant cutoff = cutoffCaptor.getValue();
        Instant sevenDaysAgo = Instant.now().minusSeconds(7 * 24 * 60 * 60);
        assertThat(cutoff).isBetween(sevenDaysAgo.minusSeconds(5), sevenDaysAgo.plusSeconds(5));
    }

    @Test
    void shouldHandleZeroDeletedEvents() {
        when(store.deletePublishedBefore(any(Instant.class)))
                .thenReturn(0);

        outboxCleanupService.cleanupOldEvents();

        verify(store).deletePublishedBefore(any(Instant.class));
    }
}
