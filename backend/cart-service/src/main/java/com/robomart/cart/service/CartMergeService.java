package com.robomart.cart.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.robomart.cart.config.CartProperties;
import com.robomart.cart.dto.CartResponse;
import com.robomart.cart.entity.Cart;
import com.robomart.cart.entity.CartItem;
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

    public CartResponse mergeCart(String anonymousCartId, String authenticatedUserId) {
        log.debug("Merging anonymous cart={} into authenticated user={}", anonymousCartId, authenticatedUserId);

        // Self-merge is a no-op
        if (anonymousCartId.equals(authenticatedUserId)) {
            log.debug("Self-merge detected, returning existing cart for user={}", authenticatedUserId);
            return getOrCreateCart(authenticatedUserId);
        }

        var anonymousCartOpt = cartRepository.findById(anonymousCartId);
        if (anonymousCartOpt.isEmpty()) {
            log.debug("Anonymous cart={} not found, returning authenticated cart", anonymousCartId);
            return getOrCreateCart(authenticatedUserId);
        }

        Cart anonymousCart = anonymousCartOpt.get();
        if (anonymousCart.getItems().isEmpty()) {
            log.debug("Anonymous cart={} is empty, deleting and returning authenticated cart", anonymousCartId);
            cartRepository.deleteById(anonymousCartId);
            return getOrCreateCart(authenticatedUserId);
        }

        Cart authCart = cartRepository.findById(authenticatedUserId)
                .orElseGet(() -> {
                    Cart newCart = new Cart(authenticatedUserId);
                    newCart.setUserId(authenticatedUserId);
                    return newCart;
                });

        // Reuse Cart.addItem() which handles duplicate detection and quantity summing (caps at 9999)
        for (CartItem item : anonymousCart.getItems()) {
            authCart.addItem(new CartItem(item.getProductId(), item.getProductName(),
                    item.getPrice(), item.getQuantity()));
        }

        authCart.setTimeToLive(cartProperties.getTtlSeconds());
        cartRepository.save(authCart);

        cartRepository.deleteById(anonymousCartId);
        log.info("Merged {} items from anonymous cart={} into user={}, total items={}",
                anonymousCart.getItems().size(), anonymousCartId, authenticatedUserId, authCart.getTotalItems());

        return cartMapper.toCartResponse(authCart);
    }

    private CartResponse getOrCreateCart(String userId) {
        Cart cart = cartRepository.findById(userId)
                .orElseGet(() -> {
                    Cart newCart = new Cart(userId);
                    newCart.setUserId(userId);
                    newCart.setTimeToLive(cartProperties.getTtlSeconds());
                    cartRepository.save(newCart);
                    return newCart;
                });
        return cartMapper.toCartResponse(cart);
    }
}
