package com.robomart.cart.unit;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.robomart.cart.dto.CartItemResponse;
import com.robomart.cart.dto.CartResponse;
import com.robomart.cart.entity.Cart;
import com.robomart.cart.entity.CartItem;
import com.robomart.cart.mapper.CartMapper;
import com.robomart.cart.mapper.CartMapperImpl;

import static org.assertj.core.api.Assertions.assertThat;

class CartMapperTest {

    private CartMapper cartMapper;

    @BeforeEach
    void setUp() {
        cartMapper = new CartMapperImpl();
    }

    @Test
    void shouldMapCartToCartResponse() {
        Cart cart = new Cart("cart-1");
        cart.addItem(new CartItem(1L, "Product A", new BigDecimal("10.00"), 2));
        cart.addItem(new CartItem(2L, "Product B", new BigDecimal("25.50"), 1));

        CartResponse response = cartMapper.toCartResponse(cart);

        assertThat(response.cartId()).isEqualTo("cart-1");
        assertThat(response.items()).hasSize(2);
        assertThat(response.totalItems()).isEqualTo(3);
        assertThat(response.totalPrice()).isEqualByComparingTo(new BigDecimal("45.50"));
    }

    @Test
    void shouldMapCartItemToCartItemResponse() {
        CartItem item = new CartItem(1L, "Test Product", new BigDecimal("19.99"), 3);

        CartItemResponse response = cartMapper.toCartItemResponse(item);

        assertThat(response.productId()).isEqualTo(1L);
        assertThat(response.productName()).isEqualTo("Test Product");
        assertThat(response.price()).isEqualByComparingTo(new BigDecimal("19.99"));
        assertThat(response.quantity()).isEqualTo(3);
        assertThat(response.subtotal()).isEqualByComparingTo(new BigDecimal("59.97"));
    }

    @Test
    void shouldMapEmptyCartToEmptyResponse() {
        Cart cart = new Cart("empty-cart");

        CartResponse response = cartMapper.toCartResponse(cart);

        assertThat(response.cartId()).isEqualTo("empty-cart");
        assertThat(response.items()).isEmpty();
        assertThat(response.totalItems()).isZero();
        assertThat(response.totalPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
