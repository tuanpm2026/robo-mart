package com.robomart.cart.controller;

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
import com.robomart.cart.entity.CartKeys;
import com.robomart.cart.service.CartMergeService;
import com.robomart.cart.service.CartService;
import com.robomart.common.dto.ApiResponse;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.validation.Valid;

/**
 * Cart REST API.
 *
 * <p>Identity: {@code X-User-Id} is set exclusively by the API gateway from the validated JWT
 * subject (inbound client values are stripped there) and selects the user's cart. Anonymous callers
 * identify their guest cart with {@code X-Cart-Id}, which must be a UUID. The two live in separate
 * namespaces (see {@link CartKeys}); an authenticated caller always operates on its own cart.
 */
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

        String cartKey = resolveCartKey(userId, cartId);

        CartResponse cart = cartService.addItem(cartKey, request, normalizedUserId(userId));
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

        String cartKey = resolveCartKey(userId, cartId);

        CartResponse cart = cartService.updateItemQuantity(cartKey, productId, request);
        return ResponseEntity.ok()
                .header(CART_ID_HEADER, cart.cartId())
                .body(new ApiResponse<>(cart, getTraceId()));
    }

    @DeleteMapping("/items/{productId}")
    public ResponseEntity<Void> removeItem(
            @RequestHeader(value = CART_ID_HEADER, required = false) String cartId,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @PathVariable Long productId) {

        String cartKey = resolveCartKey(userId, cartId);

        cartService.removeItem(cartKey, productId);
        return ResponseEntity.noContent()
                .header(CART_ID_HEADER, CartKeys.publicId(cartKey))
                .build();
    }

    /**
     * Merges the caller's guest cart into their user cart. Requires the gateway-set
     * {@code X-User-Id}; the body's {@code anonymousCartId} must be a guest cart UUID.
     */
    @PostMapping("/merge")
    public ResponseEntity<ApiResponse<CartResponse>> mergeCart(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestBody @Valid MergeCartRequest request) {

        String authenticatedUserId = normalizedUserId(userId);
        if (authenticatedUserId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        CartResponse cart = cartMergeService.mergeCart(request.anonymousCartId().trim(), authenticatedUserId);
        return ResponseEntity.ok(new ApiResponse<>(cart, getTraceId()));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<CartResponse>> getCart(
            @RequestHeader(value = CART_ID_HEADER, required = false) String cartId,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {

        String cartKey = resolveCartKey(userId, cartId);

        CartResponse cart = cartService.getCart(cartKey);
        return ResponseEntity.ok()
                .header(CART_ID_HEADER, cart.cartId())
                .body(new ApiResponse<>(cart, getTraceId()));
    }

    /**
     * Authenticated callers always get their own user cart ({@code X-Cart-Id} is ignored); anonymous
     * callers get the guest cart named by {@code X-Cart-Id} (validated as a UUID) or a new guest cart.
     */
    private String resolveCartKey(String userId, String cartId) {
        String authenticatedUserId = normalizedUserId(userId);
        if (authenticatedUserId != null) {
            return CartKeys.forUser(authenticatedUserId);
        }
        if (cartId != null && !cartId.isBlank()) {
            return CartKeys.forGuest(cartId.trim());
        }
        return CartKeys.newGuest();
    }

    private static String normalizedUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        return userId.trim();
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
