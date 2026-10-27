package com.robomart.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OutboxSettingsTest {
    @Test
    void validatesAllDurationBoundsAndPreservesConfiguredValues() {
        var settings = new OutboxSettings();
        assertThat(settings.getPublishTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.getClaimDuration()).isEqualTo(Duration.ofMinutes(2));
        assertThat(settings.getRetention()).isEqualTo(Duration.ofDays(7));
        for (Duration invalid : new Duration[]{null, Duration.ZERO, Duration.ofNanos(999), Duration.ofSeconds(-1)}) {
            assertThatThrownBy(() -> settings.setPublishTimeout(invalid)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> settings.setClaimDuration(invalid)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> settings.setRetention(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        settings.setPublishTimeout(Duration.ofSeconds(3));
        settings.setClaimDuration(Duration.ofSeconds(4));
        settings.setRetention(Duration.ofDays(2));
        assertThat(settings.getPublishTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(settings.getClaimDuration()).isEqualTo(Duration.ofSeconds(4));
        assertThat(settings.getRetention()).isEqualTo(Duration.ofDays(2));
    }

    @Test
    void preservesLegacyRowIdentityAndDoesNotMintAnIdForAnUnpersistedEvent() {
        String old = UUID.nameUUIDFromBytes("order-outbox-42".getBytes(StandardCharsets.UTF_8)).toString();
        assertThat(StableEventIds.fromRow("order-outbox-", 42)).isEqualTo(old);
        assertThat(StableEventIds.fromRow("order-outbox-", 42)).isEqualTo(old);
        assertThat(StableEventIds.fromRow("other-outbox-", 42)).isNotEqualTo(old);
        assertThatThrownBy(() -> StableEventIds.fromRow("", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StableEventIds.fromRow(null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StableEventIds.fromRow("order-outbox-", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countsOnlyTheStoreConfirmedRetentionDeletion() {
        var store = mock(OutboxStore.class);
        var meters = new SimpleMeterRegistry();
        when(store.deletePublishedBefore(any())).thenReturn(3);
        OutboxRetention.cleanup(store, new OutboxSettings(), meters);
        verify(store).deletePublishedBefore(any());
        assertThat(meters.counter("robomart.outbox.retention.deleted").count()).isEqualTo(3);
    }
}
