package com.robomart.cart.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.robomart.cart.entity.Cart;
import com.robomart.cart.entity.CartItem;
import com.robomart.cart.event.producer.CartEventProducer;
import com.robomart.cart.repository.CartRepository;
import com.robomart.cart.service.CartExpiryWarningScheduler;
import com.robomart.events.cart.CartExpiryWarningEvent;

class CartExpiryWarningSchedulerTest {
    @Test
    void globalLockPreventsConcurrentReplicaWarnings() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        CartRepository carts = mock(CartRepository.class);
        CartEventProducer producer = mock(CartEventProducer.class);
        when(redis.opsForValue().setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        new CartExpiryWarningScheduler(redis, carts, producer).scanAndWarnExpiringCarts();
        verifyNoInteractions(carts, producer);
    }

    @Test
    void retryKeepsStableIdAndOnlySuccessfulSendGetsWarnedMarker() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        CartRepository carts = mock(CartRepository.class);
        CartEventProducer producer = mock(CartEventProducer.class);
        when(redis.opsForValue().setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redis.opsForZSet().rangeByScoreWithScores(eq(CartRepository.EXPIRY_KEY), anyDouble(), anyDouble(),
                eq(0L), eq(100L))).thenReturn(Set.of(new DefaultTypedTuple<>("cart:u:user", 10000000000D)));
        when(redis.getExpire("cart:u:user", TimeUnit.SECONDS)).thenReturn(3600L);
        Cart cart = new Cart("u:user");
        cart.setUserId("user");
        cart.addItem(new CartItem(7L, "Robot", new BigDecimal("10.00"), 2));
        when(carts.findById("u:user")).thenReturn(Optional.of(cart));
        when(producer.send(anyString(), anyString(), any())).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("send failed")),
                CompletableFuture.completedFuture(null));
        var scheduler = new CartExpiryWarningScheduler(redis, carts, producer);
        ReflectionTestUtils.setField(scheduler, "checkoutBaseUrl", "http://localhost/cart");
        scheduler.scanAndWarnExpiringCarts();
        verify(redis.opsForValue(), never()).set(eq("cart:warned:cart:u:user"), anyString(), any(Duration.class));
        scheduler.scanAndWarnExpiringCarts();
        var events = ArgumentCaptor.forClass(CartExpiryWarningEvent.class);
        verify(producer, times(2)).send(anyString(), eq("user"), events.capture());
        assertThat(events.getAllValues().get(0).getEventId()).isEqualTo(events.getAllValues().get(1).getEventId());
        verify(redis.opsForValue()).set("cart:warned:cart:u:user", "10000000000", Duration.ofSeconds(3600));
    }
}
