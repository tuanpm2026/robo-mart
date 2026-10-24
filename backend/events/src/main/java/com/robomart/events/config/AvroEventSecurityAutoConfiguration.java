package com.robomart.events.config;

import org.apache.avro.util.ClassSecurityValidator;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/** Registers only the generated event records trusted for Avro class loading. */
@AutoConfiguration
public class AvroEventSecurityAutoConfiguration {

    private static final ClassSecurityValidator.ClassSecurityPredicate TRUSTED_EVENT_CLASSES =
            ClassSecurityValidator.builder()
                    .add("com.robomart.events.BaseEventEnvelope")
                    .add("com.robomart.events.cart.CartExpiryWarningEvent")
                    .add("com.robomart.events.cart.CartItemSummary")
                    .add("com.robomart.events.inventory.StockLowAlertEvent")
                    .add("com.robomart.events.inventory.StockReleasedEvent")
                    .add("com.robomart.events.inventory.StockReservedEvent")
                    .add("com.robomart.events.order.OrderCancelledEvent")
                    .add("com.robomart.events.order.OrderCreatedEvent")
                    .add("com.robomart.events.order.OrderItemDetail")
                    .add("com.robomart.events.order.OrderStatusChangedEvent")
                    .add("com.robomart.events.payment.PaymentProcessedEvent")
                    .add("com.robomart.events.payment.PaymentRefundedEvent")
                    .add("com.robomart.events.product.ProductCreatedEvent")
                    .add("com.robomart.events.product.ProductDeletedEvent")
                    .add("com.robomart.events.product.ProductUpdatedEvent")
                    .build();

    @Bean
    static BeanFactoryPostProcessor avroEventClassSecurity() {
        // Run before any producer/consumer bean can initialize Avro's schema/class caches.
        return beanFactory -> registerTrustedEventClasses();
    }

    private static synchronized void registerTrustedEventClasses() {
        var existing = ClassSecurityValidator.getGlobal();
        if (!(existing instanceof EventClassSecurityPredicate)) {
            ClassSecurityValidator.setGlobal(new EventClassSecurityPredicate(existing));
        }
    }

    private record EventClassSecurityPredicate(ClassSecurityValidator.ClassSecurityPredicate existing)
            implements ClassSecurityValidator.ClassSecurityPredicate {

        @Override
        public boolean isTrusted(Class<?> clazz) {
            // Keep Avro's defaults and any deployment-provided trusted types.
            return existing.isTrusted(clazz) || TRUSTED_EVENT_CLASSES.isTrusted(clazz);
        }
    }
}
