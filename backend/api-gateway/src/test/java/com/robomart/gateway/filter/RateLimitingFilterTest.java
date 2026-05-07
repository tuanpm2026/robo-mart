package com.robomart.gateway.filter;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitingFilterTest {

    @Test
    void shouldReturn503WhenRateLimiterSignalsRedisOutage() {
        // Spring's RedisRateLimiter swallows Redis errors and returns allowed=true
        // with X-RateLimit-Remaining=-1 as a marker. We must fail-closed on that.
        RedisRateLimiter authenticated = mock(RedisRateLimiter.class);
        RedisRateLimiter anonymous = mock(RedisRateLimiter.class);
        KeyResolver keyResolver = mock(KeyResolver.class);

        when(keyResolver.resolve(any())).thenReturn(Mono.just("anon:127.0.0.1"));
        RateLimiter.Response outage = new RateLimiter.Response(true,
                Map.of("X-RateLimit-Remaining", "-1"));
        when(anonymous.isAllowed(anyString(), anyString())).thenReturn(Mono.just(outage));

        RateLimitingFilter filter = new RateLimitingFilter(authenticated, anonymous, keyResolver);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/products").build());
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("30");
        Mockito.verifyNoInteractions(chain);
    }

    @Test
    void shouldAllowRequestWhenLimiterAllows() {
        RedisRateLimiter authenticated = mock(RedisRateLimiter.class);
        RedisRateLimiter anonymous = mock(RedisRateLimiter.class);
        KeyResolver keyResolver = mock(KeyResolver.class);

        when(keyResolver.resolve(any())).thenReturn(Mono.just("user:42"));
        RateLimiter.Response allowed = new RateLimiter.Response(true, Map.of());
        when(authenticated.isAllowed(anyString(), anyString())).thenReturn(Mono.just(allowed));

        RateLimitingFilter filter = new RateLimitingFilter(authenticated, anonymous, keyResolver);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/products").build());
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        Mockito.verify(chain).filter(exchange);
    }

    @Test
    void shouldReturn429WhenLimitExceeded() {
        RedisRateLimiter authenticated = mock(RedisRateLimiter.class);
        RedisRateLimiter anonymous = mock(RedisRateLimiter.class);
        KeyResolver keyResolver = mock(KeyResolver.class);

        when(keyResolver.resolve(any())).thenReturn(Mono.just("anon:127.0.0.1"));
        RateLimiter.Response denied = new RateLimiter.Response(false, Map.of());
        when(anonymous.isAllowed(anyString(), anyString())).thenReturn(Mono.just(denied));

        RateLimitingFilter filter = new RateLimitingFilter(authenticated, anonymous, keyResolver);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/products").build());
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("60");
        Mockito.verifyNoInteractions(chain);
    }

    @Test
    void shouldSkipActuatorPaths() {
        RedisRateLimiter authenticated = mock(RedisRateLimiter.class);
        RedisRateLimiter anonymous = mock(RedisRateLimiter.class);
        KeyResolver keyResolver = mock(KeyResolver.class);

        RateLimitingFilter filter = new RateLimitingFilter(authenticated, anonymous, keyResolver);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health").build());
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        Mockito.verify(chain).filter(exchange);
        Mockito.verifyNoInteractions(keyResolver);
    }
}
