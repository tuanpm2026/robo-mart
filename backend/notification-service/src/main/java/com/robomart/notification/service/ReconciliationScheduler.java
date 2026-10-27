package com.robomart.notification.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService reconciliationService;

    public ReconciliationScheduler(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(cron = "${notification.reconciliation.cron:0 0 2 * * *}")
    @SchedulerLock(name = "NotificationInventoryReconciliation", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    public void runDailyInventoryReconciliation() {
        log.info("Starting daily inventory reconciliation");
        var result = reconciliationService.runInventoryReconciliation();
        log.info("Inventory reconciliation finished: status={}, discrepancies={}", result.status(), result.discrepancies().size());
    }

    @Scheduled(cron = "${notification.reconciliation.payment-cron:0 30 2 * * *}")
    @SchedulerLock(name = "NotificationPaymentReconciliation", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    public void runDailyPaymentReconciliation() {
        log.info("Starting daily payment reconciliation");
        var result = reconciliationService.runPaymentReconciliation();
        log.info("Payment reconciliation finished: status={}, discrepancies={}", result.status(), result.discrepancies().size());
    }
}
