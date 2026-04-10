package com.robomart.common.config;

import io.micrometer.tracing.Tracer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnMissingBean(Tracer.class)
public class TracerConfig {

    @Bean
    public Tracer noopTracer() {
        return Tracer.NOOP;
    }
}
