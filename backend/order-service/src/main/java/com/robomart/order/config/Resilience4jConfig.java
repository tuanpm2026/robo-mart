package com.robomart.order.config;

import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PostConstruct;

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

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;

    public Resilience4jConfig(CircuitBreakerRegistry circuitBreakerRegistry, RetryRegistry retryRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry = retryRegistry;
    }

    private static boolean isBusinessError(Throwable e) {
        return e instanceof StatusRuntimeException sre
                && sre.getStatus().getCode() == Status.Code.FAILED_PRECONDITION;
    }

    @PostConstruct
    public void configureIgnoreExceptions() {
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig
                .from(circuitBreakerRegistry.getDefaultConfig())
                .ignoreException(Resilience4jConfig::isBusinessError)
                .build();
        circuitBreakerRegistry.addConfiguration("inventory-service", cbConfig);
        circuitBreakerRegistry.addConfiguration("payment-service", cbConfig);

        RetryConfig retryConfig = RetryConfig
                .from(retryRegistry.getDefaultConfig())
                .retryOnException(e -> !isBusinessError(e))
                .build();
        retryRegistry.addConfiguration("inventory-service", retryConfig);
        retryRegistry.addConfiguration("payment-service", retryConfig);
    }
}
