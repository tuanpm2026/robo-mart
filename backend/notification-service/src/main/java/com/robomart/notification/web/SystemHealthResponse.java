package com.robomart.notification.web;

import java.time.Instant;
import java.util.List;

public record SystemHealthResponse(List<ServiceHealthData> services, Instant checkedAt) {}
