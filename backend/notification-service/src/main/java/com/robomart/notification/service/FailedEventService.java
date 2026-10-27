package com.robomart.notification.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.annotation.PreDestroy;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.robomart.notification.kafka.DlqRecordCodec;
import com.robomart.notification.config.DlqProperties;
import com.robomart.notification.repository.FailedEventRepository;
import com.robomart.notification.dto.FailedEventSummary;
import com.robomart.notification.service.DlqRecordStore.ReplayClaim;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;

/** One replay path for individual and batch operations; database claims never span broker work. */
@Service
public class FailedEventService {
    private static final Logger LOG = LoggerFactory.getLogger(FailedEventService.class);
    private final FailedEventRepository events;
    private final DlqRecordStore store;
    private final DlqRecordCodec codec;
    private final KafkaTemplate<byte[], byte[]> kafka;
    private final DlqProperties properties;
    private final Semaphore inFlight = new Semaphore(10);
    private final ExecutorService executor = ContextExecutorService.wrap(
            Executors.newVirtualThreadPerTaskExecutor(), this::captureContext);

    public FailedEventService(FailedEventRepository events, DlqRecordStore store, DlqRecordCodec codec,
            KafkaTemplate<byte[], byte[]> kafka, DlqProperties properties) {
        this.events = events;
        this.store = store;
        this.codec = codec;
        this.kafka = kafka;
        this.properties = properties;
    }

    public boolean retryEvent(Long id) {
        CompletableFuture<Boolean> acknowledged = publish(id);
        try {
            return acknowledged.get(properties.getReplayTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException uncertain) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new ReplayUnavailableException("Replay not confirmed; original record retained", failure.getCause());
        }
    }

    private CompletableFuture<Boolean> publish(Long id) {
        if (!inFlight.tryAcquire()) throw new IllegalStateException("Replay capacity is busy; retry later");
        ReplayClaim claim = null;
        try {
            claim = store.claim(id);
            var headers = codec.replayHeaders(claim.headers());
            Long timestamp = claim.timestamp() == null || claim.timestamp() < 0 ? null : claim.timestamp();
            var record = new ProducerRecord<>(claim.topic(), claim.partition(), timestamp,
                    claim.key(), claim.value(), headers);
            ReplayClaim acquired = claim;
            ContextSnapshot context = captureContext();
            return kafka.send(record).handleAsync((result, failure) -> {
                try (var scope = context.setThreadLocals()) {
                    if (failure != null) {
                        store.release(acquired.id(), acquired.token());
                        throw new ReplayUnavailableException("Kafka replay failed; record retained", failure);
                    }
                    if (!store.acknowledge(acquired.id(), acquired.token())) {
                        throw new ReplayUnavailableException("Replay lease changed; inspect its current state", null);
                    }
                    return true;
                } finally {
                    inFlight.release();
                }
            }, executor);
        } catch (RuntimeException failure) {
            inFlight.release();
            if (claim != null) store.release(claim.id(), claim.token());
            throw failure;
        }
    }

    public Page<FailedEventSummary> listEvents(int page, int size) {
        return events.findSummariesByStatusNot("REPLAYED", PageRequest.of(page, size, Sort.by("firstFailedAt").descending()));
    }

    public int retryAll() {
        List<CompletableFuture<Boolean>> attempts = new ArrayList<>();
        for (Long id : events.findReplayableIds(Instant.now(), PageRequest.of(0, 25))) {
            attempts.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return retryEvent(id);
                } catch (RuntimeException failed) {
                    LOG.error("DLQ replay not confirmed: failedEventId={}", id, failed);
                    return false;
                }
            }, executor));
        }
        try {
            CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)).get(properties.getReplayTimeout().plusSeconds(2).toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException uncertain) {
            LOG.warn("DLQ replay batch acknowledgement pending; claims retain their original record");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException failed) {
            LOG.error("DLQ replay batch failed", failed);
        }
        return (int) attempts.stream().filter(future -> future.isDone() && !future.isCompletedExceptionally()
                && Boolean.TRUE.equals(future.getNow(false))).count();
    }

    private ContextSnapshot captureContext() {
        ContextRegistry registry = new ContextRegistry();
        ContextRegistry.getInstance().getThreadLocalAccessors().forEach(registry::registerThreadLocalAccessor);
        registry.registerThreadLocalAccessor(new Slf4jThreadLocalAccessor("correlationId", "traceId", "spanId"));
        return ContextSnapshotFactory.builder().contextRegistry(registry).build().captureAll();
    }

    @PreDestroy
    public void shutdown() { executor.shutdownNow(); }

    public static class ReplayUnavailableException extends RuntimeException {
        public ReplayUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
