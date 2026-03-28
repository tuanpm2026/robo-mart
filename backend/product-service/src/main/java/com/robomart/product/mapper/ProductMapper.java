package com.robomart.product.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.robomart.product.dto.CategoryResponse;
import com.robomart.product.dto.ProductDetailResponse;
import com.robomart.product.dto.ProductImageResponse;
import com.robomart.product.dto.ProductListResponse;
import com.robomart.product.entity.Category;
import com.robomart.product.entity.Product;
import com.robomart.product.entity.ProductImage;

@Mapper(componentModel = "spring")
public interface ProductMapper {

    @Mapping(target = "categoryName", source = "category.name")
    @Mapping(target = "primaryImageUrl", expression = "java(getPrimaryImageUrl(product))")
    ProductListResponse toListResponse(Product product);

    List<ProductListResponse> toListResponse(List<Product> products);

    ProductDetailResponse toDetailResponse(Product product);

    CategoryResponse toCategoryResponse(Category category);

    ProductImageResponse toImageResponse(ProductImage image);

    List<ProductImageResponse> toImageResponseList(List<ProductImage> images);

    default String getPrimaryImageUrl(Product product) {
        if (product.getImages() == null || product.getImages().isEmpty()) {
            return null;
        }
        return product.getImages().getFirst().getImageUrl();
    }
}
