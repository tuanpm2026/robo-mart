package com.robomart.notification.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Authenticates STOMP sessions: on CONNECT the {@code Authorization: Bearer <jwt>} native header is
 * decoded, converted to an {@code Authentication} carrying the Keycloak realm roles, and attached to
 * the session via {@link StompHeaderAccessor#setUser}. Spring's STOMP handler then stamps that user on
 * every later frame of the session, where {@link WebSocketConfig}'s authorization interceptor enforces
 * per-destination rules. This interceptor must run before the authorization interceptors.
 */
public class JwtStompInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtStompInterceptor.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtDecoder jwtDecoder;
    private final Converter<Jwt, ? extends AbstractAuthenticationToken> authenticationConverter;

    public JwtStompInterceptor(JwtDecoder jwtDecoder,
                               Converter<Jwt, ? extends AbstractAuthenticationToken> authenticationConverter) {
        this.jwtDecoder = jwtDecoder;
        this.authenticationConverter = authenticationConverter;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor
                .getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && isConnect(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
                log.warn("STOMP CONNECT rejected: missing or invalid Authorization header");
                throw new MessageDeliveryException(message, "Missing or invalid Authorization header");
            }
            AbstractAuthenticationToken authentication;
            try {
                Jwt jwt = jwtDecoder.decode(authHeader.substring(BEARER_PREFIX.length()));
                authentication = authenticationConverter.convert(jwt);
            } catch (JwtException e) {
                log.warn("STOMP CONNECT rejected: invalid JWT - {}", e.getMessage());
                throw new MessageDeliveryException(message, "Invalid JWT");
            }
            if (authentication == null) {
                throw new MessageDeliveryException(message, "Invalid JWT");
            }
            accessor.setUser(authentication);
            log.debug("STOMP CONNECT authenticated: user={}, authorities={}",
                    authentication.getName(), authentication.getAuthorities());
        }
        return message;
    }

    // STOMP 1.2 allows the STOMP frame as a synonym for CONNECT.
    private static boolean isConnect(StompCommand command) {
        return StompCommand.CONNECT.equals(command) || StompCommand.STOMP.equals(command);
    }
}
