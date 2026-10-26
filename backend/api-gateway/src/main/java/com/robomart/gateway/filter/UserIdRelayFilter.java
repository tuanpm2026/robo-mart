package com.robomart.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Relays the authenticated caller's identity to downstream services as {@code X-User-Id}.
 *
 * <p>Downstream services trust this header, so it is never taken from the client: any inbound
 * {@code X-User-Id} is always stripped and the header is set only from the subject of a validated
 * JWT. Anonymous requests therefore reach downstream services without an {@code X-User-Id}
 * (guest carts are identified by {@code X-Cart-Id} instead).
 */
@Component
public class UserIdRelayFilter implements GlobalFilter, Ordered {

    static final String X_USER_ID = "X-User-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(SecurityContext::getAuthentication)
                .mapNotNull(Authentication::getPrincipal)
                .filter(Jwt.class::isInstance)
                .cast(Jwt.class)
                .mapNotNull(Jwt::getSubject)
                .filter(sub -> !sub.isBlank())
                .map(sub -> withUserId(exchange, sub))
                .switchIfEmpty(Mono.fromSupplier(() -> withoutUserId(exchange)))
                .flatMap(chain::filter);
    }

    private ServerWebExchange withUserId(ServerWebExchange exchange, String sub) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> h.set(X_USER_ID, sub))
                .build();
        return exchange.mutate().request(request).build();
    }

    private ServerWebExchange withoutUserId(ServerWebExchange exchange) {
        if (!exchange.getRequest().getHeaders().containsHeader(X_USER_ID)) {
            return exchange;
        }
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> h.remove(X_USER_ID))
                .build();
        return exchange.mutate().request(request).build();
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }
}
