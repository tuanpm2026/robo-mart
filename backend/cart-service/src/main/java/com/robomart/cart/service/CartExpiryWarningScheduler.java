package com.robomart.cart.service;

import java.time.Instant;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.robomart.cart.entity.Cart;
import com.robomart.cart.entity.CartKeys;
import com.robomart.cart.event.producer.CartEventProducer;
import com.robomart.cart.repository.CartRepository;
import com.robomart.events.cart.CartExpiryWarningEvent;
import com.robomart.events.cart.CartItemSummary;

@Service
public class CartExpiryWarningScheduler {

    private static final Logger log = LoggerFactory.getLogger(CartExpiryWarningScheduler.class);
    private static final long WARN_THRESHOLD_SECONDS = 7200L;  // 2 hours
    private static final String CART_KEY_PREFIX = "cart:";

    private final StringRedisTemplate stringRedisTemplate;
    private final CartRepository cartRepository;
    private final CartEventProducer cartEventProducer;

    @Value("${notification.checkout-base-url:http://localhost:5173/cart}")
    private String checkoutBaseUrl;

    public CartExpiryWarningScheduler(StringRedisTemplate stringRedisTemplate,
                                      CartRepository cartRepository,
                                      CartEventProducer cartEventProducer) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.cartRepository = cartRepository;
        this.cartEventProducer = cartEventProducer;
    }

    @Scheduled(fixedDelayString = "${robomart.cart.warning-interval:PT1M}")
    public void scanAndWarnExpiringCarts() {
        String token = UUID.randomUUID().toString();
        String lock = "cart:expiry-warning-lock";
        if (!Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(lock, token, Duration.ofMinutes(2)))) {
            return;
        }
        try {
            long now = Instant.now().getEpochSecond();
            stringRedisTemplate.opsForZSet().removeRangeByScore(CartRepository.EXPIRY_KEY, Double.NEGATIVE_INFINITY, now);
            var candidates = stringRedisTemplate.opsForZSet().rangeByScoreWithScores(
                    CartRepository.EXPIRY_KEY, now, now + WARN_THRESHOLD_SECONDS, 0, 100);
            if (candidates == null) return;
            int attempts = 0;
            for (var candidate : candidates) {
                String key = candidate.getValue();
                String retry = "cart:warning-retry:" + key;
                try {
                    if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(retry))) continue;
                    String generation = String.valueOf(candidate.getScore().longValue());
                    String marker = "cart:warned:" + key;
                    if (generation.equals(stringRedisTemplate.opsForValue().get(marker))) {
                        removeCandidate(key, generation);
                        continue;
                    }
                    Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
                    if (ttl == null || ttl <= 0 || ttl > WARN_THRESHOLD_SECONDS) continue;
                    Cart cart = cartRepository.findById(key.substring(CART_KEY_PREFIX.length())).orElse(null);
                    if (cart == null || cart.getUserId() == null || cart.getItems().isEmpty()) {
                        removeCandidate(key, generation);
                        continue;
                    }
                    if (attempts++ == 5) break;
                    publishExpiryWarning(cart, ttl, generation);
                    stringRedisTemplate.opsForValue().set(marker, generation, Duration.ofSeconds(ttl));
                    removeCandidate(key, generation);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception failure) {
                    stringRedisTemplate.opsForValue().set(retry, "retry", Duration.ofMinutes(5));
                    log.error("Cart expiry warning failed; retry retains the same event ID", failure);
                }
            }
        } finally {
            stringRedisTemplate.execute(new DefaultRedisScript<>(
                    "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) end return 0",
                    Long.class), List.of(lock), token);
        }
    }

    private void removeCandidate(String key, String generation) {
        stringRedisTemplate.execute(new DefaultRedisScript<>(
                "if tonumber(redis.call('ZSCORE',KEYS[1],ARGV[1]))==tonumber(ARGV[2]) "
                + "then return redis.call('ZREM',KEYS[1],ARGV[1]) end return 0", Long.class),
                List.of(CartRepository.EXPIRY_KEY), key, generation);
    }

    private void publishExpiryWarning(Cart cart, long ttlSeconds, String generation) throws Exception {
        String publicCartId = CartKeys.publicId(cart.getId());
        List<CartItemSummary> items = cart.getItems().stream()
                .map(item -> CartItemSummary.newBuilder()
                        .setProductId(item.getProductId())
                        .setProductName(item.getProductName())
                        .setPrice(item.getPrice().toPlainString())
                        .setQuantity(item.getQuantity())
                        .setSubtotal(item.getSubtotal().toPlainString())
                        .build())
                .toList();

        CartExpiryWarningEvent event = CartExpiryWarningEvent.newBuilder()
                .setEventId(UUID.nameUUIDFromBytes((cart.getId() + ":" + generation)
                        .getBytes(StandardCharsets.UTF_8)).toString())
                .setEventType("CART_EXPIRY_WARNING")
                .setAggregateId(publicCartId)
                .setAggregateType("Cart")
                .setTimestamp(Instant.now())
                .setVersion(1)
                .setCartId(publicCartId)
                .setUserId(cart.getUserId())
                .setExpiresInSeconds(ttlSeconds)
                .setCheckoutUrl(checkoutBaseUrl)
                .setItems(items)
                .build();

        cartEventProducer.send(CartEventProducer.TOPIC_CART_EXPIRY_WARNING, publicCartId, event)
                .get(5, TimeUnit.SECONDS);
        log.info("Published cart expiry warning, ttl={}s", ttlSeconds);
    }
}
