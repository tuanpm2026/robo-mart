package com.robomart.cart.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.robomart.cart.entity.Cart;
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

    @Scheduled(fixedDelay = 1800000)  // every 30 minutes
    public void scanAndWarnExpiringCarts() {
        log.info("Scanning for expiring carts...");
        ScanOptions options = ScanOptions.scanOptions().match(CART_KEY_PREFIX + "*").count(100).build();
        try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
            cursor.forEachRemaining(key -> {
                try {
                    processCartKey(key);
                } catch (Exception e) {
                    log.error("Error processing cart key={}: {}", key, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("Failed to scan cart keys", e);
        }
    }

    private void processCartKey(String key) {
        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (ttl == null || ttl <= 0 || ttl > WARN_THRESHOLD_SECONDS) {
            return;
        }
        String cartId = key.substring(CART_KEY_PREFIX.length());
        if (cartId.isEmpty()) return;

        Cart cart = cartRepository.findById(cartId).orElse(null);
        if (cart == null || cart.getUserId() == null || cart.getItems().isEmpty()) {
            return;
        }
        publishExpiryWarning(cart, ttl);
    }

    private void publishExpiryWarning(Cart cart, long ttlSeconds) {
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
                .setEventId(UUID.randomUUID().toString())
                .setEventType("CART_EXPIRY_WARNING")
                .setAggregateId(cart.getId())
                .setAggregateType("Cart")
                .setTimestamp(Instant.now())
                .setVersion(1)
                .setCartId(cart.getId())
                .setUserId(cart.getUserId())
                .setExpiresInSeconds(ttlSeconds)
                .setCheckoutUrl(checkoutBaseUrl)
                .setItems(items)
                .build();

        cartEventProducer.send(CartEventProducer.TOPIC_CART_EXPIRY_WARNING, cart.getId(), event);
        log.info("Published cart expiry warning: cartId={}, userId={}, ttl={}s",
                cart.getId(), cart.getUserId(), ttlSeconds);
    }
}
