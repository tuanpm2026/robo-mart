package com.robomart.common.config;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import tools.jackson.databind.json.JsonMapper;

import tools.jackson.databind.PropertyNamingStrategies;

@AutoConfiguration
@ConditionalOnClass(JsonMapper.class)
public class JacksonConfig {

    @Bean
    @ConditionalOnMissingBean(name = "jsonCustomizer")
    public JsonMapperBuilderCustomizer jsonCustomizer() {
        return builder -> builder
                .propertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
                .changeDefaultPropertyInclusion(v ->
                        v.withValueInclusion(JsonInclude.Include.NON_NULL));
    }
}
