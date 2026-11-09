package com.robomart.saga;

public record Transition(
        String state, String reservationId, String paymentId, String resolution, String reason) {
    public static Transition next(Work w, String state) {
        return new Transition(
                state, w.reservationId(), w.paymentId(), w.chargeResolution(), w.reason());
    }

    public static Transition cancel(Work w, String reason) {
        return new Transition(
                "BEGIN_CANCEL",
                w.reservationId(),
                w.paymentId(),
                w.chargeResolution(),
                w.reason().isBlank() ? reason : w.reason());
    }
}
