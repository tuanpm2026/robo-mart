package com.robomart.product.unit.service;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.robomart.product.config.CacheConfig;
import com.robomart.product.document.ProductDocument;
import com.robomart.product.dto.ProductSearchRequest;
import com.robomart.product.service.ProductSearchService;

import io.micrometer.tracing.Tracer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(ProductSearchServiceCacheTest.TestConfig.class)
class ProductSearchServiceCacheTest {

    @EnableCaching
    @Import(ProductSearchService.class)
    static class TestConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(
                    CacheConfig.CACHE_PRODUCT_DETAIL,
                    CacheConfig.CACHE_PRODUCT_SEARCH);
        }

        @Bean
        ElasticsearchOperations elasticsearchOperations() {
            return mock(ElasticsearchOperations.class);
        }

        @Bean
        Tracer tracer() {
            return mock(Tracer.class);
        }
    }

    @Autowired
    private ProductSearchService productSearchService;

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void resetState() {
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        });
        reset(elasticsearchOperations);
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldCacheSearchResultsForSameParameters() {
        SearchHits<ProductDocument> searchHits = mock(SearchHits.class);
        when(searchHits.getSearchHits()).thenReturn(List.of());
        when(searchHits.getTotalHits()).thenReturn(0L);

        when(elasticsearchOperations.search(any(Query.class), eq(ProductDocument.class)))
                .thenReturn(searchHits);

        var request = new ProductSearchRequest("robot", null, null, null, null, null);
        var pageable = PageRequest.of(0, 10);

        var result1 = productSearchService.search(request, pageable);
        var result2 = productSearchService.search(request, pageable);

        assertThat(result1).isNotNull();
        assertThat(result2).isNotNull();
        verify(elasticsearchOperations, times(1)).search(any(Query.class), eq(ProductDocument.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldNotReturnCachedResultForDifferentKeyword() {
        SearchHits<ProductDocument> searchHits = mock(SearchHits.class);
        when(searchHits.getSearchHits()).thenReturn(List.of());
        when(searchHits.getTotalHits()).thenReturn(0L);

        when(elasticsearchOperations.search(any(Query.class), eq(ProductDocument.class)))
                .thenReturn(searchHits);

        var request1 = new ProductSearchRequest("robot", null, null, null, null, null);
        var request2 = new ProductSearchRequest("toy", null, null, null, null, null);
        var pageable = PageRequest.of(0, 10);

        productSearchService.search(request1, pageable);
        productSearchService.search(request2, pageable);

        verify(elasticsearchOperations, times(2)).search(any(Query.class), eq(ProductDocument.class));
    }
}
