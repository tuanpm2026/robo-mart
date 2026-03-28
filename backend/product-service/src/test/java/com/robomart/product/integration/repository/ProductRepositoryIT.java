package com.robomart.product.integration.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import com.robomart.product.repository.ProductRepository;
import com.robomart.test.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class ProductRepositoryIT {

    @Autowired
    private ProductRepository productRepository;

    @Test
    void shouldFindAllProductsWithDetails() {
        var page = productRepository.findAllWithDetails(PageRequest.of(0, 10));

        assertThat(page.getContent()).isNotEmpty();
        assertThat(page.getContent().getFirst().getCategory()).isNotNull();
    }

    @Test
    void shouldFindProductsByCategoryId() {
        var page = productRepository.findByCategoryId(1L, PageRequest.of(0, 10));

        assertThat(page.getContent()).isNotEmpty();
        page.getContent().forEach(product ->
                assertThat(product.getCategory().getId()).isEqualTo(1L));
    }

    @Test
    void shouldFindProductByIdWithDetails() {
        var product = productRepository.findByIdWithDetails(1L);

        assertThat(product).isPresent();
        assertThat(product.get().getCategory()).isNotNull();
        assertThat(product.get().getImages()).isNotNull();
    }

    @Test
    void shouldReturnEmptyWhenProductNotFound() {
        var product = productRepository.findByIdWithDetails(99999L);

        assertThat(product).isEmpty();
    }
}
