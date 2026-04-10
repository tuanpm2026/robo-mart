package com.robomart.notification.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

public class JwtStompInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtStompInterceptor.class);

    private final JwtDecoder jwtDecoder;

    public JwtStompInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor
                .getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("STOMP CONNECT rejected: missing or invalid Authorization header");
                throw new MessageDeliveryException(message, "Missing or invalid Authorization header");
            }
            try {
                jwtDecoder.decode(authHeader.substring(7));
                log.debug("STOMP CONNECT authorized");
            } catch (JwtException e) {
                log.warn("STOMP CONNECT rejected: invalid JWT - {}", e.getMessage());
                throw new MessageDeliveryException(message, "Invalid JWT: " + e.getMessage());
            }
        }
        return message;
    }
}
