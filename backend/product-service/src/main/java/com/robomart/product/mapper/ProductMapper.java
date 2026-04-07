package com.robomart.product.mapper;

import java.util.List;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import com.robomart.product.dto.CategoryResponse;
import com.robomart.product.dto.CreateProductRequest;
import com.robomart.product.dto.ProductDetailResponse;
import com.robomart.product.dto.ProductImageResponse;
import com.robomart.product.dto.ProductListResponse;
import com.robomart.product.dto.UpdateProductRequest;
import com.robomart.product.entity.Category;
import com.robomart.product.entity.Product;
import com.robomart.product.entity.ProductImage;

@Mapper(componentModel = "spring")
public interface ProductMapper {

    @Mapping(target = "categoryName", source = "category.name")
    @Mapping(target = "categoryId", source = "category.id")
    @Mapping(target = "primaryImageUrl", expression = "java(getPrimaryImageUrl(product))")
    ProductListResponse toListResponse(Product product);

    List<ProductListResponse> toListResponse(List<Product> products);

    ProductDetailResponse toDetailResponse(Product product);

    CategoryResponse toCategoryResponse(Category category);

    ProductImageResponse toImageResponse(ProductImage image);

    List<ProductImageResponse> toImageResponseList(List<ProductImage> images);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "images", ignore = true)
    @Mapping(target = "active", ignore = true)
    @Mapping(target = "rating", ignore = true)
    @Mapping(target = "stockQuantity", ignore = true)
    @Mapping(target = "category", ignore = true)
    Product toEntity(CreateProductRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "sku", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "images", ignore = true)
    @Mapping(target = "active", ignore = true)
    @Mapping(target = "rating", ignore = true)
    @Mapping(target = "stockQuantity", ignore = true)
    @Mapping(target = "category", ignore = true)
    void updateEntityFromRequest(UpdateProductRequest request, @MappingTarget Product product);

    default String getPrimaryImageUrl(Product product) {
        if (product.getImages() == null || product.getImages().isEmpty()) {
            return null;
        }
        return product.getImages().getFirst().getImageUrl();
    }
}
