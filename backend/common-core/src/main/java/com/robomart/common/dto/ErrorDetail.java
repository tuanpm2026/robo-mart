package com.robomart.common.dto;

import java.util.Map;

import org.jspecify.annotations.Nullable;

public record ErrorDetail(String code, String message, @Nullable Map<String, String> details) {
}
