package com.robomart.common.dto;

import java.util.Map;

public record ErrorDetail(String code, String message, Map<String, String> details) {
}
