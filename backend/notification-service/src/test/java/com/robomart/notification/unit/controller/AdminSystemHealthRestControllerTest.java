package com.robomart.notification.unit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.robomart.notification.controller.AdminSystemHealthRestController;
import com.robomart.notification.service.HealthAggregatorService;
import com.robomart.notification.web.SystemHealthResponse;

import io.micrometer.tracing.Tracer;

@ExtendWith(MockitoExtension.class)
class AdminSystemHealthRestControllerTest {

    @Mock
    private HealthAggregatorService healthAggregatorService;

    @Mock
    private Tracer tracer;

    private AdminSystemHealthRestController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminSystemHealthRestController(healthAggregatorService, tracer);
        when(tracer.currentSpan()).thenReturn(null);
    }

    @Test
    void shouldReturnCachedHealthWhenAvailable() {
        SystemHealthResponse cached = new SystemHealthResponse(List.of(), Instant.now());
        when(healthAggregatorService.getLatestHealth()).thenReturn(cached);

        ResponseEntity<?> response = controller.getSystemHealth();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void shouldAggregateHealthWhenCacheIsEmpty() {
        SystemHealthResponse fresh = new SystemHealthResponse(List.of(), Instant.now());
        when(healthAggregatorService.getLatestHealth()).thenReturn(null);
        when(healthAggregatorService.aggregateHealth()).thenReturn(fresh);

        ResponseEntity<?> response = controller.getSystemHealth();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(healthAggregatorService).aggregateHealth();
    }
}
