package com.robomart.cart.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.robomart.cart.client.ProductSnapshot;
import com.robomart.cart.config.CartProperties;
import com.robomart.cart.dto.AddCartItemRequest;
import com.robomart.cart.dto.CartResponse;
import com.robomart.cart.dto.UpdateCartItemRequest;
import com.robomart.cart.entity.Cart;
import com.robomart.cart.entity.CartItem;
import com.robomart.cart.exception.CartItemNotFoundException;
import com.robomart.cart.exception.CartNotFoundException;
import com.robomart.cart.mapper.CartMapper;
import com.robomart.cart.repository.CartRepository;

/**
 * Cart operations keyed by a namespaced cart key (see {@link com.robomart.cart.entity.CartKeys}).
 */
@Service
public class CartService {

    private static final Logger log = LoggerFactory.getLogger(CartService.class);

    private final CartRepository cartRepository;
    private final CartMapper cartMapper;
    private final CartProperties cartProperties;
    private final ProductCatalogService productCatalogService;

    public CartService(CartRepository cartRepository, CartMapper cartMapper, CartProperties cartProperties,
                       ProductCatalogService productCatalogService) {
        this.cartRepository = cartRepository;
        this.cartMapper = cartMapper;
        this.cartProperties = cartProperties;
        this.productCatalogService = productCatalogService;
    }

    /**
     * Adds a product to the cart using the name and price from product-service; any client-supplied
     * {@code price}/{@code productName} in the request is ignored.
     */
    public CartResponse addItem(String cartKey, AddCartItemRequest request, String userId) {
        log.debug("Adding item productId={} qty={}", request.productId(), request.quantity());

        ProductSnapshot product = productCatalogService.getProduct(request.productId());

        Cart cart = cartRepository.findById(cartKey)
                .orElseGet(() -> {
                    Cart newCart = new Cart(cartKey);
                    if (userId != null) {
                        newCart.setUserId(userId);
                    }
                    return newCart;
                });

        var item = new CartItem(request.productId(), product.name(),
                product.price(), request.quantity());
        cart.addItem(item);

        refreshTtl(cart);
        cartRepository.save(cart);
        log.debug("Cart saved with {} items, TTL={}s", cart.getItems().size(), cart.getTimeToLive());

        return cartMapper.toCartResponse(cart);
    }

    public CartResponse updateItemQuantity(String cartKey, Long productId, UpdateCartItemRequest request) {
        log.debug("Updating item productId={} qty={}", productId, request.quantity());

        Cart cart = cartRepository.findById(cartKey)
                .orElseThrow(() -> new CartNotFoundException(cartKey));

        CartItem item = cart.findItem(productId)
                .orElseThrow(() -> new CartItemNotFoundException(cartKey, productId));

        item.setQuantity(request.quantity());
        cart.setUpdatedAt(java.time.Instant.now());

        refreshTtl(cart);
        cartRepository.save(cart);
        return cartMapper.toCartResponse(cart);
    }

    public void removeItem(String cartKey, Long productId) {
        log.debug("Removing item productId={}", productId);

        Cart cart = cartRepository.findById(cartKey)
                .orElseThrow(() -> new CartNotFoundException(cartKey));

        if (cart.findItem(productId).isEmpty()) {
            throw new CartItemNotFoundException(cartKey, productId);
        }

        cart.removeItem(productId);
        refreshTtl(cart);
        cartRepository.save(cart);
    }

    public CartResponse getCart(String cartKey) {
        log.debug("Fetching cart");

        Cart cart = cartRepository.findById(cartKey)
                .orElseThrow(() -> new CartNotFoundException(cartKey));

        refreshTtl(cart);
        cartRepository.save(cart);

        return cartMapper.toCartResponse(cart);
    }

    private void refreshTtl(Cart cart) {
        cart.setTimeToLive(cartProperties.getTtlSeconds());
    }
}
