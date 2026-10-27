package com.robomart.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.robomart.notification.config.DlqProperties;
import com.robomart.notification.kafka.DlqRecordCodec;
import com.robomart.notification.repository.FailedEventRepository;
import com.robomart.notification.service.DlqRecordStore;
import com.robomart.notification.service.DlqRecordStore.ReplayClaim;
import com.robomart.notification.service.FailedEventService;

import tools.jackson.databind.json.JsonMapper;

class FailedEventServiceTest {
    private final FailedEventRepository events = mock(FailedEventRepository.class);
    private final DlqRecordStore store = mock(DlqRecordStore.class);
    @SuppressWarnings("unchecked") private final KafkaTemplate<byte[], byte[]> kafka = mock(KafkaTemplate.class);
    private final DlqProperties properties = new DlqProperties();
    private final FailedEventService service = new FailedEventService(events, store,
            new DlqRecordCodec(JsonMapper.builder().build()), kafka, properties);
    private final UUID token = UUID.randomUUID();
    private final ReplayClaim claim = new ReplayClaim(1L, token, "source.topic", 2, 1234L,
            new byte[]{(byte) 255}, new byte[]{0, 1, 2}, "[{\"name\":\"x-correlation-id\",\"value\":\"YWJj\"}]");

    @AfterEach
    void stop() { service.shutdown(); MDC.clear(); }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void replaysOriginalBytesPartitionTimestampAndHeadersThenAcknowledges() {
        when(store.claim(1L)).thenReturn(claim);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        when(store.acknowledge(1L, token)).thenReturn(true);
        assertThat(service.retryEvent(1L)).isTrue();
        ArgumentCaptor<ProducerRecord<byte[], byte[]>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("source.topic");
        assertThat(sent.getValue().partition()).isEqualTo(2);
        assertThat(sent.getValue().timestamp()).isEqualTo(1234L);
        assertThat(sent.getValue().key()).isEqualTo(claim.key());
        assertThat(sent.getValue().value()).isEqualTo(claim.value());
        assertThat(sent.getValue().headers().lastHeader("x-correlation-id").value()).isEqualTo("abc".getBytes());
        verify(store).acknowledge(1L, token);
        verify(store, never()).release(anyLong(), any());
    }

    @Test
    void unknownOrLegacyRecordDoesNotPublish() {
        when(store.claim(1L)).thenThrow(new NoSuchElementException("missing"));
        assertThatThrownBy(() -> service.retryEvent(1L)).isInstanceOf(NoSuchElementException.class);
        when(store.claim(2L)).thenThrow(new IllegalStateException("original bytes unavailable"));
        assertThatThrownBy(() -> service.retryEvent(2L)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(kafka);
    }

    @Test
    @SuppressWarnings("unchecked")
    void knownBrokerFailureReleasesClaimButDoesNotMarkReplayed() {
        when(store.claim(1L)).thenReturn(claim);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker")));
        assertThatThrownBy(() -> service.retryEvent(1L)).isInstanceOf(FailedEventService.ReplayUnavailableException.class);
        verify(store).release(1L, token);
        verify(store, never()).acknowledge(anyLong(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void timeoutRetainsClaimAndLateAcknowledgementRestoresCallerContext() throws Exception {
        properties.setReplayTimeout(Duration.ofMillis(20));
        when(store.claim(1L)).thenReturn(claim);
        CompletableFuture<SendResult<byte[], byte[]>> send = new CompletableFuture<>();
        when(kafka.send(any(ProducerRecord.class))).thenReturn(send);
        MDC.put("correlationId", "caller-correlation");
        when(store.acknowledge(1L, token)).thenAnswer(invocation -> {
            assertThat(MDC.get("correlationId")).isEqualTo("caller-correlation");
            return true;
        });
        assertThat(service.retryEvent(1L)).isFalse();
        verify(store, never()).release(anyLong(), any());
        MDC.clear();
        CompletableFuture.runAsync(() -> send.complete(null)).get();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> verify(store).acknowledge(1L, token));
    }

    @Test
    @SuppressWarnings("unchecked")
    void retryAllUsesSameClaimPublishAcknowledgementPath() {
        when(events.findReplayableIds(any(), any())).thenReturn(List.of(1L));
        when(store.claim(1L)).thenReturn(claim);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        when(store.acknowledge(1L, token)).thenReturn(true);
        assertThat(service.retryAll()).isEqualTo(1);
        verify(store).claim(1L);
        verify(store).acknowledge(1L, token);
    }
}
