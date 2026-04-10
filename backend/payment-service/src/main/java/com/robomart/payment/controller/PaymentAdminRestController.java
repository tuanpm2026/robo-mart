package com.robomart.payment.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.robomart.common.dto.ApiResponse;
import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.payment.repository.PaymentRepository;
import com.robomart.payment.web.PaymentStatusResponse;

// No @PreAuthorize needed — ADMIN role enforced at API Gateway level
@RestController
@RequestMapping("/api/v1/admin/payments")
public class PaymentAdminRestController {

    private final PaymentRepository paymentRepository;

    public PaymentAdminRestController(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<ApiResponse<PaymentStatusResponse>> getPaymentByOrderId(
            @PathVariable String orderId) {
        var payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found for order: " + orderId));
        PaymentStatusResponse response = new PaymentStatusResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus().name(),
                payment.getTransactionId(),
                payment.getCreatedAt());
        return ResponseEntity.ok(new ApiResponse<>(response, null));
    }
}
