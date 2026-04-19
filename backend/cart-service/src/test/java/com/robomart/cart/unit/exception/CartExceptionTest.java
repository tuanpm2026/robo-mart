package com.robomart.cart.unit.exception;

import org.junit.jupiter.api.Test;

import com.robomart.cart.exception.CartItemNotFoundException;
import com.robomart.cart.exception.CartNotFoundException;
import com.robomart.common.exception.ResourceNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;

class CartExceptionTest {

    @Test
    void shouldCreateCartNotFoundExceptionWithCartId() {
        CartNotFoundException exception = new CartNotFoundException("cart-123");

        assertThat(exception).isInstanceOf(ResourceNotFoundException.class);
        assertThat(exception.getMessage()).contains("cart-123");
    }

    @Test
    void shouldCreateCartItemNotFoundExceptionWithCartIdAndProductId() {
        CartItemNotFoundException exception = new CartItemNotFoundException("cart-123", 42L);

        assertThat(exception).isInstanceOf(ResourceNotFoundException.class);
        assertThat(exception.getMessage()).contains("42");
        assertThat(exception.getMessage()).contains("cart-123");
    }
}
