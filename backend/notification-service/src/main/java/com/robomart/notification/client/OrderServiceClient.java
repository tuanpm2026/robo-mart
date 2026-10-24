package com.robomart.notification.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.robomart.common.dto.ApiResponse;

@Component
public class OrderServiceClient {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceClient.class);

    private final RestClient restClient;

    public OrderServiceClient(@Qualifier("notificationOrderRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public OrderDetailDto getOrderDetail(String orderId) {
        log.debug("Fetching order detail for orderId={}", orderId);
        long orderIdLong;
        try {
            orderIdLong = Long.parseLong(orderId);
        } catch (NumberFormatException e) {
            log.error("Invalid orderId format '{}' — skipping notification", orderId);
            return null;
        }
        try {
            ApiResponse<OrderDetailDto> response = restClient.get()
                    .uri("/api/v1/admin/orders/{orderId}", orderIdLong)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return response != null ? response.data() : null;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                log.warn("Order not found for orderId={}, skipping notification", orderId);
                return null;
            }
            // Authentication/configuration failures must reach retry/DLQ rather than silently drop an event.
            throw e;
        }
        // HttpServerErrorException (5xx) propagates to trigger Kafka retry
    }
}
