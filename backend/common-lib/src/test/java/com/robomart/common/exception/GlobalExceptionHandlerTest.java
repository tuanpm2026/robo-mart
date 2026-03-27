package com.robomart.common.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;

import com.robomart.common.dto.ApiErrorResponse;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private Tracer tracer;
    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        tracer = mock(Tracer.class);
        handler = new GlobalExceptionHandler(tracer);

        var span = mock(Span.class);
        var traceContext = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn("abc123");
    }

    @Test
    void handleResourceNotFound_returns404WithCorrectErrorCode() {
        var ex = new ResourceNotFoundException("Order", 99);

        var response = handler.handleResourceNotFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ApiErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.error().code()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(body.error().message()).isEqualTo("Order not found with id: 99");
        assertThat(body.traceId()).isEqualTo("abc123");
    }

    @Test
    void handleBusinessRule_returns409() {
        var ex = new BusinessRuleException("Cannot modify locked order");

        var response = handler.handleBusinessRule(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("BUSINESS_RULE_VIOLATION");
    }

    @Test
    void handleValidation_returns400() {
        var ex = new ValidationException("Name must not be blank");

        var response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void handleExternalService_returns503() {
        var ex = new ExternalServiceException("Inventory service down");

        var response = handler.handleExternalService(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("EXTERNAL_SERVICE_ERROR");
    }

    @Test
    void handleMethodArgumentNotValid_returns400WithFieldErrors() throws Exception {
        var bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "name", "must not be blank"));
        var methodParam = new MethodParameter(
                getClass().getDeclaredMethod("handleMethodArgumentNotValid_returns400WithFieldErrors"), -1);
        var ex = new MethodArgumentNotValidException(methodParam, bindingResult);

        var response = handler.handleMethodArgumentNotValid(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().error().details()).containsEntry("name", "must not be blank");
    }

    @Test
    void handleMethodNotSupported_returns405() {
        var ex = new HttpRequestMethodNotSupportedException("DELETE");

        var response = handler.handleMethodNotSupported(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void handleUnexpected_returns500WithGenericMessage() {
        var ex = new NullPointerException("secret internal detail");

        var response = handler.handleUnexpected(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ApiErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.error().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.error().message()).isEqualTo("An unexpected error occurred");
        assertThat(body.error().message()).doesNotContain("secret internal detail");
    }

    @Test
    void traceId_extractedFromSpan() {
        var ex = new ValidationException("bad input");

        var response = handler.handleValidation(ex);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().traceId()).isEqualTo("abc123");
    }

    @Test
    void traceId_fallsBackToNoTrace_whenNoCurrentSpan() {
        when(tracer.currentSpan()).thenReturn(null);

        var ex = new ValidationException("bad input");
        var response = handler.handleValidation(ex);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().traceId()).isEqualTo("no-trace");
    }

    @Test
    void traceId_fallsBackToNoTrace_whenSpanContextIsNull() {
        var span = mock(Span.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(null);

        var ex = new ValidationException("bad input");
        var response = handler.handleValidation(ex);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().traceId()).isEqualTo("no-trace");
    }
}
