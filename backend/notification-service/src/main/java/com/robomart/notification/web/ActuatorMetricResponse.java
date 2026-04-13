package com.robomart.notification.web;

import java.util.List;

public record ActuatorMetricResponse(List<Measurement> measurements) {

    public record Measurement(String statistic, Double value) {}

    public Double firstValue() {
        if (measurements == null || measurements.isEmpty()) return null;
        return measurements.stream()
                .filter(m -> "VALUE".equals(m.statistic()) && m.value() != null)
                .map(Measurement::value)
                .findFirst()
                .orElseGet(() -> measurements.get(0).value());
    }
}
