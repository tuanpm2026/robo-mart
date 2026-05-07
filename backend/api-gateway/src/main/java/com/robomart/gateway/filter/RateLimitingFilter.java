package com.robomart.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class RateLimitingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);
    private static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private final RedisRateLimiter authenticatedRateLimiter;
    private final RedisRateLimiter anonymousRateLimiter;
    private final KeyResolver userKeyResolver;

    public RateLimitingFilter(@Qualifier("authenticatedRateLimiter") RedisRateLimiter authenticatedRateLimiter,
                              @Qualifier("anonymousRateLimiter") RedisRateLimiter anonymousRateLimiter,
                              KeyResolver userKeyResolver) {
        this.authenticatedRateLimiter = authenticatedRateLimiter;
        this.anonymousRateLimiter = anonymousRateLimiter;
        this.userKeyResolver = userKeyResolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        // Skip rate limiting for actuator and WebSocket upgrade paths
        if (path.startsWith("/actuator") || path.startsWith("/ws")) {
            return chain.filter(exchange);
        }

        // Fail-closed on rate-limiter outage: Spring's RedisRateLimiter swallows Redis
        // errors and returns allowed=true with X-RateLimit-Remaining=-1 as a marker.
        // We detect that marker and reject with 503 instead of letting traffic bypass
        // the rate limit during a Redis outage (DoS exposure).
        return userKeyResolver.resolve(exchange)
                .flatMap(key -> {
                    boolean isAuthenticated = key.startsWith("user:");
                    RedisRateLimiter limiter = isAuthenticated
                            ? authenticatedRateLimiter
                            : anonymousRateLimiter;
                    String routeId = isAuthenticated ? "authenticated-rate" : "anonymous-rate";
                    return limiter.isAllowed(routeId, key);
                })
                .flatMap(response -> {
                    if ("-1".equals(response.getHeaders().get(REMAINING_HEADER))) {
                        log.error("Rate limiter outage (Redis unreachable) for path {} — failing closed (503)", path);
                        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                        exchange.getResponse().getHeaders().set("Retry-After", "30");
                        return exchange.getResponse().setComplete();
                    }
                    response.getHeaders().forEach((k, v) -> exchange.getResponse().getHeaders().set(k, v));
                    if (response.isAllowed()) {
                        return chain.filter(exchange);
                    }
                    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    exchange.getResponse().getHeaders().set("Retry-After", "60");
                    return exchange.getResponse().setComplete();
                });
    }

    @Override
    public int getOrder() {
        // Run before UserIdRelayFilter (LOWEST_PRECEDENCE - 1) but after auth
        return Ordered.LOWEST_PRECEDENCE - 2;
    }
}
