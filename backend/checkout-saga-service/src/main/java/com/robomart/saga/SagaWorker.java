package com.robomart.saga;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Component
public class SagaWorker {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SagaWorker.class);
    private final SagaStore store;
    private final SagaEngine engine;
    private final MeterRegistry metrics;
    private final Semaphore capacity = new Semaphore(4);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Value("${saga.worker.enabled:true}")
    private boolean enabled;

    @Value("${saga.worker.max-attempts:120}")
    private int maxAttempts;

    @Value("${saga.worker.refund-max-attempts:12}")
    private int refundMaxAttempts;

    @Value("${saga.worker.max-age:1h}")
    private Duration maxAge;

    @Value("${saga.worker.retry-delay:5s}")
    private Duration retryDelay;

    public SagaWorker(SagaStore store, SagaEngine engine, MeterRegistry metrics) {
        this.store = store;
        this.engine = engine;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${saga.worker.interval-ms:500}")
    public void poll() {
        if (!enabled) return;
        for (int i = 0; i < 4 && capacity.tryAcquire(); i++) {
            java.util.List<Work> claims;
            try {
                claims = store.claim(1, Duration.ofSeconds(30));
            } catch (RuntimeException e) {
                capacity.release();
                throw e;
            }
            if (claims.isEmpty()) {
                capacity.release();
                break;
            }
            executor.submit(
                    () -> {
                        try {
                            process(claims.getFirst());
                        } finally {
                            capacity.release();
                        }
                    });
        }
    }

    public void process(Work w) {
        boolean exhausted =
                w.attempts() > ("REFUND".equals(w.state()) ? refundMaxAttempts : maxAttempts)
                        || w.phaseStartedAt().plus(maxAge).isBefore(Instant.now());
        if (exhausted) {
            LOG.error(
                    "Saga requires review: sagaId={}, orderId={}, state={}",
                    w.id(),
                    w.snapshot().orderId(),
                    w.state());
            store.retry(w, "Recovery budget exhausted", true, retryDelay);
            metrics.counter("saga.manual.review", "state", w.state()).increment();
            return;
        }
        try {
            Transition next = engine.run(w);
            boolean advanced = store.advance(w, next);
            LOG.info(
                    "Saga step completed: sagaId={}, orderId={}, state={}, next={}, advanced={}",
                    w.id(),
                    w.snapshot().orderId(),
                    w.state(),
                    next.state(),
                    advanced);
            metrics.counter(
                            "saga.steps",
                            "state",
                            w.state(),
                            "outcome",
                            advanced ? "success" : "fenced")
                    .increment();
        } catch (RuntimeException e) {
            LOG.warn(
                    "Saga step deferred: sagaId={}, orderId={}, state={}, cause={}",
                    w.id(),
                    w.snapshot().orderId(),
                    w.state(),
                    e.toString());
            store.retry(w, e.getClass().getSimpleName() + ": " + e.getMessage(), false, retryDelay);
            metrics.counter(
                            "saga.steps",
                            "state",
                            w.state(),
                            "outcome",
                            e instanceof CallNotPermittedException ? "breaker_denied" : "retry")
                    .increment();
        }
    }

    @PreDestroy
    public void stop() {
        executor.close();
    }
}
