package com.robomart.product.integration;

import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.DeleteQuery;
import org.springframework.data.elasticsearch.core.query.Query;

import com.robomart.product.document.ProductDocument;
import com.robomart.product.service.ProductIndexWriter;

/** Clear test documents (including logical tombstones) without deleting the checked mapping/alias. */
public final class ProductSearchTestSupport {
    private ProductSearchTestSupport() { }
    public static void resetIndex(ElasticsearchOperations operations, Class<?> documentClass) {
        if (documentClass != ProductDocument.class) throw new IllegalArgumentException("Product test fixture only");
        operations.delete(DeleteQuery.builder(Query.findAll()).withRefresh(true).build(), ProductDocument.class,
                IndexCoordinates.of(ProductIndexWriter.INDEX));
    }
}
