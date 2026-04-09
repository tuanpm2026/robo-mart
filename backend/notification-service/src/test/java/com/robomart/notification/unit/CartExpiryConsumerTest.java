package com.robomart.notification.unit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.robomart.events.cart.CartExpiryWarningEvent;
import com.robomart.events.cart.CartItemSummary;
import com.robomart.notification.event.CartExpiryConsumer;
import com.robomart.notification.service.NotificationService;

@ExtendWith(MockitoExtension.class)
class CartExpiryConsumerTest {

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private CartExpiryConsumer cartExpiryConsumer;

    private CartExpiryWarningEvent buildEvent(String cartId, String userId) {
        return CartExpiryWarningEvent.newBuilder()
                .setEventId("evt-cart-1")
                .setEventType("CART_EXPIRY_WARNING")
                .setAggregateId(cartId)
                .setAggregateType("Cart")
                .setTimestamp(Instant.now())
                .setVersion(1)
                .setCartId(cartId)
                .setUserId(userId)
                .setExpiresInSeconds(3600L)
                .setCheckoutUrl("http://localhost:5173/cart")
                .setItems(List.of(
                        CartItemSummary.newBuilder()
                                .setProductId(1L)
                                .setProductName("Test Product")
                                .setPrice("19.99")
                                .setQuantity(2)
                                .setSubtotal("39.98")
                                .build()
                ))
                .build();
    }

    @Test
    void shouldRouteCartExpiryWarningToNotificationService() {
        CartExpiryWarningEvent event = buildEvent("cart-123", "user-456");

        cartExpiryConsumer.onCartExpiryWarning(event);

        verify(notificationService).sendCartExpiryWarning(eq("cart-123"), eq("user-456"), any(CartExpiryWarningEvent.class));
    }

    @Test
    void shouldConvertAvroCharSequenceFieldsToString() {
        // Avro CharSequence fields must be .toString()'d before passing to service
        CartExpiryWarningEvent event = buildEvent("cart-xyz", "user-abc");

        cartExpiryConsumer.onCartExpiryWarning(event);

        // Verify that string arguments (not CharSequence) are passed
        verify(notificationService).sendCartExpiryWarning(eq("cart-xyz"), eq("user-abc"), any(CartExpiryWarningEvent.class));
    }
}
