package com.robomart.product.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.robomart.common.exception.ValidationException;
import com.robomart.product.document.ProductDocument;
import com.robomart.product.entity.Category;
import com.robomart.product.entity.Product;
import com.robomart.product.repository.ProductRepository;
import com.robomart.product.service.ProductIndexWriter;
import com.robomart.product.service.ProductProjectionStore;
import com.robomart.product.service.ProductReindexService;

class ProductProjectionTest {
    @Test
    void authoritativeSnapshotIncludesDeletionAndSourceVersion() {
        var repository = mock(ProductRepository.class);
        var product = new Product();
        product.setCategory(new Category());
        product.setName("Current database name");
        product.setActive(false);
        ReflectionTestUtils.setField(product, "id", 42L);
        ReflectionTestUtils.setField(product, "version", 7);
        when(repository.findProjectionById(42L)).thenReturn(Optional.of(product));
        var result = new ProductProjectionStore(repository).load(42);
        assertThat(result.getName()).isEqualTo("Current database name");
        assertThat(result.isDeleted()).isTrue();
        assertThat(result.getSourceVersion()).isEqualTo(7);
    }

    @Test
    void reindexPagesReturnAnExplicitContinuationAndNeverWritePastTheLimit() {
        var store = mock(ProductProjectionStore.class);
        var writer = mock(ProductIndexWriter.class);
        var first = document(10); var second = document(20); var next = document(30);
        when(store.page(0, 3)).thenReturn(List.of(first, second, next));
        var result = new ProductReindexService(store, writer).page(0, 2);
        assertThat(result.processed()).isEqualTo(2);
        assertThat(result.nextAfterId()).isEqualTo(20);
        assertThat(result.hasNext()).isTrue();
        verify(writer).write(first); verify(writer).write(second); verify(writer, never()).write(next);
    }

    @Test
    void failedPageCannotClaimCompletionAndInvalidLimitsCannotReadTheDatabase() {
        var store = mock(ProductProjectionStore.class);
        var writer = mock(ProductIndexWriter.class);
        var reindex = new ProductReindexService(store, writer);
        assertThatThrownBy(() -> reindex.page(0, 101)).isInstanceOf(ValidationException.class);
        verifyNoInteractions(store);
        var row = document(1);
        when(store.page(0, 2)).thenReturn(List.of(row));
        doThrow(new IllegalStateException("Index unavailable")).when(writer).write(row);
        assertThatThrownBy(() -> reindex.page(0, 1)).isInstanceOf(IllegalStateException.class);
    }

    private ProductDocument document(long id) {
        var document = new ProductDocument(); document.setId(id); document.setSourceVersion(0L); return document;
    }
}
