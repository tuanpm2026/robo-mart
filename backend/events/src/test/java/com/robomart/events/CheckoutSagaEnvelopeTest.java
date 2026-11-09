package com.robomart.events;

import static org.assertj.core.api.Assertions.*;

import com.robomart.events.order.CheckoutSagaRequestedEvent;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CheckoutSagaEnvelopeTest {
    @ParameterizedTest
    @ValueSource(strings = {"START", "CANCEL"})
    void sameEnvelopeRoundTripsBothRequestsWithoutRoundingAmounts(String type) throws Exception {
        var input =
                CheckoutSagaRequestedEvent.newBuilder()
                        .setEventId("event")
                        .setRequestId("saga:" + type)
                        .setRequestType(type)
                        .setRequestSequence("START".equals(type) ? 1 : 2)
                        .setOrderId("42")
                        .setSagaId("saga")
                        .setOwnerEpoch(1)
                        .setWorkflowVersion(1)
                        .setSnapshotJson("{\"amount\":\"19.95\",\"currency\":\"USD\"}")
                        .setReason("CANCEL".equals(type) ? "requested" : "")
                        .build();
        assertThat(CheckoutSagaRequestedEvent.fromByteBuffer(input.toByteBuffer()))
                .isEqualTo(input);
    }
}
