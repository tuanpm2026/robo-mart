package com.robomart.cart.service;

import org.springframework.stereotype.Service;

import com.robomart.cart.config.CartProperties;
import com.robomart.cart.dto.AddCartItemRequest;
import com.robomart.cart.dto.CartResponse;
import com.robomart.cart.dto.UpdateCartItemRequest;
import com.robomart.cart.entity.CartItem;
import com.robomart.cart.mapper.CartMapper;
import com.robomart.cart.repository.CartRepository;

/** Catalog lookup precedes a single atomic Redis operation; responses retain the existing contract. */
@Service
public class CartService {
    private final CartRepository carts;
    private final CartMapper mapper;
    private final ProductCatalogService catalog;

    public CartService(CartRepository carts, CartMapper mapper, CartProperties properties,
            ProductCatalogService catalog) {
        this.carts = carts;
        this.mapper = mapper;
        this.catalog = catalog;
    }

    public CartResponse addItem(String key, AddCartItemRequest request, String user) {
        var product = catalog.getProduct(request.productId());
        return mapper.toCartResponse(carts.add(key,
                new CartItem(request.productId(), product.name(), product.price(), request.quantity()), user));
    }

    public CartResponse updateItemQuantity(String key, Long product, UpdateCartItemRequest request) {
        return mapper.toCartResponse(carts.setQuantity(key, product, request.quantity()));
    }

    public void removeItem(String key, Long product) {
        carts.remove(key, product);
    }

    public CartResponse getCart(String key) {
        return mapper.toCartResponse(carts.getAndRefresh(key));
    }
}
