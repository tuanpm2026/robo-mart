package com.robomart.product.service;

import java.io.IOException;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.json.JsonData;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.robomart.product.document.ProductDocument;
import io.micrometer.core.instrument.MeterRegistry;

/** Permanent logical tombstones retain their external version beyond Elasticsearch delete GC. */
@Service
public class ProductIndexWriter {
    public static final String INDEX = "products_v1";
    public static final String ALIAS = "products-search";
    private final ElasticsearchClient client;
    private final ElasticsearchOperations elastic;
    private final MeterRegistry meters;

    public ProductIndexWriter(ElasticsearchClient client, ElasticsearchOperations elastic, MeterRegistry meters) {
        this.client = client;
        this.elastic = elastic;
        this.meters = meters;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void write(ProductDocument snapshot) {
        if (snapshot.getSourceVersion() == null || snapshot.getSourceVersion() < 0) {
            throw new IllegalArgumentException("Product projection requires a source version");
        }
        String source = elastic.getElasticsearchConverter().mapObject(snapshot).toJson();
        try {
            client.index(request -> request.index(ALIAS).id(snapshot.getId().toString()).requireAlias(true)
                    .version(Math.addExact(snapshot.getSourceVersion(), 1)).versionType(VersionType.External)
                    .document(JsonData.fromJson(source)));
        } catch (ElasticsearchException failure) {
            if (failure.status() != 409 || !"version_conflict_engine_exception".equals(failure.error().type())) throw failure;
            meters.counter("product.projection.stale").increment();
        } catch (IOException unavailable) {
            throw new DataAccessResourceFailureException("Elasticsearch projection write failed", unavailable);
        }
    }
}
