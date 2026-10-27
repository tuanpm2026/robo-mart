package com.robomart.cart.unit.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.robomart.cart.repository.CartRepository;
import com.robomart.cart.service.LegacyCartMigrationService;

class LegacyCartMigrationServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void removesStaleMembershipButRetainsMalformedHashForReviewAndClosesCursor() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        CartRepository carts = mock(CartRepository.class);
        Cursor<String> cursor = mock(Cursor.class);
        when(redis.opsForSet().scan(eq("cart"), any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn("missing", "malformed");
        when(carts.findById("missing")).thenReturn(Optional.empty());
        when(carts.findById("malformed")).thenThrow(new IllegalStateException("malformed"));
        new LegacyCartMigrationService(redis, carts).migrateBatch();
        verify(redis.opsForSet()).remove("cart", "missing");
        verify(redis.opsForSet(), never()).remove("cart", "malformed");
        verify(cursor).close();
    }
}
