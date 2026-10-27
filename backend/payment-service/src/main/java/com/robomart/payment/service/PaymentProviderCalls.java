package com.robomart.payment.service;

import java.util.function.Supplier;

import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.stereotype.Component;

/** One shared non-blocking limit for charge and refund provider transactions. */
@Component
public class PaymentProviderCalls {
    @ConcurrencyLimit(limit = 2, policy = ConcurrencyLimit.ThrottlePolicy.REJECT)
    public GatewayResult execute(Supplier<GatewayResult> request) {
        return request.get();
    }
}
