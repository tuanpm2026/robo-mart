package com.robomart.order.unit.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;

import com.robomart.order.grpc.InventoryBusinessException;
import com.robomart.order.grpc.InventoryGrpcClient;
import com.robomart.order.grpc.InventoryServiceUnavailableException;
import com.robomart.order.grpc.PaymentBusinessException;
import com.robomart.order.grpc.PaymentGrpcClient;
import com.robomart.order.grpc.PaymentServiceUnavailableException;
import com.robomart.proto.inventory.InventoryServiceGrpc;
import com.robomart.proto.inventory.ReserveInventoryRequest;
import com.robomart.proto.payment.PaymentServiceGrpc;
import com.robomart.proto.payment.ProcessPaymentRequest;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import io.grpc.Status;

/** Verifies the real annotation aspects and application configuration without external services. */
class GrpcClientResilienceTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    CircuitBreakerAutoConfiguration.class, RetryAutoConfiguration.class))
            .withUserConfiguration(ClientConfiguration.class);

    @Test
    void businessRejectionsBypassRetriesAndCircuitBreakerFailureCounting() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            InventoryGrpcClient inventoryClient = context.getBean(InventoryGrpcClient.class);
            PaymentGrpcClient paymentClient = context.getBean(PaymentGrpcClient.class);
            assertThat(AopUtils.isAopProxy(inventoryClient)).isTrue();
            assertThat(AopUtils.isAopProxy(paymentClient)).isTrue();

            var inventoryStub = context.getBean(InventoryServiceGrpc.InventoryServiceBlockingStub.class);
            var paymentStub = context.getBean(PaymentServiceGrpc.PaymentServiceBlockingStub.class);
            when(inventoryStub.reserveInventory(any())).thenThrow(
                    Status.FAILED_PRECONDITION.withDescription("Insufficient stock").asRuntimeException());
            when(paymentStub.processPayment(any())).thenThrow(
                    Status.FAILED_PRECONDITION.withDescription("Payment declined").asRuntimeException());

            assertThatThrownBy(() -> inventoryClient.reserveInventory(ReserveInventoryRequest.getDefaultInstance()))
                    .isInstanceOf(InventoryBusinessException.class).hasMessage("Insufficient stock");
            assertThatThrownBy(() -> paymentClient.processPayment(ProcessPaymentRequest.getDefaultInstance()))
                    .isInstanceOf(PaymentBusinessException.class).hasMessage("Payment declined");
            verify(inventoryStub).reserveInventory(any());
            verify(paymentStub).processPayment(any());
            CircuitBreakerRegistry registry = context.getBean(CircuitBreakerRegistry.class);
            RetryRegistry retries = context.getBean(RetryRegistry.class);
            for (String name : new String[] {"inventory-service", "payment-service"}) {
                CircuitBreaker breaker = registry.circuitBreaker(name);
                assertThat(retries.retry(name).getRetryConfig().getMaxAttempts()).isEqualTo(3);
                assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
                assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
                assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
            }
        });
    }

    @Test
    void inventoryOutageInvokesFallbackAndOpensConfiguredCircuitBreaker() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var stub = context.getBean(InventoryServiceGrpc.InventoryServiceBlockingStub.class);
            when(stub.reserveInventory(any())).thenThrow(Status.UNAVAILABLE.asRuntimeException());
            InventoryGrpcClient client = context.getBean(InventoryGrpcClient.class);
            CircuitBreaker breaker = context.getBean(CircuitBreakerRegistry.class)
                    .circuitBreaker("inventory-service");

            assertOutageOpensBreaker(
                    () -> client.reserveInventory(ReserveInventoryRequest.getDefaultInstance()),
                    InventoryServiceUnavailableException.class, breaker);
            verify(stub, times(5)).reserveInventory(any());
        });
    }

    @Test
    void paymentOutageInvokesFallbackAndOpensConfiguredCircuitBreaker() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var stub = context.getBean(PaymentServiceGrpc.PaymentServiceBlockingStub.class);
            when(stub.processPayment(any())).thenThrow(Status.DEADLINE_EXCEEDED.asRuntimeException());
            PaymentGrpcClient client = context.getBean(PaymentGrpcClient.class);
            CircuitBreaker breaker = context.getBean(CircuitBreakerRegistry.class)
                    .circuitBreaker("payment-service");

            assertOutageOpensBreaker(
                    () -> client.processPayment(ProcessPaymentRequest.getDefaultInstance()),
                    PaymentServiceUnavailableException.class, breaker);
            verify(stub, times(5)).processPayment(any());
        });
    }

    private static void assertOutageOpensBreaker(Runnable call,
            Class<? extends RuntimeException> fallbackException, CircuitBreaker breaker) {
        // Preserve the documented RETRY-NOOP behavior during the dependency upgrade:
        // the inner circuit-breaker fallback translates the first transient gRPC failure,
        // so the outer Retry does not see a configured retryable exception.
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(call::run).isInstanceOf(fallbackException);
        }
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(5);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(call::run).isInstanceOf(fallbackException)
                .hasCauseInstanceOf(CallNotPermittedException.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    @Import({InventoryGrpcClient.class, PaymentGrpcClient.class})
    static class ClientConfiguration {

        @Bean
        InventoryServiceGrpc.InventoryServiceBlockingStub inventoryStub() {
            return mock(InventoryServiceGrpc.InventoryServiceBlockingStub.class);
        }

        @Bean
        PaymentServiceGrpc.PaymentServiceBlockingStub paymentStub() {
            return mock(PaymentServiceGrpc.PaymentServiceBlockingStub.class);
        }
    }
}
