package com.robomart.cart.unit.event;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.robomart.cart.entity.Cart;
import com.robomart.cart.entity.CartItem;
import com.robomart.cart.event.consumer.ProductEventConsumer;
import com.robomart.cart.repository.CartRepository;
import com.robomart.events.product.ProductUpdatedEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductEventConsumerTest {

    @Mock
    private CartRepository cartRepository;

    private ProductEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ProductEventConsumer(cartRepository);
    }

    @Test
    void shouldUpdatePriceInCartWhenProductPriceChanges() {
        Cart cart = new Cart("cart-1");
        cart.addItem(new CartItem(42L, "Old Product Name", new BigDecimal("29.99"), 2));

        when(cartRepository.findAll()).thenReturn(List.of(cart));

        var event = createProductUpdatedEvent(42L, "Updated Product Name", "39.99");
        consumer.onProductUpdated(event);

        ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(captor.capture());

        Cart savedCart = captor.getValue();
        CartItem updatedItem = savedCart.getItems().getFirst();
        assertThat(updatedItem.getPrice()).isEqualByComparingTo(new BigDecimal("39.99"));
        assertThat(updatedItem.getProductName()).isEqualTo("Updated Product Name");
    }

    @Test
    void shouldUpdateMultipleCartsWhenProductPriceChanges() {
        Cart cart1 = new Cart("cart-1");
        cart1.addItem(new CartItem(42L, "Product", new BigDecimal("29.99"), 1));

        Cart cart2 = new Cart("cart-2");
        cart2.addItem(new CartItem(42L, "Product", new BigDecimal("29.99"), 3));

        when(cartRepository.findAll()).thenReturn(List.of(cart1, cart2));

        var event = createProductUpdatedEvent(42L, "Product", "19.99");
        consumer.onProductUpdated(event);

        verify(cartRepository).save(cart1);
        verify(cartRepository).save(cart2);
    }

    @Test
    void shouldNotSaveCartWhenProductNotInCart() {
        Cart cart = new Cart("cart-1");
        cart.addItem(new CartItem(99L, "Other Product", new BigDecimal("10.00"), 1));

        when(cartRepository.findAll()).thenReturn(List.of(cart));

        var event = createProductUpdatedEvent(42L, "Updated Product", "39.99");
        consumer.onProductUpdated(event);

        verify(cartRepository, never()).save(cart);
    }

    @Test
    void shouldHandleEmptyCartListGracefully() {
        when(cartRepository.findAll()).thenReturn(List.of());

        var event = createProductUpdatedEvent(42L, "Product", "39.99");
        consumer.onProductUpdated(event);

        verify(cartRepository, never()).save(org.mockito.ArgumentMatchers.any(Cart.class));
    }

    @Test
    void shouldUpdateOnlyMatchingItemInCartWithMultipleProducts() {
        Cart cart = new Cart("cart-1");
        cart.addItem(new CartItem(42L, "Product A", new BigDecimal("29.99"), 1));
        cart.addItem(new CartItem(99L, "Product B", new BigDecimal("10.00"), 2));

        when(cartRepository.findAll()).thenReturn(List.of(cart));

        var event = createProductUpdatedEvent(42L, "Updated Product A", "39.99");
        consumer.onProductUpdated(event);

        ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(captor.capture());

        Cart savedCart = captor.getValue();
        CartItem itemA = savedCart.getItems().stream()
                .filter(i -> i.getProductId().equals(42L)).findFirst().orElseThrow();
        CartItem itemB = savedCart.getItems().stream()
                .filter(i -> i.getProductId().equals(99L)).findFirst().orElseThrow();

        assertThat(itemA.getPrice()).isEqualByComparingTo(new BigDecimal("39.99"));
        assertThat(itemA.getProductName()).isEqualTo("Updated Product A");
        assertThat(itemB.getPrice()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(itemB.getProductName()).isEqualTo("Product B");
    }

    private ProductUpdatedEvent createProductUpdatedEvent(long productId, String name, String price) {
        return ProductUpdatedEvent.newBuilder()
                .setEventId("evt-" + System.nanoTime())
                .setEventType("PRODUCT_UPDATED")
                .setAggregateId(String.valueOf(productId))
                .setAggregateType("PRODUCT")
                .setTimestamp(java.time.Instant.now())
                .setVersion(1)
                .setProductId(productId)
                .setSku("SKU-" + productId)
                .setName(name)
                .setPrice(price)
                .setCategoryId(1L)
                .setCategoryName("Electronics")
                .setStockQuantity(100)
                .build();
    }
}
