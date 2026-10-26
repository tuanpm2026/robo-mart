package com.robomart.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import com.robomart.notification.config.JwtStompInterceptor;
import com.robomart.notification.config.WebSocketConfig;
import com.robomart.security.converter.KeycloakRealmRoleConverter;

@ExtendWith(MockitoExtension.class)
class JwtStompInterceptorTest {

    private static final String ADMIN_TOKEN = "admin.jwt.token";
    private static final String CUSTOMER_TOKEN = "customer.jwt.token";

    @Mock
    private JwtDecoder jwtDecoder;

    private JwtStompInterceptor interceptor;
    private ExecutorSubscribableChannel inboundChannel;
    private List<Message<?>> delivered;

    @BeforeEach
    void setUp() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        interceptor = new JwtStompInterceptor(jwtDecoder, converter);

        // Same ordered chain the application registers on clientInboundChannel.
        delivered = new ArrayList<>();
        inboundChannel = new ExecutorSubscribableChannel();
        inboundChannel.setInterceptors(List.of(WebSocketConfig.inboundSecurityInterceptors(interceptor)));
        inboundChannel.subscribe(delivered::add);
    }

    // --- CONNECT authentication ---

    @Test
    void connect_withAdminJwt_setsUserWithRealmRoles() {
        when(jwtDecoder.decode(ADMIN_TOKEN)).thenReturn(jwt("admin-1", "ADMIN"));
        Message<?> message = connectMessage("Bearer " + ADMIN_TOKEN);

        interceptor.preSend(message, mock(MessageChannel.class));

        Principal user = StompHeaderAccessor.wrap(message).getUser();
        assertThat(user).isInstanceOf(Authentication.class);
        assertThat(user.getName()).isEqualTo("admin-1");
        assertThat(((Authentication) user).getAuthorities())
                .extracting(GrantedAuthority::getAuthority).contains("ROLE_ADMIN");
    }

    @Test
    void stompFrame_isAuthenticatedLikeConnect() {
        Message<?> message = frame(StompCommand.STOMP, null, null);
        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(message, mock(MessageChannel.class)));
        verifyNoInteractions(jwtDecoder);
    }

    @Test
    void connect_withMissingAuthHeader_throws() {
        Message<?> message = connectMessage(null);
        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(message, mock(MessageChannel.class)));
        verifyNoInteractions(jwtDecoder);
    }

    @Test
    void connect_withNonBearerHeader_throws() {
        Message<?> message = connectMessage("Basic dXNlcjpwYXNz");
        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(message, mock(MessageChannel.class)));
        verifyNoInteractions(jwtDecoder);
    }

    @Test
    void connect_withInvalidJwt_throws() {
        doThrow(new JwtException("Invalid token")).when(jwtDecoder).decode(any());
        Message<?> message = connectMessage("Bearer invalid.token.here");
        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(message, mock(MessageChannel.class)));
    }

    @Test
    void connect_throughChain_withCustomerJwt_isAccepted() {
        when(jwtDecoder.decode(CUSTOMER_TOKEN)).thenReturn(jwt("customer-1", "CUSTOMER"));
        Message<?> message = connectMessage("Bearer " + CUSTOMER_TOKEN);

        assertDoesNotThrow(() -> inboundChannel.send(message));
        assertThat(delivered).hasSize(1);
    }

    @Test
    void connect_throughChain_withoutToken_isRejected() {
        assertThatThrownBy(() -> inboundChannel.send(connectMessage(null)))
                .isInstanceOf(MessageDeliveryException.class);
        assertThat(delivered).isEmpty();
    }

    // --- SUBSCRIBE / SEND authorization ---

    @Test
    void customer_cannotSubscribeToOrdersTopic() {
        assertDenied(subscribe("/topic/orders", authenticate(CUSTOMER_TOKEN, "customer-1", "CUSTOMER")));
    }

    @Test
    void customer_cannotSubscribeToAdminTopics() {
        Authentication customer = authenticate(CUSTOMER_TOKEN, "customer-1", "CUSTOMER");
        assertDenied(subscribe("/topic/admin/reconciliation-alerts", customer));
        assertDenied(subscribe("/topic/inventory-alerts", customer));
        assertDenied(subscribe("/topic/system-health", customer));
    }

    @Test
    void admin_canSubscribeToAllDashboardTopics() {
        Authentication admin = authenticate(ADMIN_TOKEN, "admin-1", "ADMIN");
        for (String destination : List.of("/topic/orders", "/topic/inventory-alerts",
                "/topic/system-health", "/topic/admin/reconciliation-alerts")) {
            assertDoesNotThrow(() -> inboundChannel.send(subscribe(destination, admin)), destination);
        }
        assertThat(delivered).hasSize(4);
    }

    @Test
    void subscribe_withoutAuthenticatedUser_isDenied() {
        assertDenied(subscribe("/topic/orders", null));
    }

    @Test
    void admin_cannotSubscribeOutsideTopicNamespace() {
        assertDenied(subscribe("/user/queue/errors", authenticate(ADMIN_TOKEN, "admin-1", "ADMIN")));
    }

    @Test
    void send_isDeniedEvenForAdmin() {
        Authentication admin = authenticate(ADMIN_TOKEN, "admin-1", "ADMIN");
        assertDenied(frame(StompCommand.SEND, "/topic/admin/reconciliation-alerts", admin));
        assertDenied(frame(StompCommand.SEND, "/app/anything", admin));
    }

    @Test
    void disconnect_withoutUser_isPermitted() {
        assertDoesNotThrow(() -> inboundChannel.send(frame(StompCommand.DISCONNECT, null, null)));
    }

    private void assertDenied(Message<?> message) {
        int before = delivered.size();
        assertThatThrownBy(() -> inboundChannel.send(message))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(AccessDeniedException.class);
        assertThat(delivered).hasSize(before);
    }

    /** Runs a CONNECT through the interceptor, as Spring's STOMP handler does, and returns the session user. */
    private Authentication authenticate(String token, String subject, String role) {
        when(jwtDecoder.decode(token)).thenReturn(jwt(subject, role));
        Message<?> connect = connectMessage("Bearer " + token);
        interceptor.preSend(connect, mock(MessageChannel.class));
        return (Authentication) StompHeaderAccessor.wrap(connect).getUser();
    }

    private static Jwt jwt(String subject, String role) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .claim("realm_access", Map.of("roles", List.of(role)))
                .build();
    }

    private static Message<?> connectMessage(String authHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authHeader != null) {
            accessor.addNativeHeader("Authorization", authHeader);
        }
        accessor.setSessionId("session-1");
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<?> subscribe(String destination, Principal user) {
        return frame(StompCommand.SUBSCRIBE, destination, user);
    }

    private static Message<?> frame(StompCommand command, String destination, Principal user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (command == StompCommand.SUBSCRIBE) {
            accessor.setSubscriptionId("sub-0");
        }
        accessor.setSessionId("session-1");
        // Spring's STOMP handler stamps the session's authenticated user on every frame.
        accessor.setUser(user);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
