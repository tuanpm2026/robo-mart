package com.robomart.cart.unit;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.robomart.cart.client.ProductSnapshot;
import com.robomart.cart.config.CartProperties;
import com.robomart.cart.dto.AddCartItemRequest;
import com.robomart.cart.dto.CartResponse;
import com.robomart.cart.dto.UpdateCartItemRequest;
import com.robomart.cart.entity.Cart;
import com.robomart.cart.entity.CartItem;
import com.robomart.cart.exception.CartItemNotFoundException;
import com.robomart.cart.exception.CartNotFoundException;
import com.robomart.cart.exception.ProductNotFoundException;
import com.robomart.cart.mapper.CartMapper;
import com.robomart.cart.mapper.CartMapperImpl;
import com.robomart.cart.repository.CartRepository;
import com.robomart.cart.service.CartService;
import com.robomart.cart.service.ProductCatalogService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private ProductCatalogService productCatalogService;

    /** Server-side product catalog as seen through product-service. */
    private final Map<Long, ProductSnapshot> catalog = new HashMap<>();

    private CartMapper cartMapper;
    private CartProperties cartProperties;
    private CartService cartService;

    @BeforeEach
    void setUp() {
        cartMapper = new CartMapperImpl();
        cartProperties = new CartProperties();
        cartProperties.setTtlMinutes(1440);
        cartService = new CartService(cartRepository, cartMapper, cartProperties, productCatalogService);
        catalog.put(1L, new ProductSnapshot(1L, "Product", new BigDecimal("10.00")));
        catalog.put(2L, new ProductSnapshot(2L, "Product B", new BigDecimal("20.00")));
        lenient().when(productCatalogService.getProduct(anyLong())).thenAnswer(inv -> {
            Long productId = inv.getArgument(0);
            ProductSnapshot product = catalog.get(productId);
            if (product == null) {
                throw new ProductNotFoundException(productId);
            }
            return product;
        });
    }

    @Test
    void shouldUseServerPriceAndNameAndIgnoreClientSuppliedValues() {
        String cartId = "g:550e8400-e29b-41d4-a716-446655440000";
        catalog.put(7L, new ProductSnapshot(7L, "Server Name", new BigDecimal("99.99")));
        var request = new AddCartItemRequest(7L, "Client Name", new BigDecimal("0.01"), 2);
        when(cartRepository.findById(cartId)).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        CartResponse response = cartService.addItem(cartId, request, null);

        assertThat(response.items().getFirst().productName()).isEqualTo("Server Name");
        assertThat(response.items().getFirst().price()).isEqualByComparingTo("99.99");
        assertThat(response.totalPrice()).isEqualByComparingTo("199.98");
    }

    @Test
    void shouldRefreshExistingLinePriceFromServerOnAdd() {
        String cartId = "g:550e8400-e29b-41d4-a716-446655440000";
        Cart existingCart = new Cart(cartId);
        existingCart.addItem(new CartItem(2L, "Stale", new BigDecimal("0.01"), 1));
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(existingCart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        CartResponse response = cartService.addItem(cartId, new AddCartItemRequest(2L, 1), null);

        assertThat(response.items().getFirst().price()).isEqualByComparingTo("20.00");
        assertThat(response.totalPrice()).isEqualByComparingTo("40.00");
    }

    @Test
    void shouldRejectUnknownProductWithoutTouchingCart() {
        var request = new AddCartItemRequest(404L, "Ghost", new BigDecimal("1.00"), 1);

        assertThatThrownBy(() -> cartService.addItem("g:550e8400-e29b-41d4-a716-446655440000", request, null))
                .isInstanceOf(ProductNotFoundException.class);
        verify(cartRepository, never()).save(any(Cart.class));
    }

    @Test
    void shouldReturnPublicCartIdWithoutNamespacePrefix() {
        String cartKey = "u:user-42";
        when(cartRepository.findById(cartKey)).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        CartResponse response = cartService.addItem(cartKey, new AddCartItemRequest(1L, 1), "user-42");

        assertThat(response.cartId()).isEqualTo("user-42");
    }

    @Test
    void shouldCreateNewCartWhenAddingItemToNonExistentCart() {
        String cartId = "cart-123";
        catalog.put(1L, new ProductSnapshot(1L, "Test Product", new BigDecimal("29.99")));
        var request = new AddCartItemRequest(1L, "Test Product", new BigDecimal("29.99"), 2);
        when(cartRepository.findById(cartId)).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        CartResponse response = cartService.addItem(cartId, request, null);

        assertThat(response.cartId()).isEqualTo(cartId);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().productId()).isEqualTo(1L);
        assertThat(response.items().getFirst().productName()).isEqualTo("Test Product");
        assertThat(response.items().getFirst().quantity()).isEqualTo(2);
        assertThat(response.totalItems()).isEqualTo(2);
        assertThat(response.totalPrice()).isEqualByComparingTo(new BigDecimal("59.98"));
        verify(cartRepository).save(any(Cart.class));
    }

    @Test
    void shouldAddItemToExistingCart() {
        String cartId = "cart-123";
        Cart existingCart = new Cart(cartId);
        existingCart.addItem(new CartItem(1L, "Product A", new BigDecimal("10.00"), 1));

        var request = new AddCartItemRequest(2L, "Product B", new BigDecimal("20.00"), 3);
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(existingCart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        CartResponse response = cartService.addItem(cartId, request, null);

        assertThat(response.items()).hasSize(2);
        assertThat(response.totalItems()).isEqualTo(4);
        assertThat(response.totalPrice()).isEqualByComparingTo(new BigDecimal("70.00"));
    }

    @Test
    void shouldIncrementQuantityWhenAddingSameProduct() {
        String cartId = "cart-123";
        Cart existingCart = new Cart(cartId);
        existingCart.addItem(new CartItem(1L, "Product A", new BigDecimal("10.00"), 2));

        var request = new AddCartItemRequest(1L, "Product A", new BigDecimal("10.00"), 3);
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(existingCart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        CartResponse response = cartService.addItem(cartId, request, null);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().quantity()).isEqualTo(5);
        assertThat(response.totalItems()).isEqualTo(5);
        assertThat(response.totalPrice()).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    void shouldUpdateItemQuantityWhenCartAndItemExist() {
        String cartId = "cart-123";
        Cart cart = new Cart(cartId);
        cart.addItem(new CartItem(1L, "Product A", new BigDecimal("10.00"), 2));

        var request = new UpdateCartItemRequest(5);
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(cart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        CartResponse response = cartService.updateItemQuantity(cartId, 1L, request);

        assertThat(response.items().getFirst().quantity()).isEqualTo(5);
        assertThat(response.totalPrice()).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    void shouldThrowCartNotFoundExceptionWhenUpdatingNonExistentCart() {
        String cartId = "nonexistent";
        var request = new UpdateCartItemRequest(3);
        when(cartRepository.findById(cartId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.updateItemQuantity(cartId, 1L, request))
                .isInstanceOf(CartNotFoundException.class)
                .hasMessageContaining(cartId);
    }

    @Test
    void shouldThrowCartItemNotFoundExceptionWhenUpdatingNonExistentItem() {
        String cartId = "cart-123";
        Cart cart = new Cart(cartId);
        var request = new UpdateCartItemRequest(3);
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(cart));

        assertThatThrownBy(() -> cartService.updateItemQuantity(cartId, 99L, request))
                .isInstanceOf(CartItemNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void shouldRemoveItemFromCart() {
        String cartId = "cart-123";
        Cart cart = new Cart(cartId);
        cart.addItem(new CartItem(1L, "Product A", new BigDecimal("10.00"), 1));
        cart.addItem(new CartItem(2L, "Product B", new BigDecimal("20.00"), 1));

        when(cartRepository.findById(cartId)).thenReturn(Optional.of(cart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        cartService.removeItem(cartId, 1L);

        assertThat(cart.getItems()).hasSize(1);
        assertThat(cart.getItems().getFirst().getProductId()).isEqualTo(2L);
        verify(cartRepository).save(cart);
    }

    @Test
    void shouldThrowCartNotFoundExceptionWhenRemovingFromNonExistentCart() {
        String cartId = "nonexistent";
        when(cartRepository.findById(cartId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.removeItem(cartId, 1L))
                .isInstanceOf(CartNotFoundException.class);
    }

    @Test
    void shouldThrowCartItemNotFoundExceptionWhenRemovingNonExistentItem() {
        String cartId = "cart-123";
        Cart cart = new Cart(cartId);
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(cart));

        assertThatThrownBy(() -> cartService.removeItem(cartId, 99L))
                .isInstanceOf(CartItemNotFoundException.class);
    }

    @Test
    void shouldReturnCartWhenValidCartId() {
        String cartId = "cart-123";
        Cart cart = new Cart(cartId);
        cart.addItem(new CartItem(1L, "Product A", new BigDecimal("15.50"), 3));
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(cart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        CartResponse response = cartService.getCart(cartId);

        assertThat(response.cartId()).isEqualTo(cartId);
        assertThat(response.items()).hasSize(1);
        assertThat(response.totalItems()).isEqualTo(3);
        assertThat(response.totalPrice()).isEqualByComparingTo(new BigDecimal("46.50"));
    }

    @Test
    void shouldThrowCartNotFoundExceptionWhenGettingNonExistentCart() {
        String cartId = "nonexistent";
        when(cartRepository.findById(cartId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.getCart(cartId))
                .isInstanceOf(CartNotFoundException.class);
    }

    @Test
    void shouldReturnEmptyCartWhenNoItems() {
        String cartId = "cart-empty";
        Cart cart = new Cart(cartId);
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(cart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        CartResponse response = cartService.getCart(cartId);

        assertThat(response.cartId()).isEqualTo(cartId);
        assertThat(response.items()).isEmpty();
        assertThat(response.totalItems()).isZero();
        assertThat(response.totalPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // === Story 2.2: TTL and userId tests ===

    @Test
    void shouldSetTtlFromPropertiesOnAddItem() {
        String cartId = "cart-ttl";
        var request = new AddCartItemRequest(1L, "Product", new BigDecimal("10.00"), 1);
        when(cartRepository.findById(cartId)).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        cartService.addItem(cartId, request, null);

        ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(captor.capture());
        assertThat(captor.getValue().getTimeToLive()).isEqualTo(1440 * 60L);
    }

    @Test
    void shouldSetTtlOnUpdateItemQuantity() {
        String cartId = "cart-ttl";
        Cart cart = new Cart(cartId);
        cart.addItem(new CartItem(1L, "Product", new BigDecimal("10.00"), 1));
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(cart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        cartService.updateItemQuantity(cartId, 1L, new UpdateCartItemRequest(3));

        ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(captor.capture());
        assertThat(captor.getValue().getTimeToLive()).isEqualTo(1440 * 60L);
    }

    @Test
    void shouldSetTtlOnRemoveItem() {
        String cartId = "cart-ttl";
        Cart cart = new Cart(cartId);
        cart.addItem(new CartItem(1L, "Product", new BigDecimal("10.00"), 1));
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(cart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        cartService.removeItem(cartId, 1L);

        ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(captor.capture());
        assertThat(captor.getValue().getTimeToLive()).isEqualTo(1440 * 60L);
    }

    @Test
    void shouldResetTtlOnGetCart() {
        String cartId = "cart-ttl";
        Cart cart = new Cart(cartId);
        cart.addItem(new CartItem(1L, "Product", new BigDecimal("10.00"), 1));
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(cart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        cartService.getCart(cartId);

        ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(captor.capture());
        assertThat(captor.getValue().getTimeToLive()).isEqualTo(1440 * 60L);
    }

    @Test
    void shouldUseTtlFromProperties() {
        cartProperties.setTtlMinutes(30);
        CartService serviceWith30MinTtl = new CartService(cartRepository, cartMapper, cartProperties,
                productCatalogService);

        String cartId = "cart-custom-ttl";
        var request = new AddCartItemRequest(1L, "Product", new BigDecimal("10.00"), 1);
        when(cartRepository.findById(cartId)).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        serviceWith30MinTtl.addItem(cartId, request, null);

        ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(captor.capture());
        assertThat(captor.getValue().getTimeToLive()).isEqualTo(30 * 60L);
    }

    @Test
    void shouldUseUserIdAsCartIdWhenProvided() {
        String userId = "user-42";
        var request = new AddCartItemRequest(1L, "Product", new BigDecimal("10.00"), 1);
        when(cartRepository.findById(userId)).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        CartResponse response = cartService.addItem(userId, request, userId);

        assertThat(response.cartId()).isEqualTo(userId);
        ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
    }

    @Test
    void shouldNotSetUserIdWhenNullProvided() {
        String cartId = "cart-anon";
        var request = new AddCartItemRequest(1L, "Product", new BigDecimal("10.00"), 1);
        when(cartRepository.findById(cartId)).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        cartService.addItem(cartId, request, null);

        ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isNull();
    }

    @Test
    void shouldPreserveUserIdOnSubsequentAddItem() {
        String userId = "user-42";
        Cart existingCart = new Cart(userId);
        existingCart.setUserId(userId);
        existingCart.addItem(new CartItem(1L, "Product A", new BigDecimal("10.00"), 1));

        var request = new AddCartItemRequest(2L, "Product B", new BigDecimal("20.00"), 1);
        when(cartRepository.findById(userId)).thenReturn(Optional.of(existingCart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        cartService.addItem(userId, request, userId);

        ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        assertThat(captor.getValue().getItems()).hasSize(2);
    }
}
