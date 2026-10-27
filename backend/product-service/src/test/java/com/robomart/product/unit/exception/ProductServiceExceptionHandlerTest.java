package com.robomart.product.unit.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.robomart.product.entity.Product;
import com.robomart.product.exception.ImageStorageException;
import com.robomart.product.exception.ProductServiceExceptionHandler;

class ProductServiceExceptionHandlerTest {
    @Test
    void reportsSkuRaceAsConflictAndUsesCurrentTraceWithoutLeakingSql() {
        var tracer = mock(Tracer.class);
        var span = mock(Span.class);
        var context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("actual-trace");
        var handler = new ProductServiceExceptionHandler(tracer);
        var cause = new org.hibernate.exception.ConstraintViolationException("private SQL", new SQLException("duplicate", "23505"), "products_sku_key");
        var response = handler.handleIntegrity(new DataIntegrityViolationException("SQL", cause));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().traceId()).isEqualTo("actual-trace");
        assertThat(response.getBody().error().message()).doesNotContain("SQL");
        assertThat(handler.handleImageStorage(new ImageStorageException("private path", cause)).getBody().traceId())
                .isEqualTo("actual-trace");
    }

    @Test
    void unrelatedIntegrityFailuresRemainServerErrorsAndOptimisticWritesAreConflicts() {
        var handler = new ProductServiceExceptionHandler(mock(Tracer.class));
        assertThat(handler.handleIntegrity(new DataIntegrityViolationException("foreign key")).getStatusCode().value())
                .isEqualTo(500);
        assertThat(handler.handleOptimisticConflict(new ObjectOptimisticLockingFailureException(Product.class, 1L))
                .getStatusCode().value()).isEqualTo(409);
    }
}
