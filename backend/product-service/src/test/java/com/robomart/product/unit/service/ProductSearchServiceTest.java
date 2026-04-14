package com.robomart.product.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;

import com.robomart.common.dto.PagedResponse;
import com.robomart.product.document.ProductDocument;
import com.robomart.product.dto.ProductListResponse;
import com.robomart.product.dto.ProductSearchRequest;
import com.robomart.product.entity.Category;
import com.robomart.product.entity.Product;
import com.robomart.product.mapper.ProductMapper;
import com.robomart.product.repository.ProductRepository;
import com.robomart.product.service.ProductSearchService;

import io.micrometer.tracing.Tracer;

@ExtendWith(MockitoExtension.class)
class ProductSearchServiceTest {

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private Tracer tracer;

    @Mock
    private SearchHits<ProductDocument> searchHits;

    @InjectMocks
    private ProductSearchService productSearchService;

    @Captor
    private ArgumentCaptor<NativeQuery> queryCaptor;

    @Test
    void shouldReturnRelevantProductsWhenKeywordSearch() {
        var doc = createProductDocument(1L, "SKU-001", "Wireless Headphone", BigDecimal.valueOf(99.99),
                BigDecimal.valueOf(4.5), "Sony", 50, "Electronics");

        var searchHit = new SearchHit<>("products", "1", null, 5.0f, null, null, null, null, null, null, doc);
        when(searchHits.getSearchHits()).thenReturn(List.of(searchHit));
        when(searchHits.getTotalHits()).thenReturn(1L);
        when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
                .thenReturn(searchHits);

        var request = new ProductSearchRequest("wireless headphone", null, null, null, null, null);
        PagedResponse<ProductListResponse> result = productSearchService.search(request, PageRequest.of(0, 20));

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().getFirst().name()).isEqualTo("Wireless Headphone");
        assertThat(result.data().getFirst().brand()).isEqualTo("Sony");
        assertThat(result.data().getFirst().price()).isEqualByComparingTo(BigDecimal.valueOf(99.99));
        assertThat(result.pagination().totalElements()).isEqualTo(1L);
        assertThat(result.pagination().page()).isZero();
        assertThat(result.pagination().size()).isEqualTo(20);
    }

    @Test
    void shouldReturnFilteredResultsWhenMultipleFiltersApplied() {
        var doc = createProductDocument(2L, "SKU-002", "Premium Headphone", BigDecimal.valueOf(149.99),
                BigDecimal.valueOf(4.8), "Sony", 30, "Electronics");

        var searchHit = new SearchHit<>("products", "2", null, 3.0f, null, null, null, null, null, null, doc);
        when(searchHits.getSearchHits()).thenReturn(List.of(searchHit));
        when(searchHits.getTotalHits()).thenReturn(1L);
        when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
                .thenReturn(searchHits);

        var request = new ProductSearchRequest("headphone", BigDecimal.valueOf(50), BigDecimal.valueOf(200),
                "Sony", BigDecimal.valueOf(4.0), 3L);
        PagedResponse<ProductListResponse> result = productSearchService.search(request, PageRequest.of(0, 20));

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().getFirst().id()).isEqualTo(2L);
        assertThat(result.data().getFirst().brand()).isEqualTo("Sony");
    }

    @Test
    void shouldReturnResultsWhenPartialFiltersApplied() {
        var doc = createProductDocument(3L, "SKU-003", "Budget Speaker", BigDecimal.valueOf(29.99),
                BigDecimal.valueOf(3.5), "JBL", 100, "Audio");

        var searchHit = new SearchHit<>("products", "3", null, 1.0f, null, null, null, null, null, null, doc);
        when(searchHits.getSearchHits()).thenReturn(List.of(searchHit));
        when(searchHits.getTotalHits()).thenReturn(1L);
        when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
                .thenReturn(searchHits);

        var request = new ProductSearchRequest(null, null, BigDecimal.valueOf(50), null, null, null);
        PagedResponse<ProductListResponse> result = productSearchService.search(request, PageRequest.of(0, 20));

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().getFirst().price()).isEqualByComparingTo(BigDecimal.valueOf(29.99));
    }

    @Test
    void shouldReturnAllProductsWhenNoKeywordProvided() {
        var doc1 = createProductDocument(1L, "SKU-001", "Product A", BigDecimal.valueOf(10.00),
                BigDecimal.valueOf(4.0), "BrandA", 10, "Cat1");
        var doc2 = createProductDocument(2L, "SKU-002", "Product B", BigDecimal.valueOf(20.00),
                BigDecimal.valueOf(3.0), "BrandB", 20, "Cat2");

        var hit1 = new SearchHit<>("products", "1", null, 1.0f, null, null, null, null, null, null, doc1);
        var hit2 = new SearchHit<>("products", "2", null, 1.0f, null, null, null, null, null, null, doc2);
        when(searchHits.getSearchHits()).thenReturn(List.of(hit1, hit2));
        when(searchHits.getTotalHits()).thenReturn(2L);
        when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
                .thenReturn(searchHits);

        var request = new ProductSearchRequest(null, null, null, null, null, null);
        PagedResponse<ProductListResponse> result = productSearchService.search(request, PageRequest.of(0, 20));

        assertThat(result.data()).hasSize(2);
        assertThat(result.pagination().totalElements()).isEqualTo(2L);
    }

    @Test
    void shouldReturnEmptyResultsWhenNoMatchingProducts() {
        when(searchHits.getSearchHits()).thenReturn(Collections.emptyList());
        when(searchHits.getTotalHits()).thenReturn(0L);
        when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
                .thenReturn(searchHits);

        var request = new ProductSearchRequest("nonexistent product xyz", null, null, null, null, null);
        PagedResponse<ProductListResponse> result = productSearchService.search(request, PageRequest.of(0, 20));

        assertThat(result.data()).isEmpty();
        assertThat(result.pagination().totalElements()).isZero();
        assertThat(result.pagination().totalPages()).isZero();
    }

    @Test
    void shouldMapProductDocumentFieldsCorrectly() {
        var doc = createProductDocument(5L, "ELEC-005", "Robot Toy", BigDecimal.valueOf(59.99),
                BigDecimal.valueOf(4.2), "ToyBrand", 75, "Toys");

        var searchHit = new SearchHit<>("products", "5", null, 2.0f, null, null, null, null, null, null, doc);
        when(searchHits.getSearchHits()).thenReturn(List.of(searchHit));
        when(searchHits.getTotalHits()).thenReturn(1L);
        when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
                .thenReturn(searchHits);

        var request = new ProductSearchRequest("robot", null, null, null, null, null);
        PagedResponse<ProductListResponse> result = productSearchService.search(request, PageRequest.of(0, 20));

        ProductListResponse product = result.data().getFirst();
        assertThat(product.id()).isEqualTo(5L);
        assertThat(product.sku()).isEqualTo("ELEC-005");
        assertThat(product.name()).isEqualTo("Robot Toy");
        assertThat(product.price()).isEqualByComparingTo(BigDecimal.valueOf(59.99));
        assertThat(product.rating()).isEqualByComparingTo(BigDecimal.valueOf(4.2));
        assertThat(product.brand()).isEqualTo("ToyBrand");
        assertThat(product.stockQuantity()).isEqualTo(75);
        assertThat(product.categoryName()).isEqualTo("Toys");
        assertThat(product.primaryImageUrl()).isNull();
    }

    @Test
    void shouldCalculatePaginationCorrectly() {
        when(searchHits.getSearchHits()).thenReturn(Collections.emptyList());
        when(searchHits.getTotalHits()).thenReturn(55L);
        when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
                .thenReturn(searchHits);

        var request = new ProductSearchRequest(null, null, null, null, null, null);
        PagedResponse<ProductListResponse> result = productSearchService.search(request, PageRequest.of(2, 20));

        assertThat(result.pagination().page()).isEqualTo(2);
        assertThat(result.pagination().size()).isEqualTo(20);
        assertThat(result.pagination().totalElements()).isEqualTo(55L);
        assertThat(result.pagination().totalPages()).isEqualTo(3);
    }

    @Test
    void shouldClampPageSizeWhenExceedsMax() {
        when(searchHits.getSearchHits()).thenReturn(Collections.emptyList());
        when(searchHits.getTotalHits()).thenReturn(0L);
        when(elasticsearchOperations.search(queryCaptor.capture(), eq(ProductDocument.class)))
                .thenReturn(searchHits);

        var request = new ProductSearchRequest(null, null, null, null, null, null);
        PagedResponse<ProductListResponse> result = productSearchService.search(request, PageRequest.of(0, 200));

        assertThat(result.pagination().size()).isEqualTo(100);
    }

    @Test
    void shouldPassQueryToElasticsearchOperations() {
        when(searchHits.getSearchHits()).thenReturn(Collections.emptyList());
        when(searchHits.getTotalHits()).thenReturn(0L);
        when(elasticsearchOperations.search(queryCaptor.capture(), eq(ProductDocument.class)))
                .thenReturn(searchHits);

        var request = new ProductSearchRequest("test", BigDecimal.valueOf(10), BigDecimal.valueOf(100),
                "Sony", BigDecimal.valueOf(3.0), 1L);
        productSearchService.search(request, PageRequest.of(0, 20));

        NativeQuery capturedQuery = queryCaptor.getValue();
        assertThat(capturedQuery).isNotNull();
        assertThat(capturedQuery.getPageable().getPageSize()).isEqualTo(20);
        assertThat(capturedQuery.getPageable().getPageNumber()).isZero();
    }

    @Test
    void shouldFallBackToPostgresWhenElasticsearchThrows() {
        when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
                .thenThrow(new RuntimeException("Elasticsearch unavailable"));

        Product product = createProduct(10L, "SKU-010", "Fallback Product", BigDecimal.valueOf(19.99), "FallbackBrand");
        Page<Product> page = new PageImpl<>(List.of(product));
        when(productRepository.searchByKeywordLike(eq("fallback"), eq(null), any(Pageable.class)))
                .thenReturn(page);

        ProductListResponse response = new ProductListResponse(10L, "SKU-010", "Fallback Product",
                null, BigDecimal.valueOf(19.99), null, "FallbackBrand", 5, null, null, null);
        when(productMapper.toListResponse(product)).thenReturn(response);

        var request = new ProductSearchRequest("fallback", null, null, null, null, null);
        PagedResponse<ProductListResponse> result = productSearchService.search(request, PageRequest.of(0, 20));

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().getFirst().name()).isEqualTo("Fallback Product");
        verify(productRepository).searchByKeywordLike(eq("fallback"), eq(null), any(Pageable.class));
    }

    @Test
    void shouldReturnPagedResponseFromPostgresFallback() {
        when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
                .thenThrow(new RuntimeException("ES down"));

        Product p1 = createProduct(1L, "SKU-001", "Product A", BigDecimal.valueOf(10.00), "Brand1");
        Product p2 = createProduct(2L, "SKU-002", "Product B", BigDecimal.valueOf(20.00), "Brand2");
        Page<Product> page = new PageImpl<>(List.of(p1, p2), PageRequest.of(0, 20), 2);
        when(productRepository.searchByKeywordLike(eq(null), eq(null), any(Pageable.class)))
                .thenReturn(page);

        ProductListResponse r1 = new ProductListResponse(1L, "SKU-001", "Product A",
                null, BigDecimal.valueOf(10.00), null, "Brand1", 10, null, null, null);
        ProductListResponse r2 = new ProductListResponse(2L, "SKU-002", "Product B",
                null, BigDecimal.valueOf(20.00), null, "Brand2", 20, null, null, null);
        when(productMapper.toListResponse(p1)).thenReturn(r1);
        when(productMapper.toListResponse(p2)).thenReturn(r2);

        var request = new ProductSearchRequest(null, null, null, null, null, null);
        PagedResponse<ProductListResponse> result = productSearchService.search(request, PageRequest.of(0, 20));

        assertThat(result.data()).hasSize(2);
        assertThat(result.pagination().totalElements()).isEqualTo(2);
        assertThat(result.pagination().page()).isZero();
    }

    private Product createProduct(Long id, String sku, String name, BigDecimal price, String brand) {
        Product p = new Product();
        p.setSku(sku);
        p.setName(name);
        p.setPrice(price);
        p.setBrand(brand);
        p.setStockQuantity(5);
        p.setActive(true);
        try {
            var idField = com.robomart.common.entity.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(p, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Category cat = new Category();
        p.setCategory(cat);
        return p;
    }

    private ProductDocument createProductDocument(Long id, String sku, String name, BigDecimal price,
                                                   BigDecimal rating, String brand, int stockQuantity,
                                                   String categoryName) {
        var doc = new ProductDocument();
        doc.setId(id);
        doc.setSku(sku);
        doc.setName(name);
        doc.setPrice(price);
        doc.setRating(rating);
        doc.setBrand(brand);
        doc.setStockQuantity(stockQuantity);
        doc.setCategoryName(categoryName);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        return doc;
    }
}
