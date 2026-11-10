package com.robomart.notification.service;

import jakarta.annotation.PostConstruct;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Each replica reads every broadcast and serves its own connected WebSocket clients. */
@Component
public class AdminBroadcastRelay {
    private final OperationsStateStore store;
    private final SimpMessagingTemplate messaging;
    private long cursor;

    public AdminBroadcastRelay(OperationsStateStore store, SimpMessagingTemplate messaging) {
        this.store = store;
        this.messaging = messaging;
    }

    @PostConstruct
    public void initialize() { cursor = store.latestId(); }

    @Scheduled(fixedDelayString = "${notification.broadcast.interval:PT0.2S}")
    public synchronized void relay() {
        for (var event : store.after(cursor)) {
            if (event.traceId() == null) messaging.convertAndSend(event.topic(), event.payload());
            else {
                var headers = org.springframework.messaging.simp.SimpMessageHeaderAccessor.create(
                        org.springframework.messaging.simp.SimpMessageType.MESSAGE);
                headers.setNativeHeader("x-trace-id", event.traceId());
                messaging.convertAndSend(event.topic(), event.payload(), headers.getMessageHeaders());
            }
            cursor = event.id();
        }
    }

    @Scheduled(fixedDelayString = "${notification.broadcast.retention-interval:PT1M}")
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(name = "AdminBroadcastRetention", lockAtMostFor = "PT1M")
    public void cleanup() {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        for (int batch = 0; batch < 10 && System.nanoTime() < until; batch++) {
            if (store.cleanup() < 10000) break;
        }
    }
}
