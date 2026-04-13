package com.robomart.order.web;

import java.time.Instant;

public record OrderEventResponse(Long id, String status, Instant changedAt) {}
