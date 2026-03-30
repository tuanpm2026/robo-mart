package com.robomart.inventory.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import com.robomart.inventory.service.DistributedLockService;

@ExtendWith(MockitoExtension.class)
@DisplayName("DistributedLockService Unit Tests")
class DistributedLockServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private DistributedLockService distributedLockService;

    private static final String LOCK_KEY = "inventory:lock:product:1";
    private static final String LOCK_VALUE = "test-lock-value";
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);

    @BeforeEach
    void setUp() {
        distributedLockService = new DistributedLockService(stringRedisTemplate);
    }

    @Test
    @DisplayName("acquireLock - should acquire lock when key does not exist")
    void shouldAcquireLockWhenKeyNotExists() {
        // given
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(LOCK_KEY, LOCK_VALUE, LOCK_TTL)).thenReturn(true);

        // when
        boolean result = distributedLockService.acquireLock(LOCK_KEY, LOCK_VALUE, LOCK_TTL);

        // then
        assertThat(result).isTrue();
        verify(valueOperations).setIfAbsent(LOCK_KEY, LOCK_VALUE, LOCK_TTL);
    }

    @Test
    @DisplayName("acquireLock - should fail to acquire lock when key already exists")
    void shouldFailToAcquireLockWhenKeyAlreadyExists() {
        // given
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(LOCK_KEY, LOCK_VALUE, LOCK_TTL)).thenReturn(false);

        // when
        boolean result = distributedLockService.acquireLock(LOCK_KEY, LOCK_VALUE, LOCK_TTL);

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("releaseLock - should release lock when lock value matches")
    @SuppressWarnings("unchecked")
    void shouldReleaseLockWhenLockValueMatches() {
        // given
        when(stringRedisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList(LOCK_KEY)), eq(LOCK_VALUE)))
                .thenReturn(1L);

        // when
        boolean result = distributedLockService.releaseLock(LOCK_KEY, LOCK_VALUE);

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("releaseLock - should not release lock when lock value differs")
    @SuppressWarnings("unchecked")
    void shouldNotReleaseLockWhenLockValueDiffers() {
        // given
        when(stringRedisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList(LOCK_KEY)), eq(LOCK_VALUE)))
                .thenReturn(0L);

        // when
        boolean result = distributedLockService.releaseLock(LOCK_KEY, LOCK_VALUE);

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("isLockHeld - should return true when lock is held with matching value")
    void shouldReturnTrueWhenLockHeld() {
        // given
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(LOCK_KEY)).thenReturn(LOCK_VALUE);

        // when
        boolean result = distributedLockService.isLockHeld(LOCK_KEY, LOCK_VALUE);

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isLockHeld - should return false when lock is held with different value")
    void shouldReturnFalseWhenLockNotHeld() {
        // given
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(LOCK_KEY)).thenReturn("different-lock-value");

        // when
        boolean result = distributedLockService.isLockHeld(LOCK_KEY, LOCK_VALUE);

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("generateLockValue - should generate unique values on each call")
    void shouldGenerateUniqueValues() {
        // when
        String value1 = distributedLockService.generateLockValue();
        String value2 = distributedLockService.generateLockValue();

        // then
        assertThat(value1).isNotNull();
        assertThat(value2).isNotNull();
        assertThat(value1).isNotEqualTo(value2);
    }
}
