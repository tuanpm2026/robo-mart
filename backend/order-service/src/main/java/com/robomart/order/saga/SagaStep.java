package com.robomart.order.saga;

public interface SagaStep {

    String getName();

    SagaStepResult execute(SagaContext context);

    void compensate(SagaContext context);
}
