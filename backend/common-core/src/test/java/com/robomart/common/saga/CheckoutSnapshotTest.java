package com.robomart.common.saga;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class CheckoutSnapshotTest {
    @Test
    void snapshotCopiesLinesAndRejectsUnsupportedVersionOrInvalidValues() {
        var lines = new ArrayList<>(List.of(new CheckoutSnapshot.Line("1", 2)));
        var snapshot = new CheckoutSnapshot("42", "saga", 1, "user", "20.00", "USD", 1, lines);
        lines.clear();
        assertThat(snapshot.items()).hasSize(1);
        assertThatThrownBy(() -> snapshot.items().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(
                        () ->
                                new CheckoutSnapshot(
                                        "42",
                                        "saga",
                                        1,
                                        "user",
                                        "20.00",
                                        "USD",
                                        2,
                                        snapshot.items()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new CheckoutSnapshot(
                                        "0",
                                        "saga",
                                        1,
                                        "user",
                                        "20.00",
                                        "USD",
                                        1,
                                        snapshot.items()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new CheckoutSnapshot(
                                        "42",
                                        "saga",
                                        1,
                                        "user",
                                        "0.00",
                                        "USD",
                                        1,
                                        snapshot.items()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutSnapshot.Line("1", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutSnapshot.Line("1", 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
