package com.robomart.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.messaging.access.intercept.AuthorizationChannelInterceptor;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;
import org.springframework.security.messaging.context.SecurityContextChannelInterceptor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import com.robomart.security.converter.KeycloakRealmRoleConverter;

/**
 * STOMP over SockJS endpoint ({@code /ws}) with JWT authentication and per-frame authorization.
 *
 * <p>Inbound chain (order matters): {@link JwtStompInterceptor} authenticates CONNECT and attaches
 * the user to the session, {@link SecurityContextChannelInterceptor} exposes that user as the
 * {@code SecurityContext} for each frame, and {@link AuthorizationChannelInterceptor} applies
 * {@link #stompAuthorizationManager()}. All broadcast topics carry admin-only data (every order's
 * userId/total, inventory and health state, reconciliation alerts), so subscribing to any
 * {@code /topic/**} destination requires ADMIN; clients may never SEND (there are no
 * {@code @MessageMapping} handlers, and SEND to {@code /topic/**} would let a client forge
 * broadcasts to other subscribers). Everything else is denied by default.
 *
 * <p>The Spring Security components are wired manually instead of via
 * {@code @EnableWebSocketSecurity} because that annotation always installs a CSRF channel
 * interceptor that requires a CSRF token from an HTTP session on CONNECT. This service is
 * stateless and authenticates with a bearer JWT sent explicitly in the STOMP CONNECT frame (not an
 * ambient cookie), so a cross-site page cannot ride on a victim's credentials and CSRF protection
 * does not apply — the same reasoning that disables CSRF for the HTTP resource server.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final KeycloakRealmRoleConverter keycloakRealmRoleConverter;
    private final JwtDecoder jwtDecoder;

    public WebSocketConfig(KeycloakRealmRoleConverter keycloakRealmRoleConverter, JwtDecoder jwtDecoder) {
        this.keycloakRealmRoleConverter = keycloakRealmRoleConverter;
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(inboundSecurityInterceptors(jwtStompInterceptor()));
    }

    @Bean
    public JwtStompInterceptor jwtStompInterceptor() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(keycloakRealmRoleConverter);
        return new JwtStompInterceptor(jwtDecoder, converter);
    }

    /**
     * The ordered inbound interceptor chain: authenticate, propagate the security context, authorize.
     */
    public static ChannelInterceptor[] inboundSecurityInterceptors(JwtStompInterceptor authentication) {
        return new ChannelInterceptor[] {
            authentication,
            new SecurityContextChannelInterceptor(),
            new AuthorizationChannelInterceptor(stompAuthorizationManager())
        };
    }

    /**
     * Per-frame STOMP authorization rules. CONNECT is authenticated by {@link JwtStompInterceptor}
     * (which rejects a missing/invalid token before this runs); the rule here is defense in depth.
     */
    public static AuthorizationManager<Message<?>> stompAuthorizationManager() {
        return MessageMatcherDelegatingAuthorizationManager.builder()
                .simpTypeMatchers(SimpMessageType.CONNECT).authenticated()
                // Session housekeeping frames carry no data; DISCONNECT is also synthesized by the
                // server when an (even unauthenticated) socket closes.
                .simpTypeMatchers(SimpMessageType.HEARTBEAT, SimpMessageType.UNSUBSCRIBE,
                        SimpMessageType.DISCONNECT).permitAll()
                .simpSubscribeDestMatchers("/topic/**").hasRole("ADMIN")
                .anyMessage().denyAll()
                .build();
    }
}
