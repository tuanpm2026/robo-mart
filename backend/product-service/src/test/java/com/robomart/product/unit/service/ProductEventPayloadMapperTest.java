package com.robomart.product.unit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import com.robomart.product.entity.Category;
import com.robomart.product.entity.Product;
import com.robomart.product.mapper.ProductEventPayloadMapper;

class ProductEventPayloadMapperTest {
    @Test
    void liveEditsAndSeedIndexingKeepRatingInTheSameSerializableSnapshot() {
        var product = new Product();
        product.setCategory(new Category());
        product.setName("Updated product");
        product.setPrice(new BigDecimal("19.95"));
        product.setRating(new BigDecimal("4.75"));
        var mapper = JsonMapper.builder().build();
        var json = mapper.readTree(mapper.writeValueAsString(ProductEventPayloadMapper.snapshot(product)));
        assertThat(json.path("rating").decimalValue()).isEqualByComparingTo("4.75");
        assertThat(json.path("price").decimalValue()).isEqualByComparingTo("19.95");
        assertThat(json.path("name").asString()).isEqualTo("Updated product");
        product.setRating(null);
        assertThat(ProductEventPayloadMapper.snapshot(product)).containsEntry("rating", null);
    }
}
