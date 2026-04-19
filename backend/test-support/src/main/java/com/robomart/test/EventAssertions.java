package com.robomart.test;

import java.util.Objects;
import java.util.function.Consumer;

import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;
import org.assertj.core.api.AbstractAssert;

/**
 * AssertJ-style assertions for Avro Kafka events.
 * Usage:
 *   EventAssertions.assertThat(capturedEvent)
 *       .hasField("orderId", "order-123")
 *       .hasField("status", "CONFIRMED");
 */
public class EventAssertions extends AbstractAssert<EventAssertions, SpecificRecord> {

    private EventAssertions(SpecificRecord actual) {
        super(actual, EventAssertions.class);
    }

    public static EventAssertions assertThat(SpecificRecord actual) {
        return new EventAssertions(actual);
    }

    public EventAssertions hasField(String fieldName, Object expectedValue) {
        isNotNull();
        Schema.Field field = actual.getSchema().getField(fieldName);
        if (field == null) {
            failWithMessage("Avro schema has no field named <%s>. Available fields: <%s>",
                fieldName, actual.getSchema().getFields());
            return this;
        }
        Object actualValue = actual.get(field.pos());
        if (!Objects.equals(expectedValue, actualValue)) {
            failWithMessage("Expected Avro event field <%s> to be <%s> but was <%s>",
                fieldName, expectedValue, actualValue);
        }
        return this;
    }

    public EventAssertions satisfies(Consumer<SpecificRecord> assertions) {
        isNotNull();
        assertions.accept(actual);
        return this;
    }
}
