package com.robomart.inventory.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "robomart.inventory")
public class InventoryProperties {
    private Duration reservationTtl = Duration.ofHours(24);
    private boolean expiryEnabled = true;

    public Duration getReservationTtl() {
        return reservationTtl;
    }

    public void setReservationTtl(Duration reservationTtl) {
        if (reservationTtl == null || reservationTtl.toMillis() < 1000) {
            throw new IllegalArgumentException("Reservation TTL must be at least one second");
        }
        this.reservationTtl = reservationTtl;
    }

    public boolean isExpiryEnabled() {
        return expiryEnabled;
    }

    public void setExpiryEnabled(boolean expiryEnabled) {
        this.expiryEnabled = expiryEnabled;
    }
}
