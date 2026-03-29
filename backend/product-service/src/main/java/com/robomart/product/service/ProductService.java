package com.robomart.product.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.robomart.common.dto.PagedResponse;
import com.robomart.common.dto.PaginationMeta;
import com.robomart.product.dto.ProductDetailResponse;
import com.robomart.product.dto.ProductListResponse;
import com.robomart.product.entity.Product;
import com.robomart.product.exception.ProductNotFoundException;
import com.robomart.product.mapper.ProductMapper;
import com.robomart.product.repository.ProductRepository;

import io.micrometer.tracing.Tracer;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);
    private static final int MAX_PAGE_SIZE = 100;

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;
    private final Tracer tracer;

    public ProductService(ProductRepository productRepository,
                          ProductMapper productMapper,
                          Tracer tracer) {
        this.productRepository = productRepository;
        this.productMapper = productMapper;
        this.tracer = tracer;
    }

    public PagedResponse<ProductListResponse> getProducts(Long categoryId, Pageable pageable) {
        Pageable clampedPageable = clampPageSize(pageable);

        Page<Product> page;
        if (categoryId != null) {
            log.debug("Fetching products for categoryId={}, page={}", categoryId, clampedPageable);
            page = productRepository.findByCategoryId(categoryId, clampedPageable);
        } else {
            log.debug("Fetching all products, page={}", clampedPageable);
            page = productRepository.findAllWithDetails(clampedPageable);
        }

        var products = productMapper.toListResponse(page.getContent());
        var pagination = new PaginationMeta(
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );

        return new PagedResponse<>(products, pagination, getTraceId());
    }

    @Cacheable(value = "productDetail", key = "#productId")
    public ProductDetailResponse getProductById(Long productId) {
        log.debug("Fetching product detail for id={}", productId);

        Product product = productRepository.findByIdWithDetails(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        return productMapper.toDetailResponse(product);
    }

    private Pageable clampPageSize(Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
        }
        return pageable;
    }

    private String getTraceId() {
        var span = tracer.currentSpan();
        if (span != null) {
            var context = span.context();
            if (context != null) {
                return context.traceId();
            }
        }
        return "no-trace";
    }
}
