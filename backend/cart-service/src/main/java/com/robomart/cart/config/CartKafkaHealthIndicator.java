package com.robomart.cart.config;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** One shared admin client and a bounded probe; Kafka is required for price updates and expiry notifications. */
public class CartKafkaHealthIndicator implements HealthIndicator, AutoCloseable {
    private final Admin admin;

    public CartKafkaHealthIndicator(Admin admin) {
        this.admin = admin;
    }

    @Override
    public Health health() {
        try {
            var nodes = admin.describeCluster().nodes().get(2, TimeUnit.SECONDS);
            return (nodes.isEmpty() ? Health.down() : Health.up()).withDetail("nodes", nodes.size()).build();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Health.down(interrupted).build();
        } catch (Exception failure) {
            return Health.down(failure).build();
        }
    }

    @Override
    public void close() {
        admin.close(Duration.ofSeconds(2));
    }
}
