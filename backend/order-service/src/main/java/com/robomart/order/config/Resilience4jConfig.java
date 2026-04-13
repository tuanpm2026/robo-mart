package com.robomart.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.circuitbreaker.configure.CircuitBreakerConfigCustomizer;
import io.github.resilience4j.retry.configure.RetryConfigCustomizer;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * Resilience4j customizers for gRPC circuit breakers and retry instances.
 *
 * <p>These customizers are applied on top of the YAML configuration in application.yml.
 * They exclude FAILED_PRECONDITION (business errors such as insufficient stock and payment
 * declined) from both retry and circuit-breaker failure counting. Business rejections are
 * deterministic — retrying them wastes time and produces wrong saga compensation behaviour
 * (shouldCompensate=false vs true). Transient errors (UNAVAILABLE, DEADLINE_EXCEEDED) are
 * still retried and counted by the circuit breaker.
 */
@Configuration
public class Resilience4jConfig {

    private static boolean isBusinessError(Throwable e) {
        return e instanceof StatusRuntimeException sre
                && sre.getStatus().getCode() == Status.Code.FAILED_PRECONDITION;
    }

    // ── Inventory Service ────────────────────────────────────────────────────

    @Bean
    public CircuitBreakerConfigCustomizer inventoryCircuitBreakerCustomizer() {
        return CircuitBreakerConfigCustomizer.of("inventory-service",
                builder -> builder.ignoreException(Resilience4jConfig::isBusinessError));
    }

    @Bean
    public RetryConfigCustomizer inventoryRetryCustomizer() {
        return RetryConfigCustomizer.of("inventory-service",
                builder -> builder.ignoreException(Resilience4jConfig::isBusinessError));
    }

    // ── Payment Service ──────────────────────────────────────────────────────

    @Bean
    public CircuitBreakerConfigCustomizer paymentCircuitBreakerCustomizer() {
        return CircuitBreakerConfigCustomizer.of("payment-service",
                builder -> builder.ignoreException(Resilience4jConfig::isBusinessError));
    }

    @Bean
    public RetryConfigCustomizer paymentRetryCustomizer() {
        return RetryConfigCustomizer.of("payment-service",
                builder -> builder.ignoreException(Resilience4jConfig::isBusinessError));
    }
}
