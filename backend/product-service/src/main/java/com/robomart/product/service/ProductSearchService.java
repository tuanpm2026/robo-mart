package com.robomart.product.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import com.robomart.common.dto.PagedResponse;
import com.robomart.common.dto.PaginationMeta;
import com.robomart.product.document.ProductDocument;
import com.robomart.product.dto.ProductListResponse;
import com.robomart.product.dto.ProductSearchRequest;

import io.micrometer.tracing.Tracer;

@Service
public class ProductSearchService {

    private static final Logger log = LoggerFactory.getLogger(ProductSearchService.class);
    private static final int MAX_PAGE_SIZE = 100;

    private final ElasticsearchOperations elasticsearchOperations;
    private final Tracer tracer;

    public ProductSearchService(ElasticsearchOperations elasticsearchOperations, Tracer tracer) {
        this.elasticsearchOperations = elasticsearchOperations;
        this.tracer = tracer;
    }

    public PagedResponse<ProductListResponse> search(ProductSearchRequest request, Pageable pageable) {
        Pageable clampedPageable = clampPageSize(pageable);

        NativeQuery query = buildSearchQuery(request, clampedPageable);

        log.debug("Executing product search: keyword={}, filters=[categoryId={}, brand={}, price={}-{}, minRating={}]",
                request.keyword(), request.categoryId(), request.brand(),
                request.minPrice(), request.maxPrice(), request.minRating());

        SearchHits<ProductDocument> searchHits = elasticsearchOperations.search(query, ProductDocument.class);

        List<ProductListResponse> products = searchHits.getSearchHits().stream()
                .map(hit -> toProductListResponse(hit.getContent()))
                .toList();

        var pagination = new PaginationMeta(
                clampedPageable.getPageNumber(),
                clampedPageable.getPageSize(),
                searchHits.getTotalHits(),
                calculateTotalPages(searchHits.getTotalHits(), clampedPageable.getPageSize())
        );

        return new PagedResponse<>(products, pagination, getTraceId());
    }

    private NativeQuery buildSearchQuery(ProductSearchRequest request, Pageable pageable) {
        return NativeQuery.builder()
                .withQuery(q -> q.bool(b -> {
                    if (request.keyword() != null && !request.keyword().isBlank()) {
                        b.must(m -> m.multiMatch(mm -> mm
                                .query(request.keyword())
                                .fields("name^3", "description", "brand^2", "categoryName")
                        ));
                    } else {
                        b.must(m -> m.matchAll(ma -> ma));
                    }

                    if (request.categoryId() != null) {
                        b.filter(f -> f.term(t -> t.field("categoryId").value(request.categoryId())));
                    }

                    if (request.brand() != null && !request.brand().isBlank()) {
                        b.filter(f -> f.term(t -> t.field("brand").value(request.brand())));
                    }

                    if (request.minPrice() != null || request.maxPrice() != null) {
                        b.filter(f -> f.range(r -> r.number(n -> {
                            n.field("price");
                            if (request.minPrice() != null) n.gte(request.minPrice().doubleValue());
                            if (request.maxPrice() != null) n.lte(request.maxPrice().doubleValue());
                            return n;
                        })));
                    }

                    if (request.minRating() != null) {
                        b.filter(f -> f.range(r -> r.number(n -> n
                                .field("rating")
                                .gte(request.minRating().doubleValue())
                        )));
                    }

                    return b;
                }))
                .withPageable(pageable)
                .build();
    }

    private ProductListResponse toProductListResponse(ProductDocument doc) {
        return new ProductListResponse(
                doc.getId(),
                doc.getSku(),
                doc.getName(),
                doc.getPrice(),
                doc.getRating(),
                doc.getBrand(),
                doc.getStockQuantity(),
                doc.getCategoryName(),
                null
        );
    }

    private int calculateTotalPages(long totalHits, int pageSize) {
        return (int) Math.ceil((double) totalHits / pageSize);
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
