package com.robomart.product.unit.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.robomart.product.config.CacheConfig;
import com.robomart.product.dto.ProductDetailResponse;
import com.robomart.product.entity.Category;
import com.robomart.product.entity.Product;
import com.robomart.product.exception.ProductNotFoundException;
import com.robomart.product.mapper.ProductMapper;
import com.robomart.product.repository.CategoryRepository;
import com.robomart.product.repository.ProductRepository;
import com.robomart.product.service.OutboxPublisher;
import com.robomart.product.service.ProductService;

import io.micrometer.tracing.Tracer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(ProductServiceCacheTest.TestConfig.class)
class ProductServiceCacheTest {

    @EnableCaching
    @Import(ProductService.class)
    static class TestConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(
                    CacheConfig.CACHE_PRODUCT_DETAIL,
                    CacheConfig.CACHE_PRODUCT_SEARCH);
        }

        @Bean
        ProductRepository productRepository() {
            return mock(ProductRepository.class);
        }

        @Bean
        CategoryRepository categoryRepository() {
            return mock(CategoryRepository.class);
        }

        @Bean
        ProductMapper productMapper() {
            return mock(ProductMapper.class);
        }

        @Bean
        OutboxPublisher outboxPublisher() {
            return mock(OutboxPublisher.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return mock(ObjectMapper.class);
        }

        @Bean
        Tracer tracer() {
            return mock(Tracer.class);
        }
    }

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void resetState() {
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        });
        reset(productRepository, productMapper);
    }

    @Test
    void shouldCacheProductDetailOnFirstCallAndReturnCachedOnSecond() {
        var product = createProduct();
        var detailResponse = new ProductDetailResponse(1L, "TEST-001", "Test Product",
                "Description", BigDecimal.valueOf(29.99), BigDecimal.valueOf(4.5),
                "TestBrand", 100, null, List.of(), null, null);

        when(productRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(product));
        when(productMapper.toDetailResponse(product)).thenReturn(detailResponse);

        var result1 = productService.getProductById(1L);
        var result2 = productService.getProductById(1L);

        assertThat(result1).isEqualTo(detailResponse);
        assertThat(result2).isEqualTo(detailResponse);
        verify(productRepository, times(1)).findByIdWithDetails(1L);
    }

    @Test
    void shouldNotCacheExceptions() {
        when(productRepository.findByIdWithDetails(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getProductById(999L))
                .isInstanceOf(ProductNotFoundException.class);
        assertThatThrownBy(() -> productService.getProductById(999L))
                .isInstanceOf(ProductNotFoundException.class);

        verify(productRepository, times(2)).findByIdWithDetails(999L);
    }

    @Test
    void shouldCacheDifferentProductsSeparately() {
        var product1 = createProduct();
        var product2 = createProduct();
        product2.setSku("TEST-002");
        product2.setName("Another Product");

        var detail1 = new ProductDetailResponse(1L, "TEST-001", "Test Product",
                "Desc", BigDecimal.valueOf(29.99), BigDecimal.valueOf(4.5),
                "TestBrand", 100, null, List.of(), null, null);
        var detail2 = new ProductDetailResponse(2L, "TEST-002", "Another Product",
                "Desc", BigDecimal.valueOf(39.99), BigDecimal.valueOf(4.0),
                "TestBrand", 50, null, List.of(), null, null);

        when(productRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(product1));
        when(productRepository.findByIdWithDetails(2L)).thenReturn(Optional.of(product2));
        when(productMapper.toDetailResponse(product1)).thenReturn(detail1);
        when(productMapper.toDetailResponse(product2)).thenReturn(detail2);

        assertThat(productService.getProductById(1L).name()).isEqualTo("Test Product");
        assertThat(productService.getProductById(2L).name()).isEqualTo("Another Product");
        assertThat(productService.getProductById(1L).name()).isEqualTo("Test Product");

        verify(productRepository, times(1)).findByIdWithDetails(1L);
        verify(productRepository, times(1)).findByIdWithDetails(2L);
    }

    private Product createProduct() {
        var category = new Category();
        category.setName("Electronics");

        var product = new Product();
        product.setSku("TEST-001");
        product.setName("Test Product");
        product.setDescription("Description");
        product.setPrice(BigDecimal.valueOf(29.99));
        product.setCategory(category);
        product.setRating(BigDecimal.valueOf(4.50));
        product.setBrand("TestBrand");
        product.setStockQuantity(100);

        return product;
    }
}
