package com.robomart.payment.exception;

import com.robomart.common.exception.ExternalServiceException;

public class PaymentTransientException extends ExternalServiceException {

    public PaymentTransientException(String message) {
        super(message);
    }

    public PaymentTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
