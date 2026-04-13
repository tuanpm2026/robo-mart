package com.robomart.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.springframework.data.domain.Pageable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.robomart.notification.entity.FailedEvent;
import com.robomart.notification.repository.FailedEventRepository;
import com.robomart.notification.service.FailedEventService;

@ExtendWith(MockitoExtension.class)
@DisplayName("FailedEventService")
class FailedEventServiceTest {

    @Mock
    private FailedEventRepository failedEventRepository;

    @InjectMocks
    private FailedEventService failedEventService;

    @Test
    @DisplayName("retryEvent marks event as RESOLVED")
    void retryEvent_marksResolved() {
        FailedEvent event = pendingEvent(1L);
        when(failedEventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(failedEventRepository.save(any())).thenReturn(event);

        boolean result = failedEventService.retryEvent(1L);

        assertThat(result).isTrue();
        assertThat(event.getStatus()).isEqualTo("RESOLVED");
        verify(failedEventRepository).save(event);
    }

    @Test
    @DisplayName("retryEvent throws IllegalStateException for non-PENDING event")
    void retryEvent_throwsForNonPending() {
        FailedEvent event = pendingEvent(2L);
        event.setStatus("RESOLVED");
        when(failedEventRepository.findById(2L)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> failedEventService.retryEvent(2L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Event already processed");
        verify(failedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("retryAll processes all PENDING events")
    void retryAll_processesAllPending() {
        FailedEvent e1 = pendingEvent(1L);
        FailedEvent e2 = pendingEvent(2L);
        when(failedEventRepository.findByStatus(eq("PENDING"), any(Pageable.class))).thenReturn(List.of(e1, e2));
        when(failedEventRepository.saveAll(anyList())).thenReturn(List.of(e1, e2));

        failedEventService.retryAll();

        assertThat(e1.getStatus()).isEqualTo("RESOLVED");
        assertThat(e2.getStatus()).isEqualTo("RESOLVED");
    }

    @Test
    @DisplayName("retryAll returns correct count of processed events")
    void retryAll_returnsCorrectCount() {
        FailedEvent e1 = pendingEvent(1L);
        FailedEvent e2 = pendingEvent(2L);
        FailedEvent e3 = pendingEvent(3L);
        when(failedEventRepository.findByStatus(eq("PENDING"), any(Pageable.class))).thenReturn(List.of(e1, e2, e3));
        when(failedEventRepository.saveAll(anyList())).thenReturn(List.of(e1, e2, e3));

        int count = failedEventService.retryAll();

        assertThat(count).isEqualTo(3);
    }

    private FailedEvent pendingEvent(Long id) {
        FailedEvent event = new FailedEvent();
        event.setEventType("test.topic");
        event.setOriginalTopic("test.topic");
        event.setStatus("PENDING");
        return event;
    }
}
