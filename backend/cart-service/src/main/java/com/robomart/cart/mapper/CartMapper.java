package com.robomart.cart.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.robomart.cart.dto.CartItemResponse;
import com.robomart.cart.dto.CartResponse;
import com.robomart.cart.entity.Cart;
import com.robomart.cart.entity.CartItem;

@Mapper(componentModel = "spring")
public interface CartMapper {

    @Mapping(source = "id", target = "cartId")
    CartResponse toCartResponse(Cart cart);

    CartItemResponse toCartItemResponse(CartItem item);

    List<CartItemResponse> toCartItemResponseList(List<CartItem> items);
}
