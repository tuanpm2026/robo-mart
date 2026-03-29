package com.robomart.cart.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "robomart.cart")
public class CartProperties {

    private int ttlMinutes = 1440; // 24 hours default

    public int getTtlMinutes() {
        return ttlMinutes;
    }

    public void setTtlMinutes(int ttlMinutes) {
        if (ttlMinutes <= 0) {
            throw new IllegalArgumentException("TTL minutes must be positive, got: " + ttlMinutes);
        }
        this.ttlMinutes = ttlMinutes;
    }

    public long getTtlSeconds() {
        return (long) ttlMinutes * 60L;
    }
}
