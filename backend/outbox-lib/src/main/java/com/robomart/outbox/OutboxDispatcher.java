package com.robomart.outbox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import io.micrometer.core.instrument.MeterRegistry;

/** Claims aggregate heads, sends without a DB transaction, and retains uncertain leases for replay. */
public final class OutboxDispatcher<D extends ClaimedEvent> implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private final OutboxStore<D> store;
    private final Function<D, CompletableFuture<?>> publisher;
    private final OutboxSettings properties;
    private final MeterRegistry meters;
    private final Semaphore inFlight = new Semaphore(50);
    private final ExecutorService sends = ContextExecutorService.wrap(
            Executors.newVirtualThreadPerTaskExecutor(), this::captureContext);

    public OutboxDispatcher(OutboxStore<D> store, Function<D, CompletableFuture<?>> publisher,
            OutboxSettings properties, MeterRegistry meters) {
        this.store = store;
        this.publisher = publisher;
        this.properties = properties;
        this.meters = meters;
    }

    public void poll() {
        if (properties.getClaimDuration().compareTo(properties.getPublishTimeout()) <= 0) {
            throw new IllegalStateException("Outbox claim duration must exceed the publish timeout");
        }
        Instant now = Instant.now();
        List<Pending<D>> pending = new ArrayList<>();
        for (var delivery : store.claim(now, now.plus(properties.getClaimDuration()))) {
            var future = CompletableFuture.supplyAsync(() -> startSend(delivery), sends)
                    .thenCompose(result -> result);
            pending.add(new Pending<>(delivery, future));
        }
        if (pending.isEmpty()) {
            return;
        }
        try {
            CompletableFuture.allOf(pending.stream().map(Pending::future).toArray(CompletableFuture[]::new))
                    .handle((result, error) -> null).get(properties.getPublishTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            meters.counter("robomart.outbox.publish.timeout").increment();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new IllegalStateException("Outbox completion handler failed", failure);
        }
        for (var send : pending) {
            if (!send.future().isDone()) {
                // Unknown delivery: retain the lease. A stable event ID makes a later replay deduplicable.
                continue;
            }
            if (send.future().isCompletedExceptionally()) {
                store.release(send.delivery());
            } else if (store.acknowledge(send.delivery())) {
                meters.counter("robomart.outbox.published").increment();
            }
        }
    }

    private CompletableFuture<?> startSend(D delivery) {
        if (!inFlight.tryAcquire()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Outbox send limit reached"));
        }
        try {
            return publisher.apply(delivery).whenComplete((result, error) -> {
                inFlight.release();
                if (error != null) {
                    log.error("Outbox delivery failed: id={}, type={}", delivery.id(), delivery.eventType(), error);
                    meters.counter("robomart.outbox.publish.failed").increment();
                }
            });
        } catch (RuntimeException failure) {
            inFlight.release();
            log.error("Outbox event cannot be published: id={}, type={}", delivery.id(), delivery.eventType(), failure);
            meters.counter("robomart.outbox.publish.failed").increment();
            if (failure instanceof IllegalArgumentException) {
                meters.counter("robomart.outbox.unmappable").increment();
            }
            return CompletableFuture.failedFuture(failure);
        }
    }

    private ContextSnapshot captureContext() {
        ContextRegistry registry = new ContextRegistry();
        ContextRegistry.getInstance().getThreadLocalAccessors().forEach(registry::registerThreadLocalAccessor);
        registry.registerThreadLocalAccessor(new Slf4jThreadLocalAccessor("correlationId", "traceId", "spanId"));
        return ContextSnapshotFactory.builder().contextRegistry(registry).build().captureAll();
    }

    @Override
    public void close() {
        sends.shutdownNow();
    }

    private record Pending<D extends ClaimedEvent>(D delivery, CompletableFuture<?> future) { }
}
