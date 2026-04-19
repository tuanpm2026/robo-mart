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

    public static OrderBuilder order() {
        return new OrderBuilder();
    }

    public static CartItemBuilder cartItem() {
        return new CartItemBuilder();
    }

    public static InventoryItemBuilder inventoryItem() {
        return new InventoryItemBuilder();
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

    public static class OrderBuilder {
        private String userId = "user-001";
        private String status = "PENDING";
        private BigDecimal totalAmount = BigDecimal.valueOf(99.99);
        private List<OrderItemBuilder> items = new ArrayList<>();

        public OrderBuilder withUserId(String userId) { this.userId = userId; return this; }
        public OrderBuilder withStatus(String status) { this.status = status; return this; }
        public OrderBuilder withTotalAmount(BigDecimal totalAmount) {
            this.totalAmount = totalAmount;
            return this;
        }
        public OrderBuilder withItem(OrderItemBuilder item) { this.items.add(item); return this; }

        public String getUserId() { return userId; }
        public String getStatus() { return status; }
        public BigDecimal getTotalAmount() { return totalAmount; }
        public List<OrderItemBuilder> getItems() { return items; }
    }

    public static class OrderItemBuilder {
        private String productId = "prod-001";
        private String sku = "TEST-001";
        private int quantity = 1;
        private BigDecimal unitPrice = BigDecimal.valueOf(29.99);

        public OrderItemBuilder withProductId(String productId) {
            this.productId = productId;
            return this;
        }
        public OrderItemBuilder withSku(String sku) { this.sku = sku; return this; }
        public OrderItemBuilder withQuantity(int quantity) { this.quantity = quantity; return this; }
        public OrderItemBuilder withUnitPrice(BigDecimal unitPrice) {
            this.unitPrice = unitPrice;
            return this;
        }

        public String getProductId() { return productId; }
        public String getSku() { return sku; }
        public int getQuantity() { return quantity; }
        public BigDecimal getUnitPrice() { return unitPrice; }
    }

    public static class CartItemBuilder {
        private String productId = "prod-001";
        private String sku = "TEST-001";
        private String name = "Test Product";
        private int quantity = 1;
        private BigDecimal price = BigDecimal.valueOf(29.99);

        public CartItemBuilder withProductId(String productId) {
            this.productId = productId;
            return this;
        }
        public CartItemBuilder withSku(String sku) { this.sku = sku; return this; }
        public CartItemBuilder withName(String name) { this.name = name; return this; }
        public CartItemBuilder withQuantity(int quantity) { this.quantity = quantity; return this; }
        public CartItemBuilder withPrice(BigDecimal price) { this.price = price; return this; }

        public String getProductId() { return productId; }
        public String getSku() { return sku; }
        public String getName() { return name; }
        public int getQuantity() { return quantity; }
        public BigDecimal getPrice() { return price; }
    }

    public static class InventoryItemBuilder {
        private String productId = "prod-001";
        private String sku = "TEST-001";
        private int quantity = 100;
        private int reservedQuantity = 0;

        public InventoryItemBuilder withProductId(String productId) {
            this.productId = productId;
            return this;
        }
        public InventoryItemBuilder withSku(String sku) { this.sku = sku; return this; }
        public InventoryItemBuilder withQuantity(int quantity) {
            this.quantity = quantity;
            return this;
        }
        public InventoryItemBuilder withReservedQuantity(int reservedQuantity) {
            this.reservedQuantity = reservedQuantity;
            return this;
        }

        public String getProductId() { return productId; }
        public String getSku() { return sku; }
        public int getQuantity() { return quantity; }
        public int getReservedQuantity() { return reservedQuantity; }
    }
}
