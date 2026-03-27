package com.robomart.common.config;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.PropertyNamingStrategies;

@Configuration
public class JacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer jsonCustomizer() {
        return builder -> builder
                .propertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
                .changeDefaultPropertyInclusion(v ->
                        v.withValueInclusion(JsonInclude.Include.NON_NULL));
    }
}
