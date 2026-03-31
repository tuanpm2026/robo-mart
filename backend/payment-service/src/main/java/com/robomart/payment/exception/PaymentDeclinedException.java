package com.robomart.payment.exception;

import com.robomart.common.exception.ExternalServiceException;

public class PaymentDeclinedException extends ExternalServiceException {

    public PaymentDeclinedException(String message) {
        super(message);
    }

    public PaymentDeclinedException(String message, Throwable cause) {
        super(message, cause);
    }
}
