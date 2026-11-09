package com.robomart.saga.transport;

import static org.assertj.core.api.Assertions.*;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.Status;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

class ParticipantCircuitBreakersTest {
    final CircuitBreakerRegistry registry =
            CircuitBreakerRegistry.of(ParticipantCircuitBreakers.defaultConfig());
    final ParticipantCircuitBreakers calls = new ParticipantCircuitBreakers(registry);

    @Test
    void transportFailuresOpenOnlyTheirParticipantAndRecoveryRequiresAProbe() {
        var sent = new AtomicInteger();
        for (int i = 0; i < 5; i++)
            assertThatThrownBy(
                            () ->
                                    calls.call(
                                            "payment-service",
                                            () -> {
                                                sent.incrementAndGet();
                                                throw Status.UNAVAILABLE.asRuntimeException();
                                            }))
                    .isInstanceOf(io.grpc.StatusRuntimeException.class);
        assertThatThrownBy(() -> calls.call("payment-service", () -> sent.incrementAndGet()))
                .isInstanceOf(CallNotPermittedException.class);
        assertThat(sent).hasValue(5);
        assertThat(calls.call("inventory-service", () -> "healthy")).isEqualTo("healthy");
        var payment = registry.circuitBreaker("payment-service");
        assertThat(payment.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        payment.transitionToHalfOpenState();
        assertThat(calls.call("payment-service", () -> "receipt")).isEqualTo("receipt");
        assertThat(payment.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void businessRejectionsAndIdentityErrorsNeverBecomeServiceOutages() {
        for (var status :
                new Status[] {
                    Status.FAILED_PRECONDITION,
                    Status.NOT_FOUND,
                    Status.INVALID_ARGUMENT,
                    Status.ALREADY_EXISTS,
                    Status.PERMISSION_DENIED,
                    Status.UNAUTHENTICATED,
                    Status.OUT_OF_RANGE,
                    Status.UNIMPLEMENTED
                }) {
            for (int i = 0; i < 6; i++)
                assertThatThrownBy(
                                () ->
                                        calls.call(
                                                "inventory-service",
                                                () -> {
                                                    throw status.asRuntimeException();
                                                }))
                        .isInstanceOf(io.grpc.StatusRuntimeException.class);
        }
        assertThatThrownBy(
                        () ->
                                calls.call(
                                        "inventory-service",
                                        () -> {
                                            throw new IllegalStateException("token unavailable");
                                        }))
                .hasMessage("token unavailable");
        assertThat(registry.circuitBreaker("inventory-service").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(calls.call("inventory-service", () -> "rejected business call recovered"))
                .isEqualTo("rejected business call recovered");
    }

    @Test
    void deadlineAndResourceFailuresCountTowardTheSameBoundedOutage() {
        for (var status :
                new Status[] {
                    Status.DEADLINE_EXCEEDED,
                    Status.RESOURCE_EXHAUSTED,
                    Status.ABORTED,
                    Status.INTERNAL,
                    Status.UNKNOWN
                })
            assertThatThrownBy(
                            () ->
                                    calls.call(
                                            "order-service",
                                            () -> {
                                                throw status.asRuntimeException();
                                            }))
                    .isInstanceOf(io.grpc.StatusRuntimeException.class);
        assertThat(registry.circuitBreaker("order-service").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
    }
}
