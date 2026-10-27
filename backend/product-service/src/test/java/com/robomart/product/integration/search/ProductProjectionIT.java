package com.robomart.product.integration.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.Map;
import co.elastic.clients.json.JsonData;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import com.robomart.product.document.ProductDocument;
import com.robomart.product.dto.CreateProductRequest;
import com.robomart.product.dto.ProductSearchRequest;
import com.robomart.product.repository.ProductSearchRepository;
import com.robomart.product.service.ProductIndexLifecycle;
import com.robomart.product.service.ProductIndexWriter;
import com.robomart.product.service.ProductProjectionStore;
import com.robomart.product.service.ProductReindexService;
import com.robomart.product.service.ProductSearchService;
import com.robomart.product.service.ProductService;
import com.robomart.test.ProductIntegrationTest;
import com.robomart.test.security.TestJwt;
import com.robomart.test.security.TestJwtDecoderConfig;

@ProductIntegrationTest
@Import(TestJwtDecoderConfig.class)
class ProductProjectionIT {
    @Autowired private ProductService products;
    @Autowired private ProductProjectionStore source;
    @Autowired private ProductIndexWriter writer;
    @Autowired private ProductReindexService reindex;
    @Autowired private ProductSearchService searching;
    @Autowired private ProductSearchRepository search;
    @Autowired private ElasticsearchOperations operations;
    @Autowired private ElasticsearchClient elastic;
    @LocalServerPort private int port;

    private long create(String name) {
        return products.createProduct(new CreateProductRequest(name, null, 1L, new BigDecimal("19.95"),
                "ProjectionTest", UUID.randomUUID().toString())).id();
    }

    private RestClient client(String bearer) {
        return RestClient.builder().baseUrl("http://localhost:" + port).defaultHeader("Authorization", bearer)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { }).build();
    }

    @Test
    void indexUsesCheckedMappingAndAliasWithoutRemovingTheLegacyConcreteIndex() throws Exception {
        if (!elastic.indices().exists(request -> request.index("products")).value()) {
            elastic.indices().create(request -> request.index("products").mappings(mapping -> mapping
                    .properties("brand", property -> property.text(text -> text))));
        }
        new ProductIndexLifecycle(elastic, reindex).initialize();
        assertThat(elastic.indices().exists(request -> request.index("products")).value()).isTrue();
        var mappings = elastic.indices().getMapping(request -> request.index(ProductIndexWriter.INDEX))
                .get(ProductIndexWriter.INDEX).mappings();
        assertThat(mappings.properties().get("brand").isKeyword()).isTrue();
        assertThat(mappings.properties().get("price").scaledFloat().scalingFactor()).isEqualTo(100);
        assertThat(mappings.properties().get("name").text().analyzer()).isEqualTo("vi_fold");
        assertThat(elastic.indices().getAlias(request -> request.name(ProductIndexWriter.ALIAS)).aliases().keySet())
                .containsExactly(ProductIndexWriter.INDEX);
    }

    @Test
    void aRestartResumesThePersistedBootstrapCheckpoint() throws Exception {
        long id = create("Checkpoint product");
        elastic.indices().putMapping(request -> request.index(ProductIndexWriter.INDEX).meta(Map.of(
                "schema_version", JsonData.of(1), "bootstrap_complete", JsonData.of(false),
                "bootstrap_after_id", JsonData.of(id - 1))));
        var restarted = new ProductIndexLifecycle(elastic, reindex);
        restarted.initialize();
        assertThat(restarted.state()).isEqualTo("READY");
        assertThat(search.findById(id)).isPresent();
        var metadata = elastic.indices().getMapping(request -> request.index(ProductIndexWriter.INDEX))
                .get(ProductIndexWriter.INDEX).mappings().meta();
        assertThat(metadata.get("bootstrap_complete").to(Boolean.class)).isTrue();
        assertThat(metadata.get("bootstrap_after_id").to(Long.class)).isEqualTo(id);
    }

    @Test
    void vietnameseNamesMatchAccentlessQueriesAndScaledPricesKeepCents() {
        long id = create("Điện thoại thông minh");
        writer.write(source.load(id));
        operations.indexOps(ProductDocument.class).refresh();
        var request = new ProductSearchRequest("dien thoai", new BigDecimal("19.95"), new BigDecimal("19.95"), null, null, null);
        assertThat(searching.search(request, PageRequest.of(0, 100)).data())
                .anySatisfy(product -> {
                    assertThat(product.id()).isEqualTo(id);
                    assertThat(product.price()).isEqualByComparingTo("19.95");
                });
    }

    @Test
    void adminCanResumeAndRetryAReindexPageAndServiceIdentitiesCannotTriggerIt() {
        long id = create("Reindex source");
        String path = "/api/v1/admin/products/reindex?afterId=" + (id - 1) + "&size=1";
        var admin = client(TestJwt.adminBearer());
        var first = admin.post().uri(path).retrieve().toEntity(String.class);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        var result = JsonMapper.builder().build().readTree(first.getBody()).path("data");
        assertThat(result.path("processed").asInt()).isEqualTo(1);
        assertThat(result.path("nextAfterId").asLong()).isEqualTo(id);
        assertThat(search.findById(id)).isPresent();
        assertThat(admin.post().uri(path).retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(200);
        assertThat(admin.post().uri("/api/v1/admin/products/reindex?size=101").retrieve()
                .toBodilessEntity().getStatusCode().value()).isEqualTo(400);
        assertThat(client(TestJwt.customerBearer()).post().uri(path).retrieve().toBodilessEntity().getStatusCode().value())
                .isEqualTo(403);
        assertThat(client("Bearer " + TestJwt.NOTIFICATION_TOKEN).post().uri(path).retrieve()
                .toBodilessEntity().getStatusCode().value()).isEqualTo(403);
    }
}
