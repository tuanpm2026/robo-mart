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
import com.robomart.common.exception.ValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartMergeServiceTest {

    @Mock
    private CartRepository cartRepository;

    private CartMapper cartMapper = new CartMapperImpl();

    private CartProperties cartProperties;

    private CartMergeService cartMergeService;

    private static final String ANONYMOUS_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String GUEST_KEY = "g:" + ANONYMOUS_ID;
    private static final String AUTH_USER_ID = "auth-user-uuid-456";
    private static final String USER_KEY = "u:" + AUTH_USER_ID;

    @BeforeEach
    void setUp() {
        cartProperties = new CartProperties();
        cartProperties.setTtlMinutes(1440);
        cartMergeService = new CartMergeService(cartRepository, cartMapper, cartProperties);
    }

    @Test
    void shouldMergeAnonymousCartIntoAuthenticatedCartWhenBothExist() {
        Cart anonCart = createCart(GUEST_KEY);
        anonCart.addItem(new CartItem(1L, "Product A", BigDecimal.valueOf(10.00), 2));
        anonCart.addItem(new CartItem(2L, "Product B", BigDecimal.valueOf(20.00), 1));

        Cart authCart = createCart(USER_KEY);
        authCart.setUserId(AUTH_USER_ID);
        authCart.addItem(new CartItem(3L, "Product C", BigDecimal.valueOf(30.00), 1));

        when(cartRepository.findById(GUEST_KEY)).thenReturn(Optional.of(anonCart));
        when(cartRepository.findById(USER_KEY)).thenReturn(Optional.of(authCart));

        CartResponse result = cartMergeService.mergeCart(ANONYMOUS_ID, AUTH_USER_ID);

        assertThat(result.cartId()).isEqualTo(AUTH_USER_ID);
        assertThat(result.items()).hasSize(3);
        verify(cartRepository).save(authCart);
        verify(cartRepository).deleteById(GUEST_KEY);
    }

    @Test
    void shouldSumQuantitiesWhenDuplicateProductExists() {
        Cart anonCart = createCart(GUEST_KEY);
        anonCart.addItem(new CartItem(1L, "Product A", BigDecimal.valueOf(10.00), 3));

        Cart authCart = createCart(USER_KEY);
        authCart.setUserId(AUTH_USER_ID);
        authCart.addItem(new CartItem(1L, "Product A", BigDecimal.valueOf(10.00), 2));

        when(cartRepository.findById(GUEST_KEY)).thenReturn(Optional.of(anonCart));
        when(cartRepository.findById(USER_KEY)).thenReturn(Optional.of(authCart));

        CartResponse result = cartMergeService.mergeCart(ANONYMOUS_ID, AUTH_USER_ID);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().quantity()).isEqualTo(5);
        assertThat(result.items().getFirst().productId()).isEqualTo(1L);
    }

    @Test
    void shouldAddUniqueProductsWhenNoDuplicates() {
        Cart anonCart = createCart(GUEST_KEY);
        anonCart.addItem(new CartItem(1L, "Product A", BigDecimal.valueOf(10.00), 1));

        Cart authCart = createCart(USER_KEY);
        authCart.setUserId(AUTH_USER_ID);
        authCart.addItem(new CartItem(2L, "Product B", BigDecimal.valueOf(20.00), 1));

        when(cartRepository.findById(GUEST_KEY)).thenReturn(Optional.of(anonCart));
        when(cartRepository.findById(USER_KEY)).thenReturn(Optional.of(authCart));

        CartResponse result = cartMergeService.mergeCart(ANONYMOUS_ID, AUTH_USER_ID);

        assertThat(result.items()).hasSize(2);
    }

    @Test
    void shouldDeleteAnonymousCartAfterSuccessfulMerge() {
        Cart anonCart = createCart(GUEST_KEY);
        anonCart.addItem(new CartItem(1L, "Product A", BigDecimal.valueOf(10.00), 1));

        when(cartRepository.findById(GUEST_KEY)).thenReturn(Optional.of(anonCart));
        when(cartRepository.findById(USER_KEY)).thenReturn(Optional.empty());

        cartMergeService.mergeCart(ANONYMOUS_ID, AUTH_USER_ID);

        verify(cartRepository).deleteById(GUEST_KEY);
    }

    @Test
    void shouldReturnAuthenticatedCartWhenAnonymousCartNotFound() {
        Cart authCart = createCart(USER_KEY);
        authCart.setUserId(AUTH_USER_ID);
        authCart.addItem(new CartItem(1L, "Product A", BigDecimal.valueOf(10.00), 2));

        when(cartRepository.findById(GUEST_KEY)).thenReturn(Optional.empty());
        when(cartRepository.findById(USER_KEY)).thenReturn(Optional.of(authCart));

        CartResponse result = cartMergeService.mergeCart(ANONYMOUS_ID, AUTH_USER_ID);

        assertThat(result.cartId()).isEqualTo(AUTH_USER_ID);
        assertThat(result.items()).hasSize(1);
        verify(cartRepository, never()).deleteById(any());
    }

    @Test
    void shouldReturnAuthenticatedCartWhenAnonymousCartEmpty() {
        Cart anonCart = createCart(GUEST_KEY);
        // empty — no items

        Cart authCart = createCart(USER_KEY);
        authCart.setUserId(AUTH_USER_ID);
        authCart.addItem(new CartItem(1L, "Product A", BigDecimal.valueOf(10.00), 1));

        when(cartRepository.findById(GUEST_KEY)).thenReturn(Optional.of(anonCart));
        when(cartRepository.findById(USER_KEY)).thenReturn(Optional.of(authCart));

        CartResponse result = cartMergeService.mergeCart(ANONYMOUS_ID, AUTH_USER_ID);

        assertThat(result.cartId()).isEqualTo(AUTH_USER_ID);
        assertThat(result.items()).hasSize(1);
        verify(cartRepository).deleteById(GUEST_KEY);
    }

    @Test
    void shouldRejectNonUuidGuestIdSuchAsAnotherUsersSub() {
        // IDOR guard: a victim's user id can never be used as a merge source
        assertThatThrownBy(() -> cartMergeService.mergeCart("victim-user-sub", AUTH_USER_ID))
                .isInstanceOf(ValidationException.class);
        verifyNoInteractions(cartRepository);
    }

    @Test
    void shouldOnlyReadAndDeleteGuestNamespaceEvenWhenIdLooksLikeUserSub() {
        // Keycloak subs are UUIDs too: a UUID guest id must still resolve to g:{uuid}, never u:{uuid}
        String victimSub = "123e4567-e89b-12d3-a456-426614174000";
        when(cartRepository.findById("g:" + victimSub)).thenReturn(Optional.empty());
        when(cartRepository.findById(USER_KEY)).thenReturn(Optional.of(createCart(USER_KEY)));

        cartMergeService.mergeCart(victimSub, AUTH_USER_ID);

        verify(cartRepository, never()).findById("u:" + victimSub);
        verify(cartRepository, never()).deleteById(any());
    }

    @Test
    void shouldReturnOwnCartWhenGuestIdEqualsOwnSub() {
        String ownSub = "123e4567-e89b-12d3-a456-426614174000";
        Cart userCart = createCart("u:" + ownSub);
        userCart.addItem(new CartItem(1L, "Product A", BigDecimal.valueOf(10.00), 1));
        when(cartRepository.findById("g:" + ownSub)).thenReturn(Optional.empty());
        when(cartRepository.findById("u:" + ownSub)).thenReturn(Optional.of(userCart));

        CartResponse result = cartMergeService.mergeCart(ownSub, ownSub);

        assertThat(result.cartId()).isEqualTo(ownSub);
        assertThat(result.items()).hasSize(1);
        verify(cartRepository, never()).deleteById(any());
    }

    @Test
    void shouldCapQuantityWhenMergingDuplicates() {
        Cart anonCart = createCart(GUEST_KEY);
        anonCart.addItem(new CartItem(1L, "Product A", BigDecimal.valueOf(10.00), 600));

        Cart authCart = createCart(USER_KEY);
        authCart.setUserId(AUTH_USER_ID);
        authCart.addItem(new CartItem(1L, "Product A", BigDecimal.valueOf(10.00), 600));

        when(cartRepository.findById(GUEST_KEY)).thenReturn(Optional.of(anonCart));
        when(cartRepository.findById(USER_KEY)).thenReturn(Optional.of(authCart));

        CartResponse result = cartMergeService.mergeCart(ANONYMOUS_ID, AUTH_USER_ID);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().quantity()).isEqualTo(Cart.MAX_ITEM_QUANTITY);
    }

    @Test
    void shouldRefreshTtlOnMergedCart() {
        Cart anonCart = createCart(GUEST_KEY);
        anonCart.addItem(new CartItem(1L, "Product A", BigDecimal.valueOf(10.00), 1));

        Cart authCart = createCart(USER_KEY);
        authCart.setUserId(AUTH_USER_ID);

        when(cartRepository.findById(GUEST_KEY)).thenReturn(Optional.of(anonCart));
        when(cartRepository.findById(USER_KEY)).thenReturn(Optional.of(authCart));

        cartMergeService.mergeCart(ANONYMOUS_ID, AUTH_USER_ID);

        ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(captor.capture());
        assertThat(captor.getValue().getTimeToLive()).isEqualTo(cartProperties.getTtlSeconds());
    }

    @Test
    void shouldCreateNewCartForAuthenticatedUserWhenNoneExists() {
        Cart anonCart = createCart(GUEST_KEY);
        anonCart.addItem(new CartItem(1L, "Product A", BigDecimal.valueOf(10.00), 2));
        anonCart.addItem(new CartItem(2L, "Product B", BigDecimal.valueOf(20.00), 1));

        when(cartRepository.findById(GUEST_KEY)).thenReturn(Optional.of(anonCart));
        when(cartRepository.findById(USER_KEY)).thenReturn(Optional.empty());

        CartResponse result = cartMergeService.mergeCart(ANONYMOUS_ID, AUTH_USER_ID);

        assertThat(result.cartId()).isEqualTo(AUTH_USER_ID);
        assertThat(result.items()).hasSize(2);

        ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(captor.capture());
        Cart savedCart = captor.getValue();
        assertThat(savedCart.getUserId()).isEqualTo(AUTH_USER_ID);
        assertThat(savedCart.getId()).isEqualTo(USER_KEY);
    }

    private Cart createCart(String id) {
        return new Cart(id);
    }
}
