package com.robomart.notification.unit.service;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.robomart.events.cart.CartExpiryWarningEvent;
import com.robomart.notification.service.*;
import com.robomart.notification.client.*;
import com.robomart.notification.repository.NotificationLogRepository;
import com.robomart.notification.entity.NotificationLog;
import com.robomart.notification.enums.*;
import io.micrometer.tracing.Tracer;

class CartWarningGenerationTest {
    @Test
    void eachGenerationIsDeliveredWhileReplaysAreSkipped() {
        var logs = mock(NotificationLogRepository.class);
        var email = mock(EmailService.class);
        var service = new NotificationService(mock(OrderServiceClient.class), mock(ProductServiceClient.class), email, logs, Tracer.NOOP);
        var sent = new NotificationLog(); sent.setStatus(NotificationStatus.SENT);
        when(logs.findByDeliveryKey("old")).thenReturn(Optional.of(sent));
        when(logs.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        for (var id : List.of("old", "new")) {
            var event = mock(CartExpiryWarningEvent.class);
            when(event.getEventId()).thenReturn(id);
            when(event.getItems()).thenReturn(List.of());
            service.sendCartExpiryWarning("same-cart", "user", event);
        }
        verify(email, times(1)).sendEmail(any(), any(), any());
    }
}
