package com.robomart.product.repository;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import com.robomart.product.document.ProductDocument;

public interface ProductSearchRepository extends ElasticsearchRepository<ProductDocument, Long> {
}
