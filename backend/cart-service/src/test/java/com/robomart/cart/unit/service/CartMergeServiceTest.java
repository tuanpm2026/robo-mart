package com.robomart.cart.unit.service;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.robomart.cart.config.CartProperties;
import com.robomart.cart.dto.CartResponse;
import com.robomart.cart.entity.Cart;
import com.robomart.cart.entity.CartItem;
import com.robomart.cart.mapper.CartMapper;
import com.robomart.cart.mapper.CartMapperImpl;
import com.robomart.cart.repository.CartRepository;
import com.robomart.cart.service.CartMergeService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartMergeServiceTest {

    @Mock
    private CartRepository cartRepository;

    private CartMapper cartMapper = new CartMapperImpl();

    private CartProperties cartProperties;

    private CartMergeService cartMergeService;

    private static final String ANONYMOUS_ID = "anon-uuid-123";
    private static final String AUTH_USER_ID = "auth-user-uuid-456";

    @BeforeEach
    void setUp() {
        cartProperties = new CartProperties();
        cartProperties.setTtlMinutes(1440);
        cartMergeService = new CartMergeService(cartRepository, cartMapper, cartProperties);
    }

    @Test
    void shouldMergeAnonymousCartIntoAuthenticatedCartWhenBothExist() {
        Cart anonCart = createCart(ANONYMOUS_ID);
        anonCart.addItem(new CartItem(1L, "Product A", BigDecimal.valueOf(10.00), 2));
        anonCart.addItem(new CartItem(2L, "Product B", BigDecimal.valueOf(20.00), 1));

        Cart authCart = createCart(AUTH_USER_ID);
        authCart.setUserId(AUTH_USER_ID);
        authCart.addItem(new CartItem(3L, "Product C", BigDecimal.valueOf(30.00), 1));

        when(cartRepository.findById(ANONYMOUS_ID)).thenReturn(Optional.of(anonCart));
        when(cartRepository.findById(AUTH_USER_ID)).thenReturn(Optional.of(authCart));

        CartResponse result = cartMergeService.mergeCart(ANONYMOUS_ID, AUTH_USER_ID);

        assertThat(result.cartId()).isEqualTo(AUTH_USER_ID);
        assertThat(result.items()).hasSize(3);
        verify(cartRepository).save(authCart);
        verify(cartRepository).deleteById(ANONYMOUS_ID);
    }

    @Test
    void shouldSumQuantitiesWhenDuplicateProductExists() {
        Cart anonCart = createCart(ANONYMOUS_ID);
        anonCart.addItem(new CartItem(1L, "Product A", BigDecimal.valueOf(10.00), 3));

        Cart authCart = createCart(AUTH_USER_ID);
        authCart.setUserId(AUTH_USER_ID);
        authCart.addItem(new CartItem(1L, "Product A", BigDecimal.valueOf(10.00), 2));

        when(cartRepository.findById(ANONYMOUS_ID)).thenReturn(Optional.of(anonCart));
        when(cartRepository.findById(AUTH_USER_ID)).thenReturn(Optional.of(authCart));

        CartResponse result = cartMergeService.mergeCart(ANONYMOUS_ID, AUTH_USER_ID);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().quantity()).isEqualTo(5);
        assertThat(result.items().getFirst().productId()).isEqualTo(1L);
    }

    @Test
    void shouldAddUniqueProductsWhenNoDuplicates() {
        Cart anonCart = createCart(ANONYMOUS_ID);
        anonCart.addItem(new CartItem(1L, "Product A", BigDecimal.valueOf(10.00), 1));

        Cart authCart = createCart(AUTH_USER_ID);
        authCart.setUserId(AUTH_USER_ID);
        authCart.addItem(new CartItem(2L, "Product B", BigDecimal.valueOf(20.00), 1));

        when(cartRepository.findById(ANONYMOUS_ID)).thenReturn(Optional.of(anonCart));
        when(cartRepository.findById(AUTH_USER_ID)).thenReturn(Optional.of(authCart));

        CartResponse result = cartMergeService.mergeCart(ANONYMOUS_ID, AUTH_USER_ID);

        assertThat(result.items()).hasSize(2);
    }

    @Test
    void shouldDeleteAnonymousCartAfterSuccessfulMerge() {
        Cart anonCart = createCart(ANONYMOUS_ID);
        anonCart.addItem(new CartItem(1L, "Product A", BigDecimal.valueOf(10.00), 1));

        when(cartRepository.findById(ANONYMOUS_ID)).thenReturn(Optional.of(anonCart));
        when(cartRepository.findById(AUTH_USER_ID)).thenReturn(Optional.empty());

        cartMergeService.mergeCart(ANONYMOUS_ID, AUTH_USER_ID);

        verify(cartRepository).deleteById(ANONYMOUS_ID);
    }

    @Test
    void shouldReturnAuthenticatedCartWhenAnonymousCartNotFound() {
        Cart authCart = createCart(AUTH_USER_ID);
        authCart.setUserId(AUTH_USER_ID);
        authCart.addItem(new CartItem(1L, "Product A", BigDecimal.valueOf(10.00), 2));

        when(cartRepository.findById(ANONYMOUS_ID)).thenReturn(Optional.empty());
        when(cartRepository.findById(AUTH_USER_ID)).thenReturn(Optional.of(authCart));

        CartResponse result = cartMergeService.mergeCart(ANONYMOUS_ID, AUTH_USER_ID);

        assertThat(result.cartId()).isEqualTo(AUTH_USER_ID);
        assertThat(result.items()).hasSize(1);
        verify(cartRepository, never()).deleteById(any());
    }

    @Test
    void shouldReturnAuthenticatedCartWhenAnonymousCartEmpty() {
        Cart anonCart = createCart(ANONYMOUS_ID);
        // empty — no items

        Cart authCart = createCart(AUTH_USER_ID);
        authCart.setUserId(AUTH_USER_ID);
        authCart.addItem(new CartItem(1L, "Product A", BigDecimal.valueOf(10.00), 1));

        when(cartRepository.findById(ANONYMOUS_ID)).thenReturn(Optional.of(anonCart));
        when(cartRepository.findById(AUTH_USER_ID)).thenReturn(Optional.of(authCart));

        CartResponse result = cartMergeService.mergeCart(ANONYMOUS_ID, AUTH_USER_ID);

        assertThat(result.cartId()).isEqualTo(AUTH_USER_ID);
        assertThat(result.items()).hasSize(1);
        verify(cartRepository).deleteById(ANONYMOUS_ID);
    }

    @Test
    void shouldNoOpWhenAnonymousIdEqualsAuthenticatedId() {
        Cart cart = createCart(AUTH_USER_ID);
        cart.setUserId(AUTH_USER_ID);
        cart.addItem(new CartItem(1L, "Product A", BigDecimal.valueOf(10.00), 1));

        when(cartRepository.findById(AUTH_USER_ID)).thenReturn(Optional.of(cart));

        CartResponse result = cartMergeService.mergeCart(AUTH_USER_ID, AUTH_USER_ID);

        assertThat(result.cartId()).isEqualTo(AUTH_USER_ID);
        verify(cartRepository, never()).deleteById(any());
        // findById called once for self-merge getOrCreateCart, never for merge logic
    }

    @Test
    void shouldCapQuantityAt9999WhenMergingDuplicates() {
        Cart anonCart = createCart(ANONYMOUS_ID);
        anonCart.addItem(new CartItem(1L, "Product A", BigDecimal.valueOf(10.00), 5000));

        Cart authCart = createCart(AUTH_USER_ID);
        authCart.setUserId(AUTH_USER_ID);
        authCart.addItem(new CartItem(1L, "Product A", BigDecimal.valueOf(10.00), 5000));

        when(cartRepository.findById(ANONYMOUS_ID)).thenReturn(Optional.of(anonCart));
        when(cartRepository.findById(AUTH_USER_ID)).thenReturn(Optional.of(authCart));

        CartResponse result = cartMergeService.mergeCart(ANONYMOUS_ID, AUTH_USER_ID);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().quantity()).isEqualTo(9999);
    }

    @Test
    void shouldRefreshTtlOnMergedCart() {
        Cart anonCart = createCart(ANONYMOUS_ID);
        anonCart.addItem(new CartItem(1L, "Product A", BigDecimal.valueOf(10.00), 1));

        Cart authCart = createCart(AUTH_USER_ID);
        authCart.setUserId(AUTH_USER_ID);

        when(cartRepository.findById(ANONYMOUS_ID)).thenReturn(Optional.of(anonCart));
        when(cartRepository.findById(AUTH_USER_ID)).thenReturn(Optional.of(authCart));

        cartMergeService.mergeCart(ANONYMOUS_ID, AUTH_USER_ID);

        ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(captor.capture());
        assertThat(captor.getValue().getTimeToLive()).isEqualTo(cartProperties.getTtlSeconds());
    }

    @Test
    void shouldCreateNewCartForAuthenticatedUserWhenNoneExists() {
        Cart anonCart = createCart(ANONYMOUS_ID);
        anonCart.addItem(new CartItem(1L, "Product A", BigDecimal.valueOf(10.00), 2));
        anonCart.addItem(new CartItem(2L, "Product B", BigDecimal.valueOf(20.00), 1));

        when(cartRepository.findById(ANONYMOUS_ID)).thenReturn(Optional.of(anonCart));
        when(cartRepository.findById(AUTH_USER_ID)).thenReturn(Optional.empty());

        CartResponse result = cartMergeService.mergeCart(ANONYMOUS_ID, AUTH_USER_ID);

        assertThat(result.cartId()).isEqualTo(AUTH_USER_ID);
        assertThat(result.items()).hasSize(2);

        ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(captor.capture());
        Cart savedCart = captor.getValue();
        assertThat(savedCart.getUserId()).isEqualTo(AUTH_USER_ID);
        assertThat(savedCart.getId()).isEqualTo(AUTH_USER_ID);
    }

    private Cart createCart(String id) {
        return new Cart(id);
    }
}
