package com.robomart.product.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.net.ConnectException;
import java.util.Map;
import java.util.function.Function;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.GetAliasResponse;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import org.junit.jupiter.api.Test;

import com.robomart.product.config.ProductSearchReadiness;
import com.robomart.product.service.ProductIndexLifecycle;
import com.robomart.product.service.ProductIndexWriter;
import com.robomart.product.service.ProductReindexService;

class ProductIndexLifecycleTest {
    @Test
    @SuppressWarnings("unchecked")
    void connectionFailurePreservesThePostgresTierAndCanRetryInitialization() throws Exception {
        var client = mock(ElasticsearchClient.class);
        var indices = mock(ElasticsearchIndicesClient.class);
        when(client.indices()).thenReturn(indices);
        when(indices.exists(any(Function.class))).thenThrow(new ConnectException("refused"));
        var lifecycle = new ProductIndexLifecycle(client, mock(ProductReindexService.class));
        lifecycle.initialize();
        assertThat(lifecycle.state()).isEqualTo("POSTGRES_FALLBACK");
        assertThat(new ProductSearchReadiness(lifecycle).health().getStatus().getCode()).isEqualTo("UP");
        lifecycle.initialize();
        verify(indices, times(2)).exists(any(Function.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void incompatibleExistingMappingFailsClosedWithoutDeletingAnIndex() throws Exception {
        var client = mock(ElasticsearchClient.class);
        var indices = mock(ElasticsearchIndicesClient.class);
        var reindex = mock(ProductReindexService.class);
        when(client.indices()).thenReturn(indices);
        when(indices.exists(any(Function.class))).thenReturn(new BooleanResponse(true));
        when(indices.existsAlias(any(Function.class))).thenReturn(new BooleanResponse(true));
        when(indices.getAlias(any(Function.class))).thenReturn(GetAliasResponse.of(response -> response.aliases(
                ProductIndexWriter.INDEX, value -> value.aliases(Map.of()))));
        when(indices.getMapping(any(Function.class))).thenReturn(GetMappingResponse.of(response -> response.mappings(
                ProductIndexWriter.INDEX, value -> value.mappings(mapping -> mapping.properties("brand", field -> field.text(text -> text))))));
        var lifecycle = new ProductIndexLifecycle(client, reindex);
        assertThatThrownBy(lifecycle::initialize).isInstanceOf(IllegalStateException.class);
        assertThat(lifecycle.state()).isEqualTo("ERROR");
        assertThat(new ProductSearchReadiness(lifecycle).health().getStatus().getCode()).isEqualTo("OUT_OF_SERVICE");
        verifyNoInteractions(reindex);
        verify(indices, never()).delete(any(Function.class));
    }
}
