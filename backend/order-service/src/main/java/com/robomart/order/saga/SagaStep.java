package com.robomart.order.saga;

public interface SagaStep {

    String getName();

    void execute(SagaContext context);

    void compensate(SagaContext context);
}
