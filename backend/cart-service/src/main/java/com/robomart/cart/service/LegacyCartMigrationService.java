package com.robomart.cart.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.robomart.cart.repository.CartRepository;

/** Drains the old repository index in bounded batches; each conversion is an atomic Lua operation. */
@Service
public class LegacyCartMigrationService {
    private static final Logger LOG = LoggerFactory.getLogger(LegacyCartMigrationService.class);
    private final StringRedisTemplate redis;
    private final CartRepository carts;

    public LegacyCartMigrationService(StringRedisTemplate redis, CartRepository carts) {
        this.redis = redis;
        this.carts = carts;
    }

    @Scheduled(fixedDelayString = "${robomart.cart.migration-interval:PT1M}", initialDelayString = "PT5S")
    public void migrateBatch() {
        try (var members = redis.opsForSet().scan("cart", ScanOptions.scanOptions().count(100).build())) {
            for (int count = 0; count < 100 && members.hasNext(); count++) {
                String id = members.next();
                try {
                    carts.findById(id);
                    // Missing/converted hashes no longer need the obsolete repository membership.
                    redis.opsForSet().remove("cart", id);
                } catch (Exception failure) {
                    LOG.error("Legacy cart conversion failed; original hash retained for operator review", failure);
                }
            }
        }
    }
}
