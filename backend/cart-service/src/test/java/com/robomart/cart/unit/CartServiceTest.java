package com.robomart.cart.unit;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.robomart.cart.dto.AddCartItemRequest;
import com.robomart.cart.dto.CartResponse;
import com.robomart.cart.dto.UpdateCartItemRequest;
import com.robomart.cart.entity.Cart;
import com.robomart.cart.entity.CartItem;
import com.robomart.cart.exception.CartItemNotFoundException;
import com.robomart.cart.exception.CartNotFoundException;
import com.robomart.cart.mapper.CartMapper;
import com.robomart.cart.mapper.CartMapperImpl;
import com.robomart.cart.repository.CartRepository;
import com.robomart.cart.service.CartService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock
    private CartRepository cartRepository;

    private CartMapper cartMapper;
    private CartService cartService;

    @BeforeEach
    void setUp() {
        cartMapper = new CartMapperImpl();
        cartService = new CartService(cartRepository, cartMapper);
    }

    @Test
    void shouldCreateNewCartWhenAddingItemToNonExistentCart() {
        String cartId = "cart-123";
        var request = new AddCartItemRequest(1L, "Test Product", new BigDecimal("29.99"), 2);
        when(cartRepository.findById(cartId)).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        CartResponse response = cartService.addItem(cartId, request);

        assertThat(response.cartId()).isEqualTo(cartId);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().productId()).isEqualTo(1L);
        assertThat(response.items().getFirst().productName()).isEqualTo("Test Product");
        assertThat(response.items().getFirst().quantity()).isEqualTo(2);
        assertThat(response.totalItems()).isEqualTo(2);
        assertThat(response.totalPrice()).isEqualByComparingTo(new BigDecimal("59.98"));
        verify(cartRepository).save(any(Cart.class));
    }

    @Test
    void shouldAddItemToExistingCart() {
        String cartId = "cart-123";
        Cart existingCart = new Cart(cartId);
        existingCart.addItem(new CartItem(1L, "Product A", new BigDecimal("10.00"), 1));

        var request = new AddCartItemRequest(2L, "Product B", new BigDecimal("20.00"), 3);
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(existingCart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        CartResponse response = cartService.addItem(cartId, request);

        assertThat(response.items()).hasSize(2);
        assertThat(response.totalItems()).isEqualTo(4);
        assertThat(response.totalPrice()).isEqualByComparingTo(new BigDecimal("70.00"));
    }

    @Test
    void shouldIncrementQuantityWhenAddingSameProduct() {
        String cartId = "cart-123";
        Cart existingCart = new Cart(cartId);
        existingCart.addItem(new CartItem(1L, "Product A", new BigDecimal("10.00"), 2));

        var request = new AddCartItemRequest(1L, "Product A", new BigDecimal("10.00"), 3);
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(existingCart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        CartResponse response = cartService.addItem(cartId, request);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().quantity()).isEqualTo(5);
        assertThat(response.totalItems()).isEqualTo(5);
        assertThat(response.totalPrice()).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    void shouldUpdateItemQuantityWhenCartAndItemExist() {
        String cartId = "cart-123";
        Cart cart = new Cart(cartId);
        cart.addItem(new CartItem(1L, "Product A", new BigDecimal("10.00"), 2));

        var request = new UpdateCartItemRequest(5);
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(cart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        CartResponse response = cartService.updateItemQuantity(cartId, 1L, request);

        assertThat(response.items().getFirst().quantity()).isEqualTo(5);
        assertThat(response.totalPrice()).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    void shouldThrowCartNotFoundExceptionWhenUpdatingNonExistentCart() {
        String cartId = "nonexistent";
        var request = new UpdateCartItemRequest(3);
        when(cartRepository.findById(cartId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.updateItemQuantity(cartId, 1L, request))
                .isInstanceOf(CartNotFoundException.class)
                .hasMessageContaining(cartId);
    }

    @Test
    void shouldThrowCartItemNotFoundExceptionWhenUpdatingNonExistentItem() {
        String cartId = "cart-123";
        Cart cart = new Cart(cartId);
        var request = new UpdateCartItemRequest(3);
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(cart));

        assertThatThrownBy(() -> cartService.updateItemQuantity(cartId, 99L, request))
                .isInstanceOf(CartItemNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void shouldRemoveItemFromCart() {
        String cartId = "cart-123";
        Cart cart = new Cart(cartId);
        cart.addItem(new CartItem(1L, "Product A", new BigDecimal("10.00"), 1));
        cart.addItem(new CartItem(2L, "Product B", new BigDecimal("20.00"), 1));

        when(cartRepository.findById(cartId)).thenReturn(Optional.of(cart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        cartService.removeItem(cartId, 1L);

        assertThat(cart.getItems()).hasSize(1);
        assertThat(cart.getItems().getFirst().getProductId()).isEqualTo(2L);
        verify(cartRepository).save(cart);
    }

    @Test
    void shouldThrowCartNotFoundExceptionWhenRemovingFromNonExistentCart() {
        String cartId = "nonexistent";
        when(cartRepository.findById(cartId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.removeItem(cartId, 1L))
                .isInstanceOf(CartNotFoundException.class);
    }

    @Test
    void shouldThrowCartItemNotFoundExceptionWhenRemovingNonExistentItem() {
        String cartId = "cart-123";
        Cart cart = new Cart(cartId);
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(cart));

        assertThatThrownBy(() -> cartService.removeItem(cartId, 99L))
                .isInstanceOf(CartItemNotFoundException.class);
    }

    @Test
    void shouldReturnCartWhenValidCartId() {
        String cartId = "cart-123";
        Cart cart = new Cart(cartId);
        cart.addItem(new CartItem(1L, "Product A", new BigDecimal("15.50"), 3));
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(cart));

        CartResponse response = cartService.getCart(cartId);

        assertThat(response.cartId()).isEqualTo(cartId);
        assertThat(response.items()).hasSize(1);
        assertThat(response.totalItems()).isEqualTo(3);
        assertThat(response.totalPrice()).isEqualByComparingTo(new BigDecimal("46.50"));
    }

    @Test
    void shouldThrowCartNotFoundExceptionWhenGettingNonExistentCart() {
        String cartId = "nonexistent";
        when(cartRepository.findById(cartId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.getCart(cartId))
                .isInstanceOf(CartNotFoundException.class);
    }

    @Test
    void shouldReturnEmptyCartWhenNoItems() {
        String cartId = "cart-empty";
        Cart cart = new Cart(cartId);
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(cart));

        CartResponse response = cartService.getCart(cartId);

        assertThat(response.cartId()).isEqualTo(cartId);
        assertThat(response.items()).isEmpty();
        assertThat(response.totalItems()).isZero();
        assertThat(response.totalPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
