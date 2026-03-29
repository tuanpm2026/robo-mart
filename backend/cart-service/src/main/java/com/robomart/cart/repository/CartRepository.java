package com.robomart.cart.repository;

import org.springframework.data.repository.CrudRepository;

import com.robomart.cart.entity.Cart;

public interface CartRepository extends CrudRepository<Cart, String> {
}
