package com.robomart.saga.transport;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.StatusRuntimeException;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/** A denied call remains unresolved; the durable worker owns retries and recovery budgets. */
@Component
public class ParticipantCircuitBreakers {
    private final CircuitBreakerRegistry breakers;

    public ParticipantCircuitBreakers() {
        this(CircuitBreakerRegistry.of(defaultConfig()));
    }

    ParticipantCircuitBreakers(CircuitBreakerRegistry breakers) {
        this.breakers = breakers;
    }

    static CircuitBreakerConfig defaultConfig() {
        return CircuitBreakerConfig.custom()
                .slidingWindowSize(5)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(100)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(1)
                .ignoreException(failure -> !isTransportFailure(failure))
                .build();
    }

    private static boolean isTransportFailure(Throwable failure) {
        if (!(failure instanceof StatusRuntimeException grpc)) return false;
        return switch (grpc.getStatus().getCode()) {
            case UNAVAILABLE,
                    RESOURCE_EXHAUSTED,
                    ABORTED,
                    DEADLINE_EXCEEDED,
                    INTERNAL,
                    UNKNOWN,
                    CANCELLED,
                    DATA_LOSS ->
                    true;
            default -> false;
        };
    }

    public <T> T call(String participant, Supplier<T> operation) {
        return breakers.circuitBreaker(participant).executeSupplier(operation);
    }
}
