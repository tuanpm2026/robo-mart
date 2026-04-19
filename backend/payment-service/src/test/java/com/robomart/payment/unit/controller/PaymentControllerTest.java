package com.robomart.payment.unit.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.robomart.common.dto.ApiResponse;
import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.payment.controller.PaymentAdminRestController;
import com.robomart.payment.entity.Payment;
import com.robomart.payment.enums.PaymentStatus;
import com.robomart.payment.repository.PaymentRepository;
import com.robomart.payment.web.PaymentStatusResponse;
import io.micrometer.tracing.Tracer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private Tracer tracer;

    private PaymentAdminRestController controller;

    @BeforeEach
    void setUp() {
        controller = new PaymentAdminRestController(paymentRepository, tracer);
    }

    @Test
    void shouldReturnPaymentStatusWhenValidOrderIdProvided() {
        Payment payment = buildPayment("order-123", PaymentStatus.COMPLETED);
        when(paymentRepository.findByOrderId("order-123")).thenReturn(Optional.of(payment));

        ResponseEntity<ApiResponse<PaymentStatusResponse>> response =
                controller.getPaymentByOrderId("order-123");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().orderId()).isEqualTo("order-123");
        assertThat(response.getBody().data().status()).isEqualTo("COMPLETED");
    }

    @Test
    void shouldThrowNotFoundWhenOrderIdDoesNotExist() {
        when(paymentRepository.findByOrderId("no-such-order")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getPaymentByOrderId("no-such-order"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void shouldReturnPaymentAmountAndCurrencyWhenFound() {
        Payment payment = buildPayment("order-456", PaymentStatus.PENDING);
        when(paymentRepository.findByOrderId("order-456")).thenReturn(Optional.of(payment));

        ResponseEntity<ApiResponse<PaymentStatusResponse>> response =
                controller.getPaymentByOrderId("order-456");

        assertThat(response.getBody().data().amount()).isEqualByComparingTo(BigDecimal.valueOf(99.99));
        assertThat(response.getBody().data().currency()).isEqualTo("USD");
    }

    private Payment buildPayment(String orderId, PaymentStatus status) {
        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setAmount(BigDecimal.valueOf(99.99));
        payment.setCurrency("USD");
        payment.setStatus(status);
        payment.setTransactionId("txn-" + orderId);
        payment.setIdempotencyKey("idem-" + orderId);
        payment.setCreatedAt(Instant.now());
        return payment;
    }
}
