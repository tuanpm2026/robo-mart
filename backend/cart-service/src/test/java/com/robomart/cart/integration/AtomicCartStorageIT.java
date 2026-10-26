package com.robomart.cart.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.convert.MappingRedisConverter;
import org.springframework.data.redis.core.convert.RedisData;
import org.springframework.data.redis.core.mapping.RedisMappingContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.robomart.cart.config.CartProperties;
import com.robomart.cart.entity.Cart;
import com.robomart.cart.entity.CartItem;
import com.robomart.cart.event.producer.CartEventProducer;
import com.robomart.cart.exception.CartItemNotFoundException;
import com.robomart.cart.exception.CartNotFoundException;
import com.robomart.cart.repository.CartRepository;
import com.robomart.cart.service.CartExpiryWarningScheduler;
import com.robomart.events.cart.CartExpiryWarningEvent;

import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class AtomicCartStorageIT {
    @Container private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);
    private static LettuceConnectionFactory connection;
    private static StringRedisTemplate redis;
    private static CartRepository carts;

    @BeforeAll
    static void connect() {
        connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connection.afterPropertiesSet();
        connection.start();
        redis = new StringRedisTemplate(connection);
        carts = new CartRepository(redis, JsonMapper.builder().build(), new CartProperties());
    }

    @BeforeEach
    void clear() {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) c -> {
            c.serverCommands().flushDb();
            return null;
        });
    }

    @AfterAll
    static void close() {
        connection.destroy();
    }

    private String id() { return "u:" + UUID.randomUUID(); }
    private CartItem item(long product, int quantity) {
        return new CartItem(product, "Robot " + product, new BigDecimal("12.50"), quantity);
    }

    @Test
    void concurrentAddsAndReadsNeverLoseUnitsAndQuantityCapIsAtomic() throws Exception {
        String id = id();
        carts.add(id, item(1, 1), "user");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 80; i++) {
                tasks.add(pool.submit(() -> { start.await(); carts.add(id, item(1, 1), "user"); return null; }));
                tasks.add(pool.submit(() -> { start.await(); carts.getAndRefresh(id); return null; }));
            }
            start.countDown();
            for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
        }
        assertThat(carts.getAndRefresh(id).findItem(1L).orElseThrow().getQuantity()).isEqualTo(81);
        carts.add(id, item(1, 999), "user");
        assertThat(carts.getAndRefresh(id).getTotalItems()).isEqualTo(999);
    }

    @Test
    void readsRefreshExpiryWithoutRewritingHashOrUpdatedTimestamp() {
        String id = id();
        carts.add(id, item(2, 2), "user");
        String key = CartRepository.key(id);
        var before = redis.opsForHash().entries(key);
        redis.expire(key, Duration.ofSeconds(10));
        Cart read = carts.getAndRefresh(id);
        assertThat(read.getTotalPrice()).isEqualByComparingTo("25.00");
        assertThat(redis.opsForHash().entries(key)).isEqualTo(before);
        assertThat(redis.getExpire(key)).isGreaterThan(86000);
        assertThat(redis.opsForZSet().score(CartRepository.EXPIRY_KEY, key)).isNotNull();
    }

    @Test
    void parallelGuestMergeAndUserUpdatesTransferEachLineOnce() throws Exception {
        String guest = "g:" + UUID.randomUUID();
        String user = id();
        carts.add(guest, item(3, 10), null);
        carts.add(user, item(3, 1), "user");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                tasks.add(pool.submit(() -> carts.merge(guest, user, "user")));
                tasks.add(pool.submit(() -> carts.add(user, item(3, 1), "user")));
            }
            for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
        }
        assertThat(carts.getAndRefresh(user).getTotalItems()).isEqualTo(36);
        assertThat(carts.findById(guest)).isEmpty();
        assertThat(redis.opsForZSet().score("cart:product:3", CartRepository.key(guest))).isNull();
        assertThat(redis.opsForZSet().score("cart:product:3", CartRepository.key(user))).isNotNull();
    }

    @Test
    void priceUpdatesPreserveConcurrentQuantitiesAndRejectOlderSnapshots() throws Exception {
        String id = id();
        carts.add(id, item(4, 1), "user");
        carts.add(id, item(5, 2), "user");
        Instant update = Instant.now();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> price = pool.submit(() -> carts.updateProduct(4, "New", new BigDecimal("22.50"), update));
            Future<?> quantity = pool.submit(() -> carts.setQuantity(id, 4L, 7));
            price.get(10, TimeUnit.SECONDS);
            quantity.get(10, TimeUnit.SECONDS);
        }
        carts.updateProduct(4, "Stale", BigDecimal.ONE, update.minusSeconds(1));
        Cart result = carts.getAndRefresh(id);
        assertThat(result.findItem(4L).orElseThrow().getQuantity()).isEqualTo(7);
        assertThat(result.findItem(4L).orElseThrow().getPrice()).isEqualByComparingTo("22.50");
        assertThat(result.findItem(4L).orElseThrow().getProductName()).isEqualTo("New");
        assertThat(result.findItem(5L).orElseThrow().getPrice()).isEqualByComparingTo("12.50");
    }

    @Test
    void removesOnlyRequestedLineAndReportsMissingCartOrItem() {
        String id = id();
        assertThatThrownBy(() -> carts.getAndRefresh(id)).isInstanceOf(CartNotFoundException.class);
        carts.add(id, item(6, 2), "user");
        assertThatThrownBy(() -> carts.setQuantity(id, 7L, 3)).isInstanceOf(CartItemNotFoundException.class);
        carts.remove(id, 6L);
        assertThat(carts.getAndRefresh(id).getItems()).isEmpty();
        assertThat(redis.opsForZSet().score("cart:product:6", CartRepository.key(id))).isNull();
    }

    @Test
    void migratesActualSpringDataHashWithoutLosingLinesMetadataOrTtl() {
        String id = id();
        Cart legacy = new Cart(id);
        legacy.setUserId("legacy-user");
        legacy.addItem(item(8, 4));
        var converter = new MappingRedisConverter(new RedisMappingContext());
        converter.afterPropertiesSet();
        RedisData data = new RedisData();
        converter.write(legacy, data);
        String key = CartRepository.key(id);
        data.getBucket().asMap().forEach((field, value) -> redis.opsForHash().put(key, field,
                new String(value, StandardCharsets.UTF_8)));
        redis.expire(key, Duration.ofSeconds(120));
        redis.opsForSet().add("cart", id);
        new com.robomart.cart.service.LegacyCartMigrationService(redis, carts).migrateBatch();
        Cart restored = carts.findById(id).orElseThrow();
        assertThat(restored.getUserId()).isEqualTo("legacy-user");
        assertThat(restored.getCreatedAt()).isEqualTo(legacy.getCreatedAt());
        assertThat(restored.getTotalItems()).isEqualTo(4);
        assertThat(restored.getTotalPrice()).isEqualByComparingTo("50.00");
        assertThat(redis.getExpire(key)).isBetween(100L, 120L);
        assertThat(redis.opsForHash().hasKey(key, "_class")).isFalse();
        assertThat(redis.opsForSet().isMember("cart", id)).isFalse();
        assertThat(redis.opsForZSet().score("cart:product:8", key)).isNotNull();
        carts.add(id, item(8, 1), "legacy-user");
        assertThat(carts.getAndRefresh(id).getTotalItems()).isEqualTo(5);
    }

    @Test
    void priceEventBeforeLegacyBackfillIsAppliedDuringConversion() {
        String id = id();
        String key = CartRepository.key(id);
        redis.opsForHash().putAll(key, java.util.Map.of("_class", Cart.class.getName(), "userId", "old-user",
                "items.[0].productId", "8", "items.[0].productName", "Old", "items.[0].price", "12.50",
                "items.[0].quantity", "4"));
        redis.expire(key, Duration.ofMinutes(30));
        redis.opsForSet().add("cart", id);
        carts.updateProduct(8, "New", new BigDecimal("20.00"), Instant.now());
        new com.robomart.cart.service.LegacyCartMigrationService(redis, carts).migrateBatch();
        Cart converted = carts.findById(id).orElseThrow();
        assertThat(converted.findItem(8L).orElseThrow().getPrice()).isEqualByComparingTo("20.00");
        assertThat(converted.getTotalItems()).isEqualTo(4);
    }

    @Test
    void expiryWarningIsOncePerLifetimeAndFailureReplaysStableEvent() {
        String id = id();
        carts.add(id, item(9, 1), "user");
        String key = CartRepository.key(id);
        redis.expire(key, Duration.ofSeconds(3600));
        redis.opsForZSet().add(CartRepository.EXPIRY_KEY, key, Instant.now().plusSeconds(3600).getEpochSecond());
        CartEventProducer producer = mock(CartEventProducer.class);
        when(producer.send(anyString(), anyString(), any())).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("uncertain send")),
                CompletableFuture.completedFuture(null));
        var job = new CartExpiryWarningScheduler(redis, carts, producer);
        ReflectionTestUtils.setField(job, "checkoutBaseUrl", "http://localhost/cart");
        job.scanAndWarnExpiringCarts();
        assertThat(redis.opsForValue().get("cart:warned:" + key)).isNull();
        job.scanAndWarnExpiringCarts(); // cooldown prevents immediate duplicate attempts
        verify(producer).send(anyString(), anyString(), any());
        redis.delete("cart:warning-retry:" + key);
        job.scanAndWarnExpiringCarts();
        job.scanAndWarnExpiringCarts();
        var events = ArgumentCaptor.forClass(CartExpiryWarningEvent.class);
        verify(producer, times(2)).send(anyString(), anyString(), events.capture());
        assertThat(events.getAllValues().get(0).getEventId()).isEqualTo(events.getAllValues().get(1).getEventId());
        assertThat(redis.opsForValue().get("cart:warned:" + key)).isNotNull();
        assertThat(redis.opsForZSet().score(CartRepository.EXPIRY_KEY, key)).isNull();
    }
}
