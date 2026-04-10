package com.robomart.notification.unit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import com.robomart.notification.config.JwtStompInterceptor;

@ExtendWith(MockitoExtension.class)
class JwtStompInterceptorTest {

    @Mock
    private JwtDecoder jwtDecoder;

    private JwtStompInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new JwtStompInterceptor(jwtDecoder);
    }

    @Test
    void connect_withValidJwt_passes() {
        when(jwtDecoder.decode("valid.jwt.token")).thenReturn(mock(Jwt.class));
        Message<?> message = buildConnectMessage("Bearer valid.jwt.token");
        assertDoesNotThrow(() -> interceptor.preSend(message, mock(MessageChannel.class)));
    }

    @Test
    void connect_withMissingAuthHeader_throws() {
        Message<?> message = buildConnectMessage(null);
        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(message, mock(MessageChannel.class)));
        verifyNoInteractions(jwtDecoder);
    }

    @Test
    void connect_withNonBearerHeader_throws() {
        Message<?> message = buildConnectMessage("Basic dXNlcjpwYXNz");
        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(message, mock(MessageChannel.class)));
        verifyNoInteractions(jwtDecoder);
    }

    @Test
    void connect_withInvalidJwt_throws() {
        doThrow(new JwtException("Invalid token")).when(jwtDecoder).decode(any());
        Message<?> message = buildConnectMessage("Bearer invalid.token.here");
        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(message, mock(MessageChannel.class)));
    }

    @Test
    void subscribe_command_passesWithoutJwtValidation() {
        Message<?> message = buildSubscribeMessage();
        assertDoesNotThrow(() -> interceptor.preSend(message, mock(MessageChannel.class)));
        verifyNoInteractions(jwtDecoder);
    }

    private Message<?> buildConnectMessage(String authHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authHeader != null) {
            accessor.addNativeHeader("Authorization", authHeader);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> buildSubscribeMessage() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/orders");
        accessor.setSubscriptionId("sub-0");
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
