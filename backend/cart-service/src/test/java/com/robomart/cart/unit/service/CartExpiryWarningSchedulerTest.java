package com.robomart.cart.unit.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.robomart.cart.entity.Cart;
import com.robomart.cart.entity.CartItem;
import com.robomart.cart.event.producer.CartEventProducer;
import com.robomart.cart.repository.CartRepository;
import com.robomart.cart.service.CartExpiryWarningScheduler;
import com.robomart.events.cart.CartExpiryWarningEvent;

@ExtendWith(MockitoExtension.class)
class CartExpiryWarningSchedulerTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CartEventProducer cartEventProducer;

    @Mock
    private Cursor<String> cursor;

    private CartExpiryWarningScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new CartExpiryWarningScheduler(stringRedisTemplate, cartRepository, cartEventProducer);
        ReflectionTestUtils.setField(scheduler, "checkoutBaseUrl", "http://localhost:5173/cart");
    }

    private Cart buildCart(String cartId, String userId, boolean hasItems) {
        Cart cart = new Cart(cartId);
        cart.setUserId(userId);
        if (hasItems) {
            CartItem item = new CartItem(1L, "Test Product", new BigDecimal("19.99"), 2);
            List<CartItem> items = new ArrayList<>();
            items.add(item);
            cart.setItems(items);
        }
        return cart;
    }

    @SuppressWarnings("unchecked")
    private void setupCursorWithKey(String key) {
        when(stringRedisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(0);
            consumer.accept(key);
            return null;
        }).when(cursor).forEachRemaining(any(Consumer.class));
    }

    @Test
    void shouldPublishEventForCartWithTtlWithinThreshold() {
        String cartId = "cart-123";
        String key = "cart:" + cartId;

        setupCursorWithKey(key);
        when(stringRedisTemplate.getExpire(key, TimeUnit.SECONDS)).thenReturn(3600L);
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(buildCart(cartId, "user-456", true)));

        scheduler.scanAndWarnExpiringCarts();

        verify(cartEventProducer).send(eq(CartEventProducer.TOPIC_CART_EXPIRY_WARNING), eq(cartId),
                any(CartExpiryWarningEvent.class));
    }

    @Test
    void shouldSkipCartWithTtlAboveThreshold() {
        String key = "cart:cart-999";

        setupCursorWithKey(key);
        when(stringRedisTemplate.getExpire(key, TimeUnit.SECONDS)).thenReturn(10000L);

        scheduler.scanAndWarnExpiringCarts();

        verify(cartEventProducer, never()).send(anyString(), anyString(), any());
    }

    @Test
    void shouldSkipCartWithNullUserId() {
        String cartId = "cart-no-user";
        String key = "cart:" + cartId;

        setupCursorWithKey(key);
        when(stringRedisTemplate.getExpire(key, TimeUnit.SECONDS)).thenReturn(3600L);
        Cart cart = buildCart(cartId, null, true);
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(cart));

        scheduler.scanAndWarnExpiringCarts();

        verify(cartEventProducer, never()).send(anyString(), anyString(), any());
    }

    @Test
    void shouldSkipCartWithEmptyItems() {
        String cartId = "cart-empty";
        String key = "cart:" + cartId;

        setupCursorWithKey(key);
        when(stringRedisTemplate.getExpire(key, TimeUnit.SECONDS)).thenReturn(3600L);
        Cart cart = buildCart(cartId, "user-456", false);
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(cart));

        scheduler.scanAndWarnExpiringCarts();

        verify(cartEventProducer, never()).send(anyString(), anyString(), any());
    }

    @Test
    void shouldNotPropagateRedisScanException() {
        when(stringRedisTemplate.scan(any(ScanOptions.class))).thenThrow(new RuntimeException("Redis connection refused"));

        // Should not throw — exception is caught and logged
        scheduler.scanAndWarnExpiringCarts();

        verify(cartEventProducer, never()).send(anyString(), anyString(), any());
    }

    @Test
    void shouldSkipCartWithNonPositiveTtl() {
        String key = "cart:cart-expired";

        setupCursorWithKey(key);
        when(stringRedisTemplate.getExpire(key, TimeUnit.SECONDS)).thenReturn(-1L);

        scheduler.scanAndWarnExpiringCarts();

        verify(cartEventProducer, never()).send(anyString(), anyString(), any());
    }
}
