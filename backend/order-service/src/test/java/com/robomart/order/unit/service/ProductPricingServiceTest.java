package com.robomart.order.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.robomart.common.exception.ExternalServiceException;
import com.robomart.order.client.ProductCatalogClient;
import com.robomart.order.exception.OrderProductUnavailableException;
import com.robomart.order.service.ProductPricingService;
import com.robomart.order.service.ProductPricingService.PricedProduct;

/** Exercises the real declarative client against product-service's response envelope. */
@DisplayName("ProductPricingService")
class ProductPricingServiceTest {

    private static final String BASE = "http://product-service.test";

    private MockRestServiceServer server;
    private ProductPricingService pricingService;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        ProductCatalogClient client = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(builder.build())).build()
                .createClient(ProductCatalogClient.class);
        pricingService = new ProductPricingService(client);
    }

    private static String product(long id, String name, String price) {
        return "{\"data\":{\"id\":" + id + ",\"sku\":\"SKU-" + id + "\",\"name\":\"" + name + "\",\"price\":" + price
                + ",\"stockQuantity\":5,\"category\":{\"id\":1,\"name\":\"Robots\"},\"images\":[]},"
                + "\"traceId\":\"abc\"}";
    }

    @Test
    @DisplayName("fetches each distinct product once and normalises prices to scale 2")
    void fetchesDistinctProducts() {
        server.expect(requestTo(BASE + "/api/v1/products/1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(product(1, "Robot", "150"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/v1/products/2"))
                .andRespond(withSuccess(product(2, "Arm", "20.5"), MediaType.APPLICATION_JSON));

        Map<Long, PricedProduct> prices = pricingService.currentPrices(List.of(1L, 2L, 1L));

        assertThat(prices).containsOnlyKeys(1L, 2L);
        assertThat(prices.get(1L)).isEqualTo(new PricedProduct("Robot", new BigDecimal("150.00")));
        assertThat(prices.get(2L).unitPrice()).isEqualTo(new BigDecimal("20.50"));
        server.verify();
    }

    @Test
    @DisplayName("unknown or deactivated products (404) are a business rejection")
    void notFoundIsUnavailable() {
        server.expect(requestTo(BASE + "/api/v1/products/9")).andRespond(withResourceNotFound());

        assertThatThrownBy(() -> pricingService.currentPrices(List.of(9L)))
                .isInstanceOf(OrderProductUnavailableException.class)
                .hasMessageContaining("Product 9");
    }

    @Test
    @DisplayName("a product without a positive price cannot be ordered")
    void zeroPriceIsUnavailable() {
        server.expect(requestTo(BASE + "/api/v1/products/3"))
                .andRespond(withSuccess(product(3, "Freebie", "0"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> pricingService.currentPrices(List.of(3L)))
                .isInstanceOf(OrderProductUnavailableException.class);
    }

    @Test
    @DisplayName("an empty envelope cannot be priced")
    void missingDataIsUnavailable() {
        server.expect(requestTo(BASE + "/api/v1/products/4"))
                .andRespond(withSuccess("{\"data\":null,\"traceId\":\"x\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> pricingService.currentPrices(List.of(4L)))
                .isInstanceOf(OrderProductUnavailableException.class);
    }

    @Test
    @DisplayName("catalog outages surface as external-service errors, not as missing products")
    void serverErrorIsExternal() {
        server.expect(requestTo(BASE + "/api/v1/products/5")).andRespond(withServerError());

        assertThatThrownBy(() -> pricingService.currentPrices(List.of(5L)))
                .isInstanceOf(ExternalServiceException.class);
    }
}
