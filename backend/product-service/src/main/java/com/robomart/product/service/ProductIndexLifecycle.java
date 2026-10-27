package com.robomart.product.service;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.json.JsonData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Initialize before Kafka listeners start, then resume bounded bootstrap pages after a restart. */
@Component
@ConditionalOnProperty(prefix = "product.search", name = "initialize-index", havingValue = "true", matchIfMissing = true)
public class ProductIndexLifecycle implements SmartInitializingSingleton {
    private static final Logger LOG = LoggerFactory.getLogger(ProductIndexLifecycle.class);
    private final ElasticsearchClient elastic;
    private final ProductReindexService reindex;
    private volatile String state = "STARTING";
    private long nextCheck;

    public ProductIndexLifecycle(ElasticsearchClient elastic, ProductReindexService reindex) {
        this.elastic = elastic;
        this.reindex = reindex;
    }

    @Override
    public void afterSingletonsInstantiated() { initialize(); }

    @Scheduled(fixedDelayString = "${product.search.bootstrap-interval:PT5S}", initialDelayString = "PT5S")
    public synchronized void initialize() {
        if ("READY".equals(state) && System.nanoTime() < nextCheck) return;
        try {
            ensureIndexAndAlias();
            var mapping = elastic.indices().getMapping(request -> request.index(ProductIndexWriter.INDEX))
                    .get(ProductIndexWriter.INDEX).mappings();
            var fields = mapping.properties();
            if (!fields.containsKey("brand") || !fields.get("brand").isKeyword()
                    || !fields.containsKey("price") || !fields.get("price").isScaledFloat()
                    || fields.get("price").scaledFloat().scalingFactor() != 100
                    || !fields.containsKey("name") || !fields.get("name").isText()
                    || !"vi_fold".equals(fields.get("name").text().analyzer())
                    || !fields.containsKey("deleted") || !fields.get("deleted").isBoolean()
                    || !fields.containsKey("sourceVersion") || !fields.get("sourceVersion").isLong()
                    || !mapping.meta().containsKey("schema_version")
                    || mapping.meta().get("schema_version").to(Integer.class) != 1) {
                throw new IllegalStateException("Unexpected products_v1 mapping; preserve it and migrate to a new index version");
            }
            boolean complete = mapping.meta().containsKey("bootstrap_complete")
                    && Boolean.TRUE.equals(mapping.meta().get("bootstrap_complete").to(Boolean.class));
            if (!complete) {
                state = "REBUILDING";
                long after = mapping.meta().containsKey("bootstrap_after_id")
                        ? mapping.meta().get("bootstrap_after_id").to(Long.class) : 0;
                var page = reindex.page(after, 100);
                complete = !page.hasNext();
                boolean done = complete;
                elastic.indices().putMapping(request -> request.index(ProductIndexWriter.INDEX).meta(Map.of(
                        "schema_version", JsonData.of(1), "bootstrap_complete", JsonData.of(done),
                        "bootstrap_after_id", JsonData.of(page.nextAfterId()))));
                if (complete) elastic.indices().refresh(request -> request.index(ProductIndexWriter.INDEX));
            }
            state = complete ? "READY" : "REBUILDING";
            if (complete) nextCheck = System.nanoTime() + java.time.Duration.ofMinutes(1).toNanos();
        } catch (IOException | RuntimeException failure) {
            if (ProductSearchService.isConnectionFailure(failure)) {
                state = "POSTGRES_FALLBACK";
                LOG.error("Search index unavailable; retaining PostgreSQL fallback and retrying initialization", failure);
            } else {
                state = "ERROR";
                throw new IllegalStateException("Product search index initialization failed", failure);
            }
        }
    }

    private void ensureIndexAndAlias() throws IOException {
        if (!elastic.indices().exists(request -> request.index(ProductIndexWriter.INDEX)).value()) {
            String settings = resource("elasticsearch/products-settings.json");
            String mapping = resource("elasticsearch/products-mapping.json");
            try {
                elastic.indices().create(request -> request.index(ProductIndexWriter.INDEX)
                        .withJson(new StringReader("{\"settings\":" + settings + ",\"mappings\":" + mapping + "}")));
            } catch (ElasticsearchException concurrentCreate) {
                if (!"resource_already_exists_exception".equals(concurrentCreate.error().type())) throw concurrentCreate;
            }
        }
        if (elastic.indices().existsAlias(request -> request.name(ProductIndexWriter.ALIAS)).value()) {
            var targets = elastic.indices().getAlias(request -> request.name(ProductIndexWriter.ALIAS)).aliases().keySet();
            if (targets.size() != 1 || !targets.contains(ProductIndexWriter.INDEX)) {
                throw new IllegalStateException("Product search alias points to an unexpected index; explicit cutover required");
            }
        }
        elastic.indices().putAlias(request -> request.index(ProductIndexWriter.INDEX).name(ProductIndexWriter.ALIAS)
                .filter(query -> query.term(term -> term.field("deleted").value(false))));
    }

    private String resource(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public String state() { return state; }
}
