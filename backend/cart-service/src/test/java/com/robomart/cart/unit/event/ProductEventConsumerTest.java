package com.robomart.cart.unit.event;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.robomart.cart.event.consumer.ProductEventConsumer;
import com.robomart.cart.repository.CartRepository;
import com.robomart.events.product.ProductUpdatedEvent;

class ProductEventConsumerTest {
    @Test
    void updatesOnlyIndexedProductAndInvalidPriceIsRecoverableByDlt() {
        CartRepository carts = mock(CartRepository.class);
        var consumer = new ProductEventConsumer(carts);
        ProductUpdatedEvent event = mock(ProductUpdatedEvent.class);
        Instant time = Instant.now();
        when(event.getProductId()).thenReturn(7L);
        when(event.getPrice()).thenReturn("12.50");
        when(event.getName()).thenReturn("Robot");
        when(event.getTimestamp()).thenReturn(time);
        consumer.onProductUpdated(event);
        verify(carts).updateProduct(7L, "Robot", new BigDecimal("12.50"), time);
        when(event.getPrice()).thenReturn("bad");
        assertThatThrownBy(() -> consumer.onProductUpdated(event)).isInstanceOf(NumberFormatException.class);
        when(event.getPrice()).thenReturn("-1");
        assertThatThrownBy(() -> consumer.onProductUpdated(event)).isInstanceOf(IllegalArgumentException.class);
        verifyNoMoreInteractions(carts);
    }
}
