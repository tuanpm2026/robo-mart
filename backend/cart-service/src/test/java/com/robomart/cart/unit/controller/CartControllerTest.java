package com.robomart.cart.unit.controller;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.robomart.cart.controller.CartRestController;
import com.robomart.cart.dto.AddCartItemRequest;
import com.robomart.cart.dto.CartItemResponse;
import com.robomart.cart.dto.CartResponse;
import com.robomart.cart.dto.MergeCartRequest;
import com.robomart.cart.dto.UpdateCartItemRequest;
import com.robomart.cart.service.CartMergeService;
import com.robomart.cart.service.CartService;
import com.robomart.common.dto.ApiResponse;
import com.robomart.common.exception.ValidationException;
import io.micrometer.tracing.Tracer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartControllerTest {

    private static final String USER_ID = "user-001";
    private static final String USER_KEY = "u:user-001";
    private static final String GUEST_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String GUEST_KEY = "g:" + GUEST_ID;

    @Mock
    private CartService cartService;

    @Mock
    private CartMergeService cartMergeService;

    @Mock
    private Tracer tracer;

    private CartRestController controller;

    private CartResponse sampleCartResponse;

    @BeforeEach
    void setUp() {
        controller = new CartRestController(cartService, cartMergeService, tracer);
        sampleCartResponse = new CartResponse("cart-001", List.of(), 0, BigDecimal.ZERO);
    }

    @Test
    void shouldUseUserNamespaceWhenAddItemWithUserId() {
        AddCartItemRequest request = new AddCartItemRequest(1L, "Test Product", BigDecimal.valueOf(29.99), 2);
        when(cartService.addItem(eq(USER_KEY), eq(request), eq(USER_ID)))
                .thenReturn(sampleCartResponse);

        ResponseEntity<ApiResponse<CartResponse>> response =
                controller.addItem(null, USER_ID, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void shouldUseGuestNamespaceWhenAddItemWithCartId() {
        AddCartItemRequest request = new AddCartItemRequest(1L, 1);
        when(cartService.addItem(eq(GUEST_KEY), eq(request), eq(null)))
                .thenReturn(sampleCartResponse);

        ResponseEntity<ApiResponse<CartResponse>> response =
                controller.addItem(GUEST_ID, null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void shouldIgnoreCartIdWhenAuthenticated() {
        when(cartService.getCart(USER_KEY)).thenReturn(sampleCartResponse);

        controller.getCart(GUEST_ID, USER_ID);

        verify(cartService).getCart(USER_KEY);
    }

    @Test
    void shouldRejectCartIdThatIsNotUuid() {
        // A victim's JWT subject (or any non-UUID) must never address a cart via X-Cart-Id
        assertThatThrownBy(() -> controller.getCart("victim-sub", null))
                .isInstanceOf(ValidationException.class);
        verifyNoInteractions(cartService);
    }

    @Test
    void shouldNotReachUserCartThroughCartIdEvenWhenSubIsUuid() {
        when(cartService.getCart(GUEST_KEY)).thenReturn(sampleCartResponse);

        controller.getCart(GUEST_ID, null);

        verify(cartService).getCart(GUEST_KEY);
    }

    @Test
    void shouldReturnOkWhenGetCartWithUserId() {
        when(cartService.getCart(USER_KEY)).thenReturn(sampleCartResponse);

        ResponseEntity<ApiResponse<CartResponse>> response =
                controller.getCart(null, USER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isEqualTo(sampleCartResponse);
        assertThat(response.getHeaders().getFirst(CartRestController.CART_ID_HEADER)).isEqualTo("cart-001");
    }

    @Test
    void shouldReturnOkWhenUpdateItemQuantity() {
        UpdateCartItemRequest request = new UpdateCartItemRequest(3);
        CartItemResponse item = new CartItemResponse(1L, "Test Product",
                BigDecimal.valueOf(29.99), 3, BigDecimal.valueOf(89.97));
        CartResponse updatedCart = new CartResponse(USER_ID, List.of(item), 3, BigDecimal.valueOf(89.97));
        when(cartService.updateItemQuantity(eq(USER_KEY), eq(1L), eq(request)))
                .thenReturn(updatedCart);

        ResponseEntity<ApiResponse<CartResponse>> response =
                controller.updateItemQuantity(null, USER_ID, 1L, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data().totalItems()).isEqualTo(3);
    }

    @Test
    void shouldReturnNoContentWithPublicCartIdWhenRemoveItem() {
        ResponseEntity<Void> response =
                controller.removeItem(GUEST_ID, null, 1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getHeaders().getFirst(CartRestController.CART_ID_HEADER)).isEqualTo(GUEST_ID);
        verify(cartService).removeItem(GUEST_KEY, 1L);
    }

    @Test
    void shouldReturnOkWhenMergeCart() {
        MergeCartRequest mergeRequest = new MergeCartRequest(GUEST_ID);
        when(cartMergeService.mergeCart(eq(GUEST_ID), eq(USER_ID)))
                .thenReturn(sampleCartResponse);

        ResponseEntity<ApiResponse<CartResponse>> response =
                controller.mergeCart(USER_ID, mergeRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(cartMergeService).mergeCart(GUEST_ID, USER_ID);
    }

    @Test
    void shouldReturnUnauthorizedWhenMergeWithoutUserId() {
        ResponseEntity<ApiResponse<CartResponse>> response =
                controller.mergeCart(" ", new MergeCartRequest(GUEST_ID));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(cartMergeService);
    }

    @Test
    void shouldGenerateNewGuestCartWhenNoUserIdAndNoCartId() {
        AddCartItemRequest request = new AddCartItemRequest(1L, 1);
        when(cartService.addItem(anyString(), eq(request), eq(null)))
                .thenReturn(sampleCartResponse);

        ResponseEntity<ApiResponse<CartResponse>> response =
                controller.addItem(null, null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(cartService).addItem(key.capture(), any(), eq(null));
        assertThat(key.getValue()).matches("g:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
