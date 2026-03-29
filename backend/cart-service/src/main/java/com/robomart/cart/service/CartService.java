package com.robomart.cart.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.robomart.cart.dto.AddCartItemRequest;
import com.robomart.cart.dto.CartResponse;
import com.robomart.cart.dto.UpdateCartItemRequest;
import com.robomart.cart.entity.Cart;
import com.robomart.cart.entity.CartItem;
import com.robomart.cart.exception.CartItemNotFoundException;
import com.robomart.cart.exception.CartNotFoundException;
import com.robomart.cart.mapper.CartMapper;
import com.robomart.cart.repository.CartRepository;

@Service
public class CartService {

    private static final Logger log = LoggerFactory.getLogger(CartService.class);

    private final CartRepository cartRepository;
    private final CartMapper cartMapper;

    public CartService(CartRepository cartRepository, CartMapper cartMapper) {
        this.cartRepository = cartRepository;
        this.cartMapper = cartMapper;
    }

    public CartResponse addItem(String cartId, AddCartItemRequest request) {
        log.debug("Adding item productId={} qty={} to cart={}", request.productId(), request.quantity(), cartId);

        Cart cart = cartRepository.findById(cartId)
                .orElseGet(() -> new Cart(cartId));

        var item = new CartItem(request.productId(), request.productName(),
                request.price(), request.quantity());
        cart.addItem(item);

        cartRepository.save(cart);
        log.debug("Cart {} saved with {} items", cartId, cart.getItems().size());

        return cartMapper.toCartResponse(cart);
    }

    public CartResponse updateItemQuantity(String cartId, Long productId, UpdateCartItemRequest request) {
        log.debug("Updating item productId={} qty={} in cart={}", productId, request.quantity(), cartId);

        Cart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new CartNotFoundException(cartId));

        CartItem item = cart.findItem(productId)
                .orElseThrow(() -> new CartItemNotFoundException(cartId, productId));

        item.setQuantity(request.quantity());
        cart.setUpdatedAt(java.time.Instant.now());

        cartRepository.save(cart);
        return cartMapper.toCartResponse(cart);
    }

    public void removeItem(String cartId, Long productId) {
        log.debug("Removing item productId={} from cart={}", productId, cartId);

        Cart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new CartNotFoundException(cartId));

        if (cart.findItem(productId).isEmpty()) {
            throw new CartItemNotFoundException(cartId, productId);
        }

        cart.removeItem(productId);
        cartRepository.save(cart);
    }

    public CartResponse getCart(String cartId) {
        log.debug("Fetching cart={}", cartId);

        Cart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new CartNotFoundException(cartId));

        return cartMapper.toCartResponse(cart);
    }
}
