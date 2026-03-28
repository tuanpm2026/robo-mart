package com.robomart.product.unit.mapper;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.robomart.product.entity.Category;
import com.robomart.product.entity.Product;
import com.robomart.product.entity.ProductImage;
import com.robomart.product.mapper.ProductMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ProductMapperTest {

    private final ProductMapper mapper = Mappers.getMapper(ProductMapper.class);

    @Test
    void shouldMapProductToListResponse() {
        var product = createProductWithImage();

        var result = mapper.toListResponse(product);

        assertThat(result.id()).isNull(); // ID not set in test (no persistence)
        assertThat(result.sku()).isEqualTo("TEST-001");
        assertThat(result.name()).isEqualTo("Test Product");
        assertThat(result.price()).isEqualByComparingTo(BigDecimal.valueOf(29.99));
        assertThat(result.categoryName()).isEqualTo("Electronics");
        assertThat(result.primaryImageUrl()).isEqualTo("https://images.robomart.com/test-1.jpg");
    }

    @Test
    void shouldReturnNullPrimaryImageWhenNoImages() {
        var product = createProductWithoutImage();

        var result = mapper.toListResponse(product);

        assertThat(result.primaryImageUrl()).isNull();
    }

    @Test
    void shouldMapProductToDetailResponse() {
        var product = createProductWithImage();

        var result = mapper.toDetailResponse(product);

        assertThat(result.sku()).isEqualTo("TEST-001");
        assertThat(result.name()).isEqualTo("Test Product");
        assertThat(result.description()).isEqualTo("A test product");
        assertThat(result.category().name()).isEqualTo("Electronics");
        assertThat(result.images()).hasSize(1);
        assertThat(result.images().getFirst().imageUrl()).isEqualTo("https://images.robomart.com/test-1.jpg");
    }

    @Test
    void shouldMapCategoryToCategoryResponse() {
        var category = new Category();
        category.setName("Electronics");
        category.setDescription("Electronic devices");

        var result = mapper.toCategoryResponse(category);

        assertThat(result.name()).isEqualTo("Electronics");
        assertThat(result.description()).isEqualTo("Electronic devices");
    }

    private Product createProductWithImage() {
        var category = new Category();
        category.setName("Electronics");

        var image = new ProductImage();
        image.setImageUrl("https://images.robomart.com/test-1.jpg");
        image.setAltText("Test image");
        image.setDisplayOrder(0);

        var product = new Product();
        product.setSku("TEST-001");
        product.setName("Test Product");
        product.setDescription("A test product");
        product.setPrice(BigDecimal.valueOf(29.99));
        product.setCategory(category);
        product.setRating(BigDecimal.valueOf(4.50));
        product.setBrand("TestBrand");
        product.setStockQuantity(50);
        product.setImages(List.of(image));

        return product;
    }

    private Product createProductWithoutImage() {
        var category = new Category();
        category.setName("Electronics");

        var product = new Product();
        product.setSku("TEST-002");
        product.setName("No Image Product");
        product.setPrice(BigDecimal.valueOf(19.99));
        product.setCategory(category);
        product.setStockQuantity(10);

        return product;
    }
}
