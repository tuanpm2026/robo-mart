package com.robomart.common.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;

import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.micrometer.tracing.Tracer;

class ProblemDetailMvcTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Requests())
                .setControllerAdvice(new GlobalExceptionHandler(mock(Tracer.class))).build();
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of(post("/payload").contentType(MediaType.APPLICATION_JSON).content("{invalid"), 400),
                Arguments.of(get("/header"), 400),
                Arguments.of(get("/number").param("number", "not-a-number"), 400),
                Arguments.of(post("/payload").contentType(MediaType.TEXT_PLAIN).content("payload"), 415),
                Arguments.of(get("/denied"), 403));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void frameworkFailuresKeepTheirStatusAndLegacyEnvelope(RequestBuilder request, int expected) throws Exception {
        mvc.perform(request).andExpect(status().is(expected))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(expected))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andExpect(jsonPath("$.traceId").value("no-trace"))
                .andExpect(jsonPath("$.error.code").isNotEmpty())
                .andExpect(jsonPath("$.error.message").isNotEmpty());
    }

    @Test
    void methodNotAllowedPreservesFrameworkAllowHeader() throws Exception {
        mvc.perform(delete("/number")).andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @RestController
    static class Requests {
        @PostMapping(value = "/payload", consumes = MediaType.APPLICATION_JSON_VALUE)
        public Payload payload(@RequestBody Payload value) {
            return value;
        }

        @GetMapping("/header")
        public String header(@RequestHeader("X-Required") String value) {
            return value;
        }

        @GetMapping("/number")
        public int number(@RequestParam("number") int value) {
            return value;
        }

        @GetMapping("/denied")
        public String denied() {
            throw new AccessDeniedException("sensitive internal authorization detail");
        }
    }

    record Payload(int number) { }
}
