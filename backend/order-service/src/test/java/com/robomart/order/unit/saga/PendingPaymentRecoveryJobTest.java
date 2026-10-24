package com.robomart.order.unit.saga;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.robomart.order.config.SagaProperties;
import com.robomart.order.saga.PendingPaymentRecoveryJob;
import com.robomart.order.service.PendingPaymentRecoveryService;

class PendingPaymentRecoveryJobTest {

    @Test
    void stopsWhenThereAreNoDueOrders() {
        var recovery = mock(PendingPaymentRecoveryService.class);
        new PendingPaymentRecoveryJob(recovery, new SagaProperties()).resumePendingPayments();
        verify(recovery).resumeNextPayment();
    }

    @Test
    void boundsWorkPerScan() {
        var recovery = mock(PendingPaymentRecoveryService.class);
        when(recovery.resumeNextPayment()).thenReturn(true);
        var properties = new SagaProperties();
        properties.getPaymentRecovery().setBatchSize(3);
        new PendingPaymentRecoveryJob(recovery, properties).resumePendingPayments();
        verify(recovery, times(3)).resumeNextPayment();
    }

    @Test
    void databaseFailureEndsScanInsteadOfSpinningOnTheSameOrder() {
        var recovery = mock(PendingPaymentRecoveryService.class);
        when(recovery.resumeNextPayment()).thenThrow(new IllegalStateException("Database unavailable"));
        new PendingPaymentRecoveryJob(recovery, new SagaProperties()).resumePendingPayments();
        verify(recovery).resumeNextPayment();
    }
}
