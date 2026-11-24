package com.robomart.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.robomart.events.config.AvroEventSecurityAutoConfiguration;
import com.robomart.events.order.CheckoutSagaRequestedEvent;

import org.apache.avro.util.ClassSecurityValidator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CheckoutSagaEnvelopeTest {
    @ParameterizedTest
    @ValueSource(strings = {"START", "CANCEL"})
    void sameEnvelopeRoundTripsBothRequestsWithoutRoundingAmounts(String type) {
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
        var originalValidator = ClassSecurityValidator.getGlobal();
        try {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(AvroEventSecurityAutoConfiguration.class))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(CheckoutSagaRequestedEvent.fromByteBuffer(input.toByteBuffer()))
                                .isEqualTo(input);
                    });
        } finally {
            ClassSecurityValidator.setGlobal(originalValidator);
        }
    }
}
