package com.robomart.cart.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

@RedisHash("cart")
public class Cart {

    @Id
    private String id;
    private List<CartItem> items = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;

    public Cart() {
    }

    public Cart(String id) {
        this.id = id;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<CartItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public void setItems(List<CartItem> items) {
        this.items = items;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Optional<CartItem> findItem(Long productId) {
        return items.stream()
                .filter(item -> item.getProductId().equals(productId))
                .findFirst();
    }

    private static final int MAX_ITEM_QUANTITY = 9999;

    public void addItem(CartItem item) {
        Optional<CartItem> existing = findItem(item.getProductId());
        if (existing.isPresent()) {
            CartItem existingItem = existing.get();
            int newQuantity = existingItem.getQuantity() + item.getQuantity();
            if (newQuantity > MAX_ITEM_QUANTITY) {
                newQuantity = MAX_ITEM_QUANTITY;
            }
            existingItem.setQuantity(newQuantity);
            existingItem.setPrice(item.getPrice());
            existingItem.setProductName(item.getProductName());
        } else {
            items.add(item);
        }
        this.updatedAt = Instant.now();
    }

    public void removeItem(Long productId) {
        items.removeIf(item -> item.getProductId().equals(productId));
        this.updatedAt = Instant.now();
    }

    public int getTotalItems() {
        return items.stream().mapToInt(CartItem::getQuantity).sum();
    }

    public BigDecimal getTotalPrice() {
        return items.stream()
                .map(CartItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
