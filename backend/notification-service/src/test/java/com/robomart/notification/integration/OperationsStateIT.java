package com.robomart.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import com.robomart.notification.service.AdminBroadcastRelay;
import com.robomart.notification.service.OperationsStateStore;
import com.robomart.notification.web.ReconciliationResult;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.listener.auto-startup=false", "notification.scheduling.enabled=false"})
@ActiveProfiles("test")
@Import(NotificationMaintenanceIT.Database.class)
class OperationsStateIT {
    @Autowired private OperationsStateStore store;
    @Autowired private SimpMessagingTemplate broker;

    @Test
    void everyReplicaReceivesBroadcastAndDoesNotReplayItOnNextPoll() {
        var first = mock(SimpMessagingTemplate.class);
        var second = mock(SimpMessagingTemplate.class);
        var a = new AdminBroadcastRelay(store, first);
        var b = new AdminBroadcastRelay(store, second);
        a.initialize(); b.initialize();
        store.publish("/topic/orders", Map.of("orderId", "42", "status", "CANCELLED"));
        var event = store.after(store.latestId() - 1).getFirst();
        var wire = broker.getMessageConverter().toMessage(event.payload(), new org.springframework.messaging.MessageHeaders(Map.of()));
        assertThat(wire).isNotNull();
        var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree((byte[]) wire.getPayload());
        assertThat(json.get("orderId").asText()).isEqualTo("42");
        a.relay(); b.relay(); a.relay(); b.relay();
        verify(first, times(1)).convertAndSend(eq("/topic/orders"), any(Object.class));
        verify(second, times(1)).convertAndSend(eq("/topic/orders"), any(Object.class));
    }

    @Test
    void sharedResultsSurviveReaderRestartAndOlderRunsCannotOverwriteNewerResults() {
        var current = new ReconciliationResult("PAYMENT", List.of(), false, Instant.now());
        store.save(current);
        store.save(new ReconciliationResult("PAYMENT", List.of(), true, current.checkedAt().minusSeconds(5)));
        assertThat(store.load("PAYMENT", null)).isEqualTo(current);
    }
    @Test
    void traceHeaderSurvivesTheSharedBroadcastEnvelope() {
        var messaging = mock(SimpMessagingTemplate.class);
        var relay = new AdminBroadcastRelay(store, messaging);
        relay.initialize();
        store.publish("/topic/orders", Map.of("orderId", "trace-order"), "trace-123");
        relay.relay();
        var headers = org.mockito.ArgumentCaptor.forClass(org.springframework.messaging.MessageHeaders.class);
        verify(messaging).convertAndSend(eq("/topic/orders"), any(Object.class), headers.capture());
        assertThat(headers.getValue().get("nativeHeaders")).isEqualTo(Map.of("x-trace-id", List.of("trace-123")));
    }

}
