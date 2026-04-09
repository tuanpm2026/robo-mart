package com.robomart.notification.client;

import java.util.concurrent.ConcurrentHashMap;

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
    private final ConcurrentHashMap<String, String> productNameCache = new ConcurrentHashMap<>();

    public ProductServiceClient(@Value("${notification.product-service.url}") String productServiceUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(productServiceUrl)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {
                    log.warn("Product service error: status={}", res.getStatusCode());
                })
                .build();
    }

    public String getProductName(String productId) {
        String cached = productNameCache.get(productId);
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
