package com.robomart.payment.controller;

import io.micrometer.tracing.Tracer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.robomart.common.dto.ApiResponse;
import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.payment.service.PaymentService;
import com.robomart.payment.web.PaymentStatusResponse;

// No @PreAuthorize needed — ADMIN role enforced at API Gateway level
@RestController
@RequestMapping("/api/v1/admin/payments")
public class PaymentAdminRestController {

    private final PaymentService paymentService;
    private final Tracer tracer;

    public PaymentAdminRestController(PaymentService paymentService, Tracer tracer) {
        this.paymentService = paymentService;
        this.tracer = tracer;
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<ApiResponse<PaymentStatusResponse>> getPaymentByOrderId(
            @PathVariable String orderId) {
        var payment = paymentService.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found for order: " + orderId));
        PaymentStatusResponse response = new PaymentStatusResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus().name(),
                payment.getTransactionId(),
                payment.getCreatedAt());
        return ResponseEntity.ok(new ApiResponse<>(response, getTraceId()));
    }

    private String getTraceId() {
        io.micrometer.tracing.Span span = tracer.currentSpan();
        if (span != null) {
            io.micrometer.tracing.TraceContext ctx = span.context();
            if (ctx != null) {
                return ctx.traceId();
            }
        }
        return null;
    }
}
