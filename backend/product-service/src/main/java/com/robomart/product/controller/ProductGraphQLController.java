package com.robomart.product.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import com.robomart.common.dto.PagedResponse;
import com.robomart.product.dto.ProductConnection;
import com.robomart.product.dto.ProductListResponse;
import com.robomart.product.dto.ProductSearchRequest;
import com.robomart.product.entity.Category;
import com.robomart.product.entity.Product;
import com.robomart.product.entity.ProductImage;
import com.robomart.product.repository.CategoryRepository;
import com.robomart.product.repository.ProductImageRepository;
import com.robomart.product.repository.ProductRepository;
import com.robomart.product.service.ProductSearchService;

@Controller
public class ProductGraphQLController {

    private static final Logger log = LoggerFactory.getLogger(ProductGraphQLController.class);
    private static final int MAX_PAGE_SIZE = 100;

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductImageRepository productImageRepository;
    private final ProductSearchService productSearchService;

    public ProductGraphQLController(ProductRepository productRepository,
                                     CategoryRepository categoryRepository,
                                     ProductImageRepository productImageRepository,
                                     ProductSearchService productSearchService) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.productImageRepository = productImageRepository;
        this.productSearchService = productSearchService;
    }

    @QueryMapping
    public Product product(@Argument Long id) {
        log.debug("GraphQL: Fetching product with id={}", id);
        return productRepository.findById(id).orElse(null);
    }

    @QueryMapping
    public ProductConnection products(@Argument Long categoryId,
                                       @Argument BigDecimal minPrice,
                                       @Argument BigDecimal maxPrice,
                                       @Argument String keyword,
                                       @Argument String brand,
                                       @Argument BigDecimal minRating,
                                       @Argument Integer page,
                                       @Argument Integer size) {
        int p = Math.max(0, (page != null) ? page : 0);
        int s = Math.max(1, Math.min((size != null) ? size : 20, MAX_PAGE_SIZE));
        log.debug("GraphQL: Searching products keyword={}, categoryId={}, page={}, size={}",
                keyword, categoryId, p, s);

        var searchRequest = new ProductSearchRequest(
                keyword, minPrice, maxPrice, brand, minRating, categoryId);
        PagedResponse<ProductListResponse> searchResult =
                productSearchService.search(searchRequest, PageRequest.of(p, s));

        List<Long> productIds = searchResult.data().stream()
                .map(ProductListResponse::id)
                .toList();

        if (productIds.isEmpty()) {
            return new ProductConnection(
                    List.of(),
                    (int) searchResult.pagination().totalElements(),
                    searchResult.pagination().totalPages(),
                    searchResult.pagination().page(),
                    searchResult.pagination().size());
        }

        List<Product> entities = productRepository.findAllById(productIds);
        Map<Long, Product> entityMap = entities.stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        List<Product> ordered = productIds.stream()
                .map(entityMap::get)
                .filter(Objects::nonNull)
                .toList();

        return new ProductConnection(
                ordered,
                (int) searchResult.pagination().totalElements(),
                searchResult.pagination().totalPages(),
                searchResult.pagination().page(),
                searchResult.pagination().size());
    }

    @BatchMapping
    public Map<Product, Category> category(List<Product> products) {
        Set<Long> categoryIds = products.stream()
                .map(p -> p.getCategory().getId())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, Category> categoryMap = categoryRepository.findAllById(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));

        return products.stream()
                .filter(p -> p.getCategory() != null && categoryMap.containsKey(p.getCategory().getId()))
                .collect(Collectors.toMap(
                        Function.identity(),
                        p -> categoryMap.get(p.getCategory().getId())));
    }

    @BatchMapping
    public Map<Product, List<ProductImage>> images(List<Product> products) {
        List<Long> productIds = products.stream()
                .map(Product::getId)
                .toList();

        List<ProductImage> allImages =
                productImageRepository.findByProductIdInOrderByDisplayOrderAsc(productIds);

        Map<Long, List<ProductImage>> imagesByProductId = allImages.stream()
                .collect(Collectors.groupingBy(img -> img.getProduct().getId()));

        return products.stream()
                .collect(Collectors.toMap(
                        Function.identity(),
                        p -> imagesByProductId.getOrDefault(p.getId(), List.of())));
    }
}
