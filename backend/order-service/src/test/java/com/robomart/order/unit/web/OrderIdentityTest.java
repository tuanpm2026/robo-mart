package com.robomart.order.unit.web;

import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import com.robomart.order.service.OrderService;
import com.robomart.order.web.OrderRestController;

class OrderIdentityTest {
    @Test
    void signedSubjectOverridesSpoofedUserHeader() {
        var service = mock(OrderService.class);
        when(service.getOrdersByUser("owner", 0, 10)).thenReturn(Page.empty());
        var jwt = Jwt.withTokenValue("signed-token").header("alg", "RS256").subject("owner").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try {
            new OrderRestController(service).listOrders("victim", 0, 10);
            verify(service).getOrdersByUser("owner", 0, 10);
            verify(service, never()).getOrdersByUser("victim", 0, 10);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
