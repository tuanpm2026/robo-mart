package com.robomart.common.exception;

import org.junit.jupiter.api.Test;

import com.robomart.common.logging.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;

class RoboMartExceptionTest {

    // ── ResourceNotFoundException ────────────────────────────────────────────

    @Test
    void resourceNotFoundException_withMessage_setsMessageAndErrorCode() {
        var ex = new ResourceNotFoundException("Item not found");

        assertThat(ex.getMessage()).isEqualTo("Item not found");
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void resourceNotFoundException_withResourceTypeAndId_formatsMessage() {
        var ex = new ResourceNotFoundException("Order", 42L);

        assertThat(ex.getMessage()).isEqualTo("Order not found with id: 42");
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ── BusinessRuleException ────────────────────────────────────────────────

    @Test
    void businessRuleException_setsMessageAndErrorCode() {
        var ex = new BusinessRuleException("Cannot cancel delivered order");

        assertThat(ex.getMessage()).isEqualTo("Cannot cancel delivered order");
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION);
    }

    // ── ValidationException ──────────────────────────────────────────────────

    @Test
    void validationException_setsMessageAndErrorCode() {
        var ex = new ValidationException("Email is required");

        assertThat(ex.getMessage()).isEqualTo("Email is required");
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    // ── ExternalServiceException ─────────────────────────────────────────────

    @Test
    void externalServiceException_withMessage_setsMessageAndErrorCode() {
        var ex = new ExternalServiceException("Payment gateway timeout");

        assertThat(ex.getMessage()).isEqualTo("Payment gateway timeout");
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR);
    }

    @Test
    void externalServiceException_withMessageAndCause_setsBoth() {
        var cause = new RuntimeException("connection refused");
        var ex = new ExternalServiceException("Payment gateway timeout", cause);

        assertThat(ex.getMessage()).isEqualTo("Payment gateway timeout");
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR);
    }

    // ── Hierarchy check ──────────────────────────────────────────────────────

    @Test
    void allExceptions_areInstancesOfRoboMartException() {
        assertThat(new ResourceNotFoundException("x")).isInstanceOf(RoboMartException.class);
        assertThat(new BusinessRuleException("x")).isInstanceOf(RoboMartException.class);
        assertThat(new ValidationException("x")).isInstanceOf(RoboMartException.class);
        assertThat(new ExternalServiceException("x")).isInstanceOf(RoboMartException.class);
    }
}
