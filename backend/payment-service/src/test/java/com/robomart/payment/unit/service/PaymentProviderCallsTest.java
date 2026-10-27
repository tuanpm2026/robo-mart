package com.robomart.payment.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.resilience.InvocationRejectedException;

import com.robomart.payment.config.PaymentResilienceConfig;
import com.robomart.payment.service.GatewayResult;
import com.robomart.payment.service.PaymentProviderCalls;

class PaymentProviderCallsTest {
    @Test
    @Timeout(10)
    void limitsAllProviderOperationsTogetherAndReleasesPermitsAfterFailure() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(
                PaymentResilienceConfig.class, PaymentProviderCalls.class);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = context.getBean(PaymentProviderCalls.class);
            var entered = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            java.util.function.Supplier<GatewayResult> provider = () -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Provider not released");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
                return new GatewayResult("provider-receipt", "COMPLETED");
            };
            var charge = executor.submit(() -> calls.execute(provider));
            var refund = executor.submit(() -> calls.execute(provider));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> calls.execute(provider)).isInstanceOf(InvocationRejectedException.class);
            } finally {
                release.countDown();
            }
            assertThat(charge.get(5, TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
            assertThat(refund.get(5, TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
            assertThatThrownBy(() -> calls.execute(() -> { throw new IllegalStateException("Provider failed"); }))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Provider failed");
            assertThat(calls.execute(() -> new GatewayResult("after-failure", "COMPLETED")).transactionId())
                    .isEqualTo("after-failure");
        }
    }
}
