package com.robomart.product.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.robomart.product.document.ProductDocument;
import com.robomart.product.repository.ProductSearchRepository;
import com.robomart.test.IntegrationTest;

@IntegrationTest
class ElasticsearchIndexIT {

    @Autowired
    private ProductSearchRepository productSearchRepository;

    @Test
    void shouldCreateIndexAndSaveDocument() {
        ProductDocument doc = new ProductDocument();
        doc.setId(1L);
        doc.setSku("ES-001");
        doc.setName("Elasticsearch Test Product");
        doc.setDescription("Testing ES index creation");
        doc.setCategoryId(1L);
        doc.setCategoryName("Test");
        doc.setBrand("TestBrand");
        doc.setPrice(new BigDecimal("99.99"));
        doc.setRating(new BigDecimal("4.5"));
        doc.setStockQuantity(50);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());

        productSearchRepository.save(doc);

        var found = productSearchRepository.findById(1L);
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Elasticsearch Test Product");
        assertThat(found.get().getSku()).isEqualTo("ES-001");
        assertThat(found.get().getCategoryName()).isEqualTo("Test");
    }

    @Test
    void shouldDeleteDocument() {
        ProductDocument doc = new ProductDocument();
        doc.setId(2L);
        doc.setSku("ES-002");
        doc.setName("To Be Deleted");
        doc.setPrice(new BigDecimal("10.00"));
        doc.setCategoryId(1L);
        doc.setCategoryName("Test");
        doc.setStockQuantity(1);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());

        productSearchRepository.save(doc);
        assertThat(productSearchRepository.findById(2L)).isPresent();

        productSearchRepository.deleteById(2L);
        assertThat(productSearchRepository.findById(2L)).isEmpty();
    }
}
