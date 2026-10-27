package com.robomart.product.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.net.ConnectException;
import java.util.function.Function;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.util.ObjectBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.convert.ElasticsearchConverter;
import org.springframework.data.elasticsearch.core.document.Document;

import com.robomart.product.document.ProductDocument;
import com.robomart.product.service.ProductIndexWriter;

class ProductIndexWriterTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void zeroBasedSourceVersionUsesPositiveExternalVersionAndCannotAutoCreateADynamicIndex() throws Exception {
        var client = mock(ElasticsearchClient.class);
        var elastic = mock(ElasticsearchOperations.class);
        var converter = mock(ElasticsearchConverter.class);
        when(elastic.getElasticsearchConverter()).thenReturn(converter);
        when(converter.mapObject(any())).thenReturn(Document.create());
        var meters = new SimpleMeterRegistry();
        try {
            var writer = new ProductIndexWriter(client, elastic, meters);
            var document = new ProductDocument(); document.setId(42L); document.setSourceVersion(0L);
            writer.write(document);
            ArgumentCaptor<Function<IndexRequest.Builder<JsonData>, ObjectBuilder<IndexRequest<JsonData>>>> capture =
                    (ArgumentCaptor) ArgumentCaptor.forClass(Function.class);
            verify(client).index(capture.capture());
            var request = capture.getValue().apply(new IndexRequest.Builder<>()).build();
            assertThat(request.requireAlias()).isTrue();
            assertThat(request.index()).isEqualTo(ProductIndexWriter.ALIAS);
            assertThat(request.version()).isEqualTo(1);
            assertThat(request.versionType()).isEqualTo(VersionType.External);
        } finally { meters.close(); }
    }

    @Test
    @SuppressWarnings("unchecked")
    void transportFailuresPropagateAndCannotBeMistakenForAStaleAcknowledgement() throws Exception {
        var client = mock(ElasticsearchClient.class);
        var elastic = mock(ElasticsearchOperations.class);
        var converter = mock(ElasticsearchConverter.class);
        when(elastic.getElasticsearchConverter()).thenReturn(converter);
        when(converter.mapObject(any())).thenReturn(Document.create());
        when(client.index(any(Function.class))).thenThrow(new ConnectException("refused"));
        var meters = new SimpleMeterRegistry();
        try {
            var document = new ProductDocument(); document.setId(1L); document.setSourceVersion(0L);
            assertThatThrownBy(() -> new ProductIndexWriter(client, elastic, meters).write(document))
                    .isInstanceOf(DataAccessResourceFailureException.class).hasCauseInstanceOf(ConnectException.class);
        } finally { meters.close(); }
    }
}
