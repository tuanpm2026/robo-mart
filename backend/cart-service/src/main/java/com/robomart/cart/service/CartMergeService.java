package com.robomart.cart.service;

import org.springframework.stereotype.Service;

import com.robomart.cart.config.CartProperties;
import com.robomart.cart.dto.CartResponse;
import com.robomart.cart.entity.CartKeys;
import com.robomart.cart.mapper.CartMapper;
import com.robomart.cart.repository.CartRepository;

@Service
public class CartMergeService {
    private final CartRepository carts;
    private final CartMapper mapper;

    public CartMergeService(CartRepository carts, CartMapper mapper, CartProperties properties) {
        this.carts = carts;
        this.mapper = mapper;
    }

    public CartResponse mergeCart(String guestId, String userId) {
        String guest = CartKeys.forGuest(guestId);
        String user = CartKeys.forUser(userId);
        return mapper.toCartResponse(carts.merge(guest, user, userId));
    }
}
