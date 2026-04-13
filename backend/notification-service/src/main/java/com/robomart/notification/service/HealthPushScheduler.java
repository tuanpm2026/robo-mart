package com.robomart.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.robomart.notification.web.SystemHealthResponse;

@Component
public class HealthPushScheduler {

    private static final Logger log = LoggerFactory.getLogger(HealthPushScheduler.class);

    private final HealthAggregatorService healthAggregatorService;
    private final AdminPushService adminPushService;

    public HealthPushScheduler(HealthAggregatorService healthAggregatorService,
                               AdminPushService adminPushService) {
        this.healthAggregatorService = healthAggregatorService;
        this.adminPushService = adminPushService;
    }

    @Scheduled(fixedDelay = 10000)
    public void pushHealthUpdate() {
        try {
            SystemHealthResponse health = healthAggregatorService.aggregateHealth();
            adminPushService.pushSystemHealth(health);
        } catch (Exception e) {
            log.warn("Health push failed: {}", e.getMessage());
        }
    }
}
