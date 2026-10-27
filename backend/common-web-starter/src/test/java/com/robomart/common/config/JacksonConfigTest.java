package com.robomart.common.config;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonConfigTest {

    private JsonMapper mapper;

    @BeforeEach
    void setUp() {
        var builder = JsonMapper.builder();
        new JacksonConfig().jsonCustomizer().customize(builder);
        mapper = builder.build();
    }

    @Test
    void serializesCamelCaseFieldNames() throws Exception {
        var bean = new SampleBean("John", "Doe");

        String json = mapper.writeValueAsString(bean);

        assertThat(json).contains("\"firstName\"");
        assertThat(json).contains("\"lastName\"");
    }

    @Test
    void excludesNullFieldsFromSerialization() throws Exception {
        var bean = new SampleBean("John", null);

        String json = mapper.writeValueAsString(bean);

        assertThat(json).contains("\"firstName\"");
        assertThat(json).doesNotContain("lastName");
    }

    @Test
    void serializesInstantAsIso8601String() throws Exception {
        var wrapper = new TimestampWrapper(Instant.parse("2025-06-15T10:30:00Z"));

        String json = mapper.writeValueAsString(wrapper);

        assertThat(json).contains("\"2025-06-15T10:30:00Z\"");
        assertThat(json).doesNotMatch(".*\"timestamp\"\\s*:\\s*\\d+.*");
    }

    static class SampleBean {
        public String firstName;
        public String lastName;

        SampleBean() {}

        SampleBean(String firstName, String lastName) {
            this.firstName = firstName;
            this.lastName = lastName;
        }
    }

    static class TimestampWrapper {
        public Instant timestamp;

        TimestampWrapper() {}

        TimestampWrapper(Instant timestamp) {
            this.timestamp = timestamp;
        }
    }
}
