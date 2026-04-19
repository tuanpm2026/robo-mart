package com.robomart.cart.unit.controller;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import io.micrometer.tracing.Tracer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartControllerTest {

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
    void shouldReturnCreatedWhenAddItemWithUserId() {
        AddCartItemRequest request = new AddCartItemRequest(1L, "Test Product", BigDecimal.valueOf(29.99), 2);
        when(cartService.addItem(eq("user-001"), eq(request), eq("user-001")))
                .thenReturn(sampleCartResponse);

        ResponseEntity<ApiResponse<CartResponse>> response =
                controller.addItem(null, "user-001", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void shouldReturnCreatedWhenAddItemWithCartId() {
        AddCartItemRequest request = new AddCartItemRequest(1L, "Test Product", BigDecimal.valueOf(29.99), 1);
        when(cartService.addItem(eq("cart-abc"), eq(request), eq(null)))
                .thenReturn(sampleCartResponse);

        ResponseEntity<ApiResponse<CartResponse>> response =
                controller.addItem("cart-abc", null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void shouldReturnOkWhenGetCartWithUserId() {
        when(cartService.getCart("user-001")).thenReturn(sampleCartResponse);

        ResponseEntity<ApiResponse<CartResponse>> response =
                controller.getCart(null, "user-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isEqualTo(sampleCartResponse);
    }

    @Test
    void shouldReturnOkWhenGetCartWithCartId() {
        when(cartService.getCart("cart-xyz")).thenReturn(sampleCartResponse);

        ResponseEntity<ApiResponse<CartResponse>> response =
                controller.getCart("cart-xyz", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldReturnOkWhenUpdateItemQuantity() {
        UpdateCartItemRequest request = new UpdateCartItemRequest(3);
        CartItemResponse item = new CartItemResponse(1L, "Test Product",
                BigDecimal.valueOf(29.99), 3, BigDecimal.valueOf(89.97));
        CartResponse updatedCart = new CartResponse("user-001", List.of(item), 3, BigDecimal.valueOf(89.97));
        when(cartService.updateItemQuantity(eq("user-001"), eq(1L), eq(request)))
                .thenReturn(updatedCart);

        ResponseEntity<ApiResponse<CartResponse>> response =
                controller.updateItemQuantity(null, "user-001", 1L, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data().totalItems()).isEqualTo(3);
    }

    @Test
    void shouldReturnNoContentWhenRemoveItem() {
        ResponseEntity<Void> response =
                controller.removeItem("cart-abc", null, 1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(cartService).removeItem("cart-abc", 1L);
    }

    @Test
    void shouldReturnOkWhenMergeCart() {
        MergeCartRequest mergeRequest = new MergeCartRequest("anon-cart-123");
        when(cartMergeService.mergeCart(eq("anon-cart-123"), eq("user-001")))
                .thenReturn(sampleCartResponse);

        ResponseEntity<ApiResponse<CartResponse>> response =
                controller.mergeCart("user-001", mergeRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(cartMergeService).mergeCart("anon-cart-123", "user-001");
    }

    @Test
    void shouldGenerateNewCartIdWhenNoUserIdAndNoCartId() {
        AddCartItemRequest request = new AddCartItemRequest(1L, "Test Product", BigDecimal.valueOf(29.99), 1);
        when(cartService.addItem(anyString(), eq(request), eq(null)))
                .thenReturn(sampleCartResponse);

        ResponseEntity<ApiResponse<CartResponse>> response =
                controller.addItem(null, null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(cartService).addItem(anyString(), eq(request), eq(null));
    }
}
