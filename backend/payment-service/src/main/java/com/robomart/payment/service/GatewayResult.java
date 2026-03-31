package com.robomart.payment.service;

public record GatewayResult(String transactionId, String status) {
}
