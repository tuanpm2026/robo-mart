package com.robomart.cart.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.robomart.cart.config.CartProperties;
import com.robomart.cart.dto.CartResponse;
import com.robomart.cart.entity.Cart;
import com.robomart.cart.entity.CartItem;
import com.robomart.cart.entity.CartKeys;
import com.robomart.cart.mapper.CartMapper;
import com.robomart.cart.repository.CartRepository;

@Service
public class CartMergeService {

    private static final Logger log = LoggerFactory.getLogger(CartMergeService.class);

    private final CartRepository cartRepository;
    private final CartMapper cartMapper;
    private final CartProperties cartProperties;

    public CartMergeService(CartRepository cartRepository, CartMapper cartMapper, CartProperties cartProperties) {
        this.cartRepository = cartRepository;
        this.cartMapper = cartMapper;
        this.cartProperties = cartProperties;
    }

    /**
     * Merges a guest cart into the authenticated user's cart and deletes the guest cart.
     *
     * <p>Only guest carts ({@code g:{uuid}}) can be merged from, and only into the caller's own user
     * cart ({@code u:{sub}}): the guest id is validated as a UUID and resolved in the guest
     * namespace, so another user's cart can never be read, merged or deleted through this call.
     *
     * @param guestCartId         public guest cart id (UUID) held by the client
     * @param authenticatedUserId JWT subject relayed by the gateway
     */
    public CartResponse mergeCart(String guestCartId, String authenticatedUserId) {
        String userKey = CartKeys.forUser(authenticatedUserId);
        String guestKey = CartKeys.forGuest(guestCartId);
        log.debug("Merging guest cart={} into user cart={}", guestKey, userKey);

        var guestCartOpt = cartRepository.findById(guestKey);
        if (guestCartOpt.isEmpty()) {
            log.debug("Guest cart={} not found, returning user cart", guestKey);
            return getOrCreateCart(userKey, authenticatedUserId);
        }

        Cart guestCart = guestCartOpt.get();
        if (guestCart.getItems().isEmpty()) {
            log.debug("Guest cart={} is empty, deleting and returning user cart", guestKey);
            cartRepository.deleteById(guestKey);
            return getOrCreateCart(userKey, authenticatedUserId);
        }

        Cart userCart = cartRepository.findById(userKey)
                .orElseGet(() -> {
                    Cart newCart = new Cart(userKey);
                    newCart.setUserId(authenticatedUserId);
                    return newCart;
                });

        // Reuse Cart.addItem() which handles duplicate detection and quantity summing (capped)
        for (CartItem item : guestCart.getItems()) {
            userCart.addItem(new CartItem(item.getProductId(), item.getProductName(),
                    item.getPrice(), item.getQuantity()));
        }

        userCart.setTimeToLive(cartProperties.getTtlSeconds());
        cartRepository.save(userCart);

        cartRepository.deleteById(guestKey);
        log.info("Merged {} items from guest cart={} into user cart={}, total items={}",
                guestCart.getItems().size(), guestKey, userKey, userCart.getTotalItems());

        return cartMapper.toCartResponse(userCart);
    }

    private CartResponse getOrCreateCart(String userKey, String userId) {
        Cart cart = cartRepository.findById(userKey)
                .orElseGet(() -> {
                    Cart newCart = new Cart(userKey);
                    newCart.setUserId(userId);
                    newCart.setTimeToLive(cartProperties.getTtlSeconds());
                    cartRepository.save(newCart);
                    return newCart;
                });
        return cartMapper.toCartResponse(cart);
    }
}
