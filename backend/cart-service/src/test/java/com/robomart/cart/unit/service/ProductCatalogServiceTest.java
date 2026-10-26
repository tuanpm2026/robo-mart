package com.robomart.cart.unit.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import com.robomart.cart.client.ProductServiceClient;
import com.robomart.cart.client.ProductSnapshot;
import com.robomart.cart.exception.ProductNotFoundException;
import com.robomart.cart.service.ProductCatalogService;
import com.robomart.common.dto.ApiResponse;
import com.robomart.common.exception.ExternalServiceException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductCatalogServiceTest {

    @Mock
    private ProductServiceClient productServiceClient;

    private ProductCatalogService service;

    @BeforeEach
    void setUp() {
        service = new ProductCatalogService(productServiceClient);
    }

    @Test
    void shouldReturnProductFromProductService() {
        ProductSnapshot product = new ProductSnapshot(1L, "Robot Arm", new BigDecimal("199.00"));
        when(productServiceClient.getProduct(1L)).thenReturn(new ApiResponse<>(product, "trace"));

        assertThat(service.getProduct(1L)).isEqualTo(product);
    }

    @Test
    void shouldMapNotFoundToProductNotFound() {
        when(productServiceClient.getProduct(9L)).thenThrow(HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.getProduct(9L))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining("9");
    }

    @Test
    void shouldMapServerErrorToExternalServiceException() {
        when(productServiceClient.getProduct(1L)).thenThrow(HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE, "Unavailable", HttpHeaders.EMPTY, new byte[0],
                StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.getProduct(1L)).isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void shouldMapTimeoutToExternalServiceException() {
        when(productServiceClient.getProduct(1L)).thenThrow(new ResourceAccessException("Read timed out"));

        assertThatThrownBy(() -> service.getProduct(1L)).isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void shouldRejectResponseWithoutData() {
        when(productServiceClient.getProduct(1L)).thenReturn(new ApiResponse<>(null, "trace"));

        assertThatThrownBy(() -> service.getProduct(1L)).isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void shouldRejectNullResponse() {
        when(productServiceClient.getProduct(1L)).thenReturn(null);

        assertThatThrownBy(() -> service.getProduct(1L)).isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void shouldRejectProductWithoutPriceOrName() {
        when(productServiceClient.getProduct(1L)).thenReturn(
                new ApiResponse<>(new ProductSnapshot(1L, "Robot Arm", null), "trace"));
        assertThatThrownBy(() -> service.getProduct(1L)).isInstanceOf(ExternalServiceException.class);

        when(productServiceClient.getProduct(2L)).thenReturn(
                new ApiResponse<>(new ProductSnapshot(2L, null, BigDecimal.ONE), "trace"));
        assertThatThrownBy(() -> service.getProduct(2L)).isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void shouldRejectNegativePrice() {
        when(productServiceClient.getProduct(1L)).thenReturn(
                new ApiResponse<>(new ProductSnapshot(1L, "Robot Arm", new BigDecimal("-1")), "trace"));

        assertThatThrownBy(() -> service.getProduct(1L)).isInstanceOf(ExternalServiceException.class);
    }
}
