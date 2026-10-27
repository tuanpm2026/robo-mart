package com.robomart.outbox;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Preserve a legacy row-derived namespace exactly; never regenerate IDs during a retry. */
public final class StableEventIds {
    private StableEventIds() { }

    public static String fromRow(String namespace, long rowId) {
        if (namespace == null || namespace.isBlank() || rowId < 1) {
            throw new IllegalArgumentException("Stable event identity requires a namespace and persisted positive row ID");
        }
        return UUID.nameUUIDFromBytes((namespace + rowId).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
