package com.robomart.cart.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import com.robomart.cart.config.CartProperties;
import com.robomart.cart.entity.Cart;
import com.robomart.cart.mapper.CartMapperImpl;
import com.robomart.cart.repository.CartRepository;
import com.robomart.cart.service.CartMergeService;
import com.robomart.common.exception.ValidationException;

class CartMergeServiceTest {
    @Test
    void onlyValidatedGuestNamespaceCanMergeIntoAuthenticatedUsersOwnCart() {
        CartRepository carts = mock(CartRepository.class);
        var service = new CartMergeService(carts, new CartMapperImpl(), new CartProperties());
        String guest = "550e8400-e29b-41d4-a716-446655440000";
        when(carts.merge("g:" + guest, "u:customer", "customer")).thenReturn(new Cart("u:customer"));
        assertThat(service.mergeCart(guest, "customer").cartId()).isEqualTo("customer");
        verify(carts).merge("g:" + guest, "u:customer", "customer");
        assertThatThrownBy(() -> service.mergeCart("u:another-user", "customer"))
                .isInstanceOf(ValidationException.class);
        verifyNoMoreInteractions(carts);
    }
}
