package com.robomart.cart.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.robomart.cart.dto.AddCartItemRequest;
import com.robomart.cart.dto.CartResponse;
import com.robomart.cart.dto.MergeCartRequest;
import com.robomart.cart.dto.UpdateCartItemRequest;
import com.robomart.cart.service.CartMergeService;
import com.robomart.cart.service.CartService;
import com.robomart.common.dto.ApiResponse;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/cart")
public class CartRestController {

    public static final String CART_ID_HEADER = "X-Cart-Id";
    public static final String USER_ID_HEADER = "X-User-Id";

    private final CartService cartService;
    private final CartMergeService cartMergeService;
    private final Tracer tracer;

    public CartRestController(CartService cartService, CartMergeService cartMergeService, Tracer tracer) {
        this.cartService = cartService;
        this.cartMergeService = cartMergeService;
        this.tracer = tracer;
    }

    @PostMapping("/items")
    public ResponseEntity<ApiResponse<CartResponse>> addItem(
            @RequestHeader(value = CART_ID_HEADER, required = false) String cartId,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestBody @Valid AddCartItemRequest request) {

        String resolvedCartId = resolveCartId(userId, cartId);

        CartResponse cart = cartService.addItem(resolvedCartId, request, userId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(CART_ID_HEADER, cart.cartId())
                .body(new ApiResponse<>(cart, getTraceId()));
    }

    @PutMapping("/items/{productId}")
    public ResponseEntity<ApiResponse<CartResponse>> updateItemQuantity(
            @RequestHeader(value = CART_ID_HEADER, required = false) String cartId,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @PathVariable Long productId,
            @RequestBody @Valid UpdateCartItemRequest request) {

        String resolvedCartId = resolveCartId(userId, cartId);

        CartResponse cart = cartService.updateItemQuantity(resolvedCartId, productId, request);
        return ResponseEntity.ok()
                .header(CART_ID_HEADER, cart.cartId())
                .body(new ApiResponse<>(cart, getTraceId()));
    }

    @DeleteMapping("/items/{productId}")
    public ResponseEntity<Void> removeItem(
            @RequestHeader(value = CART_ID_HEADER, required = false) String cartId,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @PathVariable Long productId) {

        String resolvedCartId = resolveCartId(userId, cartId);

        cartService.removeItem(resolvedCartId, productId);
        return ResponseEntity.noContent()
                .header(CART_ID_HEADER, resolvedCartId)
                .build();
    }

    @PostMapping("/merge")
    public ResponseEntity<ApiResponse<CartResponse>> mergeCart(
            @RequestHeader(value = USER_ID_HEADER) String userId,
            @RequestBody @Valid MergeCartRequest request) {

        CartResponse cart = cartMergeService.mergeCart(request.anonymousCartId(), userId);
        return ResponseEntity.ok(new ApiResponse<>(cart, getTraceId()));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<CartResponse>> getCart(
            @RequestHeader(value = CART_ID_HEADER, required = false) String cartId,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {

        String resolvedCartId = resolveCartId(userId, cartId);

        CartResponse cart = cartService.getCart(resolvedCartId);
        return ResponseEntity.ok()
                .header(CART_ID_HEADER, cart.cartId())
                .body(new ApiResponse<>(cart, getTraceId()));
    }

    private String resolveCartId(String userId, String cartId) {
        if (userId != null && !userId.isBlank()) {
            return userId.trim();
        }
        if (cartId != null && !cartId.isBlank()) {
            return cartId.trim();
        }
        return UUID.randomUUID().toString();
    }

    private String getTraceId() {
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            TraceContext context = currentSpan.context();
            if (context != null) {
                return context.traceId();
            }
        }
        return "";
    }
}
