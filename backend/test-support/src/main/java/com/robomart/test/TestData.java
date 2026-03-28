package com.robomart.test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class TestData {

    private TestData() {
    }

    public static CategoryBuilder category() {
        return new CategoryBuilder();
    }

    public static ProductBuilder product() {
        return new ProductBuilder();
    }

    public static ProductImageBuilder productImage() {
        return new ProductImageBuilder();
    }

    public static class CategoryBuilder {
        private String name = "Test Category";
        private String description = "Test category description";

        public CategoryBuilder withName(String name) {
            this.name = name;
            return this;
        }

        public CategoryBuilder withDescription(String description) {
            this.description = description;
            return this;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
    }

    public static class ProductBuilder {
        private String sku = "TEST-001";
        private String name = "Test Product";
        private String description = "Test product description";
        private BigDecimal price = BigDecimal.valueOf(29.99);
        private BigDecimal rating = BigDecimal.valueOf(4.50);
        private String brand = "TestBrand";
        private Integer stockQuantity = 100;

        public ProductBuilder withSku(String sku) {
            this.sku = sku;
            return this;
        }

        public ProductBuilder withName(String name) {
            this.name = name;
            return this;
        }

        public ProductBuilder withDescription(String description) {
            this.description = description;
            return this;
        }

        public ProductBuilder withPrice(BigDecimal price) {
            this.price = price;
            return this;
        }

        public ProductBuilder withRating(BigDecimal rating) {
            this.rating = rating;
            return this;
        }

        public ProductBuilder withBrand(String brand) {
            this.brand = brand;
            return this;
        }

        public ProductBuilder withStockQuantity(Integer stockQuantity) {
            this.stockQuantity = stockQuantity;
            return this;
        }

        public String getSku() { return sku; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public BigDecimal getPrice() { return price; }
        public BigDecimal getRating() { return rating; }
        public String getBrand() { return brand; }
        public Integer getStockQuantity() { return stockQuantity; }
    }

    public static class ProductImageBuilder {
        private String imageUrl = "https://images.robomart.com/products/test.jpg";
        private String altText = "Test product image";
        private Integer displayOrder = 0;

        public ProductImageBuilder withImageUrl(String imageUrl) {
            this.imageUrl = imageUrl;
            return this;
        }

        public ProductImageBuilder withAltText(String altText) {
            this.altText = altText;
            return this;
        }

        public ProductImageBuilder withDisplayOrder(Integer displayOrder) {
            this.displayOrder = displayOrder;
            return this;
        }

        public String getImageUrl() { return imageUrl; }
        public String getAltText() { return altText; }
        public Integer getDisplayOrder() { return displayOrder; }
    }
}
