package com.robomart.saga;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.robomart.common.saga.CheckoutSnapshot;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

class SagaWorkerTest {
    SagaStore store = mock(SagaStore.class);
    SagaEngine engine = mock(SagaEngine.class);
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    SagaWorker worker = new SagaWorker(store, engine, metrics);

    @BeforeEach
    void configure() {
        ReflectionTestUtils.setField(worker, "maxAttempts", 120);
        ReflectionTestUtils.setField(worker, "refundMaxAttempts", 12);
        ReflectionTestUtils.setField(worker, "maxAge", Duration.ofHours(1));
        ReflectionTestUtils.setField(worker, "retryDelay", Duration.ofSeconds(5));
    }

    @AfterEach
    void close() {
        worker.stop();
        metrics.close();
    }

    Work work(String state, int attempts, Instant started) {
        UUID id = UUID.randomUUID();
        return new Work(
                id,
                new CheckoutSnapshot(
                        "1",
                        id.toString(),
                        1,
                        "user",
                        "1.00",
                        "USD",
                        1,
                        List.of(new CheckoutSnapshot.Line("1", 1))),
                state,
                false,
                "",
                "",
                "",
                "",
                attempts,
                started,
                UUID.randomUUID());
    }

    @Test
    void expiredBudgetEscalatesWithoutParticipantEffects() {
        Work old = work("CHARGE", 1, Instant.now().minusSeconds(3601));
        worker.process(old);
        Work refund = work("REFUND", 13, Instant.now());
        worker.process(refund);
        verifyNoInteractions(engine);
        verify(store).retry(eq(old), anyString(), eq(true), any());
        verify(store).retry(eq(refund), anyString(), eq(true), any());
    }

    @Test
    void unknownOutcomeRetriesWithoutChangingState() {
        Work w = work("CHARGE", 1, Instant.now());
        when(engine.run(w)).thenThrow(new IllegalStateException("unknown"));
        worker.process(w);
        verify(store).retry(eq(w), contains("unknown"), eq(false), eq(Duration.ofSeconds(5)));
        verify(store, never()).advance(any(), any());
    }

    @Test
    void openBreakerDefersTheOriginalPhaseWithoutCompensationOrBudgetReset() {
        Work w = work("CHARGE", 17, Instant.now().minusSeconds(120));
        var failure =
                CallNotPermittedException.createCallNotPermittedException(
                        CircuitBreaker.ofDefaults("payment-service"));
        when(engine.run(w)).thenThrow(failure);
        worker.process(w);
        verify(store)
                .retry(
                        eq(w),
                        contains("CallNotPermittedException"),
                        eq(false),
                        eq(Duration.ofSeconds(5)));
        verify(store, never()).advance(any(), any());
        assertThat(
                        metrics.counter(
                                        "saga.steps",
                                        "state",
                                        "CHARGE",
                                        "outcome",
                                        "breaker_denied")
                                .count())
                .isEqualTo(1);
    }

    @Test
    void schedulerRunsClaimedWorkAndCanBeDisabled() {
        worker.poll();
        verifyNoInteractions(store);
        Work w = work("RESERVE", 1, Instant.now());
        when(store.claim(anyInt(), any())).thenReturn(List.of(w), List.of());
        when(engine.run(w)).thenReturn(Transition.next(w, "CHARGE"));
        ReflectionTestUtils.setField(worker, "enabled", true);
        worker.poll();
        verify(store, timeout(3000)).advance(eq(w), argThat(t -> t.state().equals("CHARGE")));
    }

    @Test
    void failedClaimDoesNotLeakWorkerCapacity() {
        ReflectionTestUtils.setField(worker, "enabled", true);
        when(store.claim(anyInt(), any())).thenThrow(new IllegalStateException("db down"));
        for (int i = 0; i < 6; i++) assertThatThrownBy(worker::poll).hasMessage("db down");
    }
}
