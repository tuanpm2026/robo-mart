package com.robomart.cart.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.robomart.cart.client.ProductSnapshot;
import com.robomart.cart.config.CartProperties;
import com.robomart.cart.dto.AddCartItemRequest;
import com.robomart.cart.dto.UpdateCartItemRequest;
import com.robomart.cart.entity.Cart;
import com.robomart.cart.entity.CartItem;
import com.robomart.cart.exception.CartNotFoundException;
import com.robomart.cart.exception.ProductNotFoundException;
import com.robomart.cart.mapper.CartMapperImpl;
import com.robomart.cart.repository.CartRepository;
import com.robomart.cart.service.CartService;
import com.robomart.cart.service.ProductCatalogService;

class CartServiceTest {
    private final CartRepository carts = mock(CartRepository.class);
    private final ProductCatalogService catalog = mock(ProductCatalogService.class);
    private final CartService service = new CartService(carts, new CartMapperImpl(), new CartProperties(), catalog);

    @Test
    void catalogPriceAndNameOverrideClientValuesBeforeAtomicAdd() {
        when(catalog.getProduct(7L)).thenReturn(new ProductSnapshot(7L, "Server name", new BigDecimal("99.99")));
        Cart result = new Cart("g:guest");
        result.addItem(new CartItem(7L, "Server name", new BigDecimal("99.99"), 2));
        when(carts.add(eq("g:guest"), any(), isNull())).thenReturn(result);
        var response = service.addItem("g:guest", new AddCartItemRequest(7L, "forged", BigDecimal.ONE, 2), null);
        var item = ArgumentCaptor.forClass(CartItem.class);
        verify(carts).add(eq("g:guest"), item.capture(), isNull());
        assertThat(item.getValue().getPrice()).isEqualByComparingTo("99.99");
        assertThat(item.getValue().getProductName()).isEqualTo("Server name");
        assertThat(response.totalPrice()).isEqualByComparingTo("199.98");
    }

    @Test
    void failedCatalogLookupDoesNotMutateCart() {
        when(catalog.getProduct(7L)).thenThrow(new ProductNotFoundException(7L));
        assertThatThrownBy(() -> service.addItem("g:guest", new AddCartItemRequest(7L, null, null, 1), null))
                .isInstanceOf(ProductNotFoundException.class);
        verifyNoInteractions(carts);
    }

    @Test
    void getOnlyRefreshesTtlAndChangesUseAtomicOperations() {
        Cart cart = new Cart("u:user");
        when(carts.getAndRefresh("u:user")).thenReturn(cart);
        service.getCart("u:user");
        when(carts.setQuantity("u:user", 7L, 3)).thenReturn(cart);
        service.updateItemQuantity("u:user", 7L, new UpdateCartItemRequest(3));
        service.removeItem("u:user", 7L);
        verify(carts).getAndRefresh("u:user");
        verify(carts).setQuantity("u:user", 7L, 3);
        verify(carts).remove("u:user", 7L);
        verifyNoMoreInteractions(carts);
        when(carts.getAndRefresh("missing")).thenThrow(new CartNotFoundException("missing"));
        assertThatThrownBy(() -> service.getCart("missing")).isInstanceOf(CartNotFoundException.class);
    }
}
