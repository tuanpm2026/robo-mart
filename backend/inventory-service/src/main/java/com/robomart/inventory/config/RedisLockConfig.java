package com.robomart.inventory.config;

import java.time.Duration;

/**
 * Configuration constants for Redis-based distributed locks.
 */
public final class RedisLockConfig {

    private RedisLockConfig() {
    }

    public static final String LOCK_KEY_PREFIX = "inventory:lock:product:";
    public static final Duration DEFAULT_LOCK_TTL = Duration.ofSeconds(10);
    public static final Duration LOCK_ACQUIRE_TIMEOUT = Duration.ofSeconds(5);
}
