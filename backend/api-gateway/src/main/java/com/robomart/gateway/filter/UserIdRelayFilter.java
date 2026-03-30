package com.robomart.gateway.filter;

import java.util.regex.Pattern;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class UserIdRelayFilter implements GlobalFilter, Ordered {

    static final String X_USER_ID = "X-User-Id";
    private static final int MAX_HEADER_LENGTH = 128;
    private static final Pattern INVALID_CHARS = Pattern.compile("[\\x00-\\x1F\\x7F]");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication().getPrincipal())
                .filter(Jwt.class::isInstance)
                .cast(Jwt.class)
                .map(jwt -> withAuthenticatedUserId(exchange, jwt))
                .defaultIfEmpty(withAnonymousUserId(exchange))
                .flatMap(chain::filter);
    }

    private ServerWebExchange withAuthenticatedUserId(ServerWebExchange exchange, Jwt jwt) {
        String sub = jwt.getSubject();
        if (sub == null) {
            return exchange;
        }
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> h.set(X_USER_ID, sub))
                .build();
        return exchange.mutate().request(request).build();
    }

    private ServerWebExchange withAnonymousUserId(ServerWebExchange exchange) {
        String existingUserId = exchange.getRequest().getHeaders().getFirst(X_USER_ID);
        if (existingUserId == null) {
            return exchange;
        }
        if (!isValidAnonymousUserId(existingUserId)) {
            ServerHttpRequest request = exchange.getRequest().mutate()
                    .headers(h -> h.remove(X_USER_ID))
                    .build();
            return exchange.mutate().request(request).build();
        }
        return exchange;
    }

    boolean isValidAnonymousUserId(String value) {
        return value.length() <= MAX_HEADER_LENGTH && !INVALID_CHARS.matcher(value).find();
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }
}
