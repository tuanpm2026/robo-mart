package com.robomart.inventory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * Service for managing distributed locks using Redis.
 *
 * Provides atomic lock acquisition, release, and verification operations.
 * Uses Redis SET NX PX for atomic lock acquisition and Lua script for atomic release.
 */
@Service
public class DistributedLockService {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockService.class);

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Lua script for atomic lock release.
     * Only deletes the key if the value matches (prevents releasing another holder's lock).
     */
    private static final String RELEASE_LOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('del', KEYS[1]) " +
            "else " +
            "    return 0 " +
            "end";

    private final RedisScript<Long> releaseLockScript;

    public DistributedLockService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.releaseLockScript = RedisScript.of(RELEASE_LOCK_SCRIPT, Long.class);
    }

    /**
     * Attempts to acquire a distributed lock.
     *
     * @param key       the lock key
     * @param lockValue unique value identifying this lock holder (use generateLockValue())
     * @param ttl       time-to-live for the lock
     * @return true if lock was acquired, false otherwise
     */
    public boolean acquireLock(String key, String lockValue, Duration ttl) {
        try {
            // SET key value NX PX milliseconds - atomic operation
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, lockValue, ttl);

            if (Boolean.TRUE.equals(acquired)) {
                log.debug("Lock acquired: key={}, value={}, ttl={}ms", key, lockValue, ttl.toMillis());
                return true;
            } else {
                log.debug("Failed to acquire lock: key={}", key);
                return false;
            }
        } catch (Exception e) {
            log.error("Error acquiring lock: key={}", key, e);
            return false;
        }
    }

    /**
     * Releases a distributed lock.
     *
     * Uses Lua script to atomically verify ownership and delete the lock.
     * Only releases if the lock value matches (prevents releasing another holder's lock).
     *
     * @param key       the lock key
     * @param lockValue the value that was used to acquire the lock
     * @return true if lock was released by this call, false if lock didn't exist or value didn't match
     */
    public boolean releaseLock(String key, String lockValue) {
        try {
            Long result = stringRedisTemplate.execute(
                    releaseLockScript,
                    Collections.singletonList(key),
                    lockValue
            );

            boolean released = result != null && result == 1L;
            if (released) {
                log.debug("Lock released: key={}, value={}", key, lockValue);
            } else {
                log.debug("Lock not released (may have expired or been taken over): key={}", key);
            }
            return released;
        } catch (Exception e) {
            log.error("Error releasing lock: key={}", key, e);
            return false;
        }
    }

    /**
     * Checks if a lock is currently held by the specified lock value.
     *
     * @param key       the lock key
     * @param lockValue the lock value to check
     * @return true if the lock exists and has the specified value
     */
    public boolean isLockHeld(String key, String lockValue) {
        try {
            String currentValue = stringRedisTemplate.opsForValue().get(key);
            return lockValue.equals(currentValue);
        } catch (Exception e) {
            log.error("Error checking lock status: key={}", key, e);
            return false;
        }
    }

    /**
     * Generates a unique lock value using UUID.
     *
     * @return a unique lock value
     */
    public String generateLockValue() {
        return UUID.randomUUID().toString();
    }
}
