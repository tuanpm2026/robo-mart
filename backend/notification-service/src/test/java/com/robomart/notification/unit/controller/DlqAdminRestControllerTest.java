package com.robomart.notification.unit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.robomart.notification.controller.DlqAdminRestController;
import com.robomart.notification.entity.FailedEvent;
import com.robomart.notification.service.FailedEventService;

import io.micrometer.tracing.Tracer;

@ExtendWith(MockitoExtension.class)
class DlqAdminRestControllerTest {

    @Mock
    private FailedEventService failedEventService;

    @Mock
    private Tracer tracer;

    private DlqAdminRestController controller;

    @BeforeEach
    void setUp() {
        controller = new DlqAdminRestController(failedEventService, tracer);
        when(tracer.currentSpan()).thenReturn(null);
    }

    @Test
    void shouldReturnPagedEventsOnListEvents() {
        FailedEvent event = buildFailedEvent(1L);
        Page<FailedEvent> page = new PageImpl<>(List.of(event), PageRequest.of(0, 25), 1);
        when(failedEventService.listEvents(0, 25)).thenReturn(page);

        ResponseEntity<?> response = controller.listEvents(0, 25);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void shouldReturnBadRequestWhenPageIsNegative() {
        ResponseEntity<?> response = controller.listEvents(-1, 25);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldReturnBadRequestWhenSizeIsZero() {
        ResponseEntity<?> response = controller.listEvents(0, 0);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldCapSizeAt200() {
        Page<FailedEvent> page = new PageImpl<>(List.of(), PageRequest.of(0, 200), 0);
        when(failedEventService.listEvents(0, 200)).thenReturn(page);

        ResponseEntity<?> response = controller.listEvents(0, 999);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(failedEventService).listEvents(0, 200);
    }

    @Test
    void shouldReturnOkOnSuccessfulRetrySingle() {
        when(failedEventService.retryEvent(1L)).thenReturn(true);

        ResponseEntity<?> response = controller.retrySingle(1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldReturn404WhenEventNotFound() {
        when(failedEventService.retryEvent(99L)).thenThrow(new NoSuchElementException("Event not found: 99"));

        ResponseEntity<?> response = controller.retrySingle(99L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void shouldReturn400WhenEventAlreadyProcessed() {
        when(failedEventService.retryEvent(5L)).thenThrow(new IllegalStateException("Event already processed"));

        ResponseEntity<?> response = controller.retrySingle(5L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldReturnCountOnRetryAll() {
        when(failedEventService.retryAll()).thenReturn(3);

        ResponseEntity<?> response = controller.retryAll();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(failedEventService).retryAll();
    }

    private FailedEvent buildFailedEvent(Long id) {
        FailedEvent event = new FailedEvent();
        event.setEventType("order.order.status-changed");
        event.setAggregateId("order-1");
        event.setOriginalTopic("order.order.status-changed");
        event.setRetryCount(0);
        event.setStatus("PENDING");
        return event;
    }
}
