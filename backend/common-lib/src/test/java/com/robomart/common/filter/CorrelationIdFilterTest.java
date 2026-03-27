package com.robomart.common.filter;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private CorrelationIdFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new CorrelationIdFilter();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @Test
    void whenCorrelationIdHeaderPresent_usesItAndSetsOnResponse() throws Exception {
        String existingId = "my-correlation-id-123";
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, existingId);

        filter.doFilterInternal(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isEqualTo(existingId);
    }

    @Test
    void whenCorrelationIdHeaderAbsent_generatesUuidAndSetsOnResponse() throws Exception {
        filter.doFilterInternal(request, response, new MockFilterChain());

        String headerValue = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(headerValue).isNotNull();
        assertThat(UUID.fromString(headerValue)).isNotNull();
    }

    @Test
    void whenCorrelationIdExceedsMaxLength_generatesNewUuid() throws Exception {
        String oversizedId = "x".repeat(200);
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, oversizedId);

        filter.doFilterInternal(request, response, new MockFilterChain());

        String headerValue = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(headerValue).isNotEqualTo(oversizedId);
        assertThat(UUID.fromString(headerValue)).isNotNull();
    }

    @Test
    void mdcIsSetDuringFilterChainExecution_andCleanedUpAfter() throws Exception {
        String existingId = "trace-abc";
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, existingId);

        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        FilterChain capturingChain = (ServletRequest req, ServletResponse res) ->
                mdcDuringChain.set(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY));

        filter.doFilterInternal(request, response, capturingChain);

        assertThat(mdcDuringChain.get()).isEqualTo(existingId);
        assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY)).isNull();
    }
}
