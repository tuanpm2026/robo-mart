package com.robomart.notification.client;

import java.time.Duration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ProductServiceClient {

    private static final Logger log = LoggerFactory.getLogger(ProductServiceClient.class);

    private final RestClient restClient;
    private final Cache<String, String> productNameCache;

    public ProductServiceClient(@Value("${notification.product-service.url}") String productServiceUrl,
            @Value("${notification.product-name-cache.maximum-size:10000}") int maximumSize,
            @Value("${notification.product-name-cache.ttl:PT10M}") Duration ttl) {
        if (maximumSize < 1 || ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("Invalid product name cache bounds");
        productNameCache = Caffeine.newBuilder().maximumSize(maximumSize).expireAfterWrite(ttl).build();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.restClient = RestClient.builder()
                .baseUrl(productServiceUrl)
                .requestFactory(factory)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {
                    log.warn("Product service error: status={}", res.getStatusCode());
                })
                .build();
    }

    public String getProductName(String productId) {
        String cached = productNameCache.getIfPresent(productId);
        if (cached != null) {
            return cached;
        }
        try {
            ProductApiResponse response = restClient.get()
                    .uri("/api/v1/products/{productId}", Long.parseLong(productId))
                    .retrieve()
                    .body(ProductApiResponse.class);
            if (response != null && response.data() != null) {
                String name = response.data().name();
                productNameCache.put(productId, name);
                return name;
            }
        } catch (Exception e) {
            log.warn("Failed to fetch product name for productId={}: {}", productId, e.getMessage());
        }
        return "Product #" + productId;
    }
}
