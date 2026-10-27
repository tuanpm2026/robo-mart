package com.robomart.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OutboxDispatcherTest {
    record Delivery(long id, String eventType, UUID claimToken) implements ClaimedEvent { }

    @SuppressWarnings("unchecked")
    private final OutboxStore<Delivery> store = mock(OutboxStore.class);
    private final OutboxSettings settings = new OutboxSettings();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private Delivery delivery(long id) { return new Delivery(id, "known", UUID.randomUUID()); }

    @Test
    void acknowledgesOnlyConfirmedSendsAndReleasesKnownFailures() {
        var ok = delivery(1);
        var failed = delivery(2);
        when(store.claim(any(), any())).thenReturn(List.of(ok, failed));
        when(store.acknowledge(ok)).thenReturn(true);
        try (var publisher = new OutboxDispatcher<>(store, item -> item.id() == 1
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.failedFuture(new IllegalStateException("broker rejected")), settings, meters)) {
            publisher.poll();
        }
        verify(store).acknowledge(ok);
        verify(store).release(failed);
        verify(store, never()).acknowledge(failed);
        assertThat(meters.counter("robomart.outbox.published").count()).isEqualTo(1);
        assertThat(meters.counter("robomart.outbox.publish.failed").count()).isEqualTo(1);
    }

    @Test
    void unknownMappingDoesNotAcknowledgeAndDoesNotBlockIndependentSuccess() {
        var corrupt = delivery(1);
        var ok = delivery(2);
        when(store.claim(any(), any())).thenReturn(List.of(corrupt, ok));
        when(store.acknowledge(ok)).thenReturn(false); // Another owner already reclaimed this fence.
        try (var publisher = new OutboxDispatcher<>(store, item -> {
            if (item.id() == 1) throw new IllegalArgumentException("unknown event type");
            return CompletableFuture.completedFuture(null);
        }, settings, meters)) {
            publisher.poll();
        }
        verify(store).release(corrupt);
        verify(store).acknowledge(ok);
        verify(store, never()).acknowledge(corrupt);
        assertThat(meters.counter("robomart.outbox.unmappable").count()).isEqualTo(1);
        assertThat(meters.counter("robomart.outbox.published").count()).isZero();
    }

    @Test
    void uncertainTimeoutRetainsLeaseEvenIfTheSendLaterCompletes() {
        var pending = delivery(1);
        when(store.claim(any(), any())).thenReturn(List.of(pending));
        settings.setPublishTimeout(Duration.ofMillis(30));
        var send = new CompletableFuture<Void>();
        try (var publisher = new OutboxDispatcher<>(store, item -> send, settings, meters)) {
            publisher.poll();
            send.complete(null);
        }
        verify(store, never()).release(any());
        verify(store, never()).acknowledge(any());
        assertThat(meters.counter("robomart.outbox.publish.timeout").count()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidLeaseBudgetBeforeClaimingAndHandlesAnEmptyPoll() {
        settings.setClaimDuration(Duration.ofSeconds(1));
        try (var publisher = new OutboxDispatcher<>(store, item -> CompletableFuture.completedFuture(null), settings, meters)) {
            assertThatThrownBy(publisher::poll).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(store);
            settings.setPublishTimeout(Duration.ofMillis(100));
            when(store.claim(any(), any())).thenReturn(List.of());
            publisher.poll();
        }
        verify(store, never()).acknowledge(any());
    }

    @Test
    void capturesAndClearsTaskMdcAcrossConsecutivePolls() {
        var first = delivery(1);
        var second = delivery(2);
        when(store.claim(any(), any())).thenReturn(List.of(first), List.of(second));
        var values = new java.util.concurrent.ConcurrentHashMap<Long, String>();
        try (var publisher = new OutboxDispatcher<>(store, item -> {
            values.put(item.id(), String.valueOf(MDC.get("correlationId")));
            return CompletableFuture.completedFuture(null);
        }, settings, meters)) {
            MDC.put("correlationId", "first-context");
            publisher.poll();
            assertThat(MDC.get("correlationId")).isEqualTo("first-context");
            MDC.remove("correlationId");
            publisher.poll();
            assertThat(values).containsEntry(1L, "first-context").containsEntry(2L, "null");
        } finally {
            MDC.remove("correlationId");
        }
    }

    @Test
    void boundsUnresolvedSendsAcrossPollsAndReusesReleasedPermits() throws Exception {
        settings.setPublishTimeout(Duration.ofMillis(30));
        var first = LongStream.rangeClosed(1, 50).mapToObj(this::delivery).toList();
        var rejected = delivery(51);
        var later = delivery(52);
        when(store.claim(any(), any())).thenReturn(first, List.of(rejected), List.of(later));
        var started = new CountDownLatch(50);
        var pending = new java.util.concurrent.ConcurrentHashMap<Long, CompletableFuture<Void>>();
        try (var publisher = new OutboxDispatcher<>(store, item -> {
            if (item.id() == 52) return CompletableFuture.completedFuture(null);
            var future = new CompletableFuture<Void>();
            pending.put(item.id(), future);
            started.countDown();
            return future;
        }, settings, meters)) {
            publisher.poll();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            settings.setPublishTimeout(Duration.ofSeconds(2));
            publisher.poll();
            verify(store).release(rejected);
            assertThat(pending).hasSize(50);
            pending.values().forEach(future -> future.complete(null));
            publisher.poll();
            verify(store).acknowledge(later);
        }
    }

    @Test
    void interruptionKeepsTheUnknownLeaseAndRestoresInterruptStatus() throws Exception {
        var item = delivery(1);
        when(store.claim(any(), any())).thenReturn(List.of(item));
        var started = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        try (var publisher = new OutboxDispatcher<>(store, delivery -> {
            started.countDown();
            return new CompletableFuture<>();
        }, settings, meters)) {
            var thread = Thread.ofPlatform().start(() -> {
                try {
                    publisher.poll();
                    interrupted.set(Thread.currentThread().isInterrupted());
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            thread.interrupt();
            thread.join(2000);
            assertThat(thread.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(interrupted).isTrue();
        }
        verify(store, never()).release(any());
        verify(store, never()).acknowledge(any());
    }
}
