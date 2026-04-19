package com.robomart.test;

/**
 * Helper for creating Order objects in specific saga states for unit testing.
 * Use in unit tests that mock repositories — not for integration tests.
 */
public final class SagaTestHelper {

    private SagaTestHelper() {
    }

    /**
     * Returns a TestData.OrderBuilder pre-configured with the given status.
     * Usage: SagaTestHelper.orderInState("PAYMENT_FAILED")
     */
    public static TestData.OrderBuilder orderInState(String status) {
        return TestData.order().withStatus(status);
    }

    /**
     * Creates an order item builder for a given product SKU with quantity.
     */
    public static TestData.OrderItemBuilder orderItem(String sku, int quantity) {
        return new TestData.OrderItemBuilder()
            .withSku(sku)
            .withQuantity(quantity);
    }
}
