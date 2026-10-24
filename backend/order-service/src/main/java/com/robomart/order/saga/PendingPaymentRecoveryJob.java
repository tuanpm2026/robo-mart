package com.robomart.order.saga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.robomart.order.config.SagaProperties;
import com.robomart.order.service.PendingPaymentRecoveryService;

@Component
@ConditionalOnProperty(prefix = "saga.payment-recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PendingPaymentRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(PendingPaymentRecoveryJob.class);

    private final PendingPaymentRecoveryService recovery;
    private final SagaProperties properties;

    public PendingPaymentRecoveryJob(PendingPaymentRecoveryService recovery, SagaProperties properties) {
        this.recovery = recovery;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${saga.payment-recovery.check-interval-ms:5000}",
            initialDelayString = "${saga.payment-recovery.initial-delay-ms:5000}")
    public void resumePendingPayments() {
        for (int i = 0; i < properties.getPaymentRecovery().getBatchSize(); i++) {
            try {
                if (!recovery.resumeNextPayment()) {
                    return;
                }
            } catch (RuntimeException failure) {
                // Database failure rolls back this order. Retry next scan using the same key.
                log.error("Pending payment recovery scan failed", failure);
                return;
            }
        }
    }
}
