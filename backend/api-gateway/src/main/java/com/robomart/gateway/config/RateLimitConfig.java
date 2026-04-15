package com.robomart.gateway.config;

import java.net.InetSocketAddress;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

    // Authenticated: ~120 req/min sustained (2 tokens/sec × 60s), burst up to 100 immediately
    @Value("${gateway.rate-limit.authenticated.replenish-rate:2}")
    private int authReplenishRate;

    @Value("${gateway.rate-limit.authenticated.burst-capacity:100}")
    private int authBurstCapacity;

    // Unauthenticated: ~20 req/min (1 token/sec, cost 3 per request = 60/3 = 20/min)
    @Value("${gateway.rate-limit.anonymous.replenish-rate:1}")
    private int anonReplenishRate;

    @Value("${gateway.rate-limit.anonymous.burst-capacity:60}")
    private int anonBurstCapacity;

    @Value("${gateway.rate-limit.anonymous.requested-tokens:3}")
    private int anonRequestedTokens;

    /**
     * Key resolver — returns "user:{sub}" for authenticated (JWT) requests.
     * Falls back to "ip:{clientIp}" for anonymous requests, using X-Forwarded-For
     * with maxTrustedIndex(1) to extract the real client IP behind one trusted proxy.
     */
    @Bean
    public KeyResolver userKeyResolver() {
        XForwardedRemoteAddressResolver ipResolver = XForwardedRemoteAddressResolver.maxTrustedIndex(1);
        return exchange -> exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(token -> "user:" + token.getName())
                .switchIfEmpty(Mono.fromCallable(() -> {
                    InetSocketAddress addr = ipResolver.resolve(exchange);
                    if (addr == null) {
                        log.warn("remoteAddress is null for path {} — assigning random key to avoid shared bucket",
                                exchange.getRequest().getPath().value());
                        return "ip:" + UUID.randomUUID();
                    }
                    return "ip:" + addr.getAddress().getHostAddress();
                }));
    }

    /** Rate limiter for authenticated users (~120 req/min sustained, burst 100 via token bucket). */
    @Bean("authenticatedRateLimiter")
    @Primary
    public RedisRateLimiter authenticatedRateLimiter() {
        return new RedisRateLimiter(authReplenishRate, authBurstCapacity, 1);
    }

    /** Rate limiter for anonymous users (~20 req/min via token bucket, cost=3 tokens/req). */
    @Bean("anonymousRateLimiter")
    public RedisRateLimiter anonymousRateLimiter() {
        return new RedisRateLimiter(anonReplenishRate, anonBurstCapacity, anonRequestedTokens);
    }
}
