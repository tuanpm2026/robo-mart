package com.robomart.order.saga;

import com.robomart.order.entity.Order;

public class SagaContext {

    private final Order order;

    public SagaContext(Order order) {
        this.order = order;
    }

    public Order getOrder() {
        return order;
    }
}
