package com.robomart.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.RetryAnnotationBeanPostProcessor;

@Configuration(proxyBeanMethods = false)
public class GrpcResilienceConfig {
    @Bean
    static RetryAnnotationBeanPostProcessor grpcRetryProcessor() {
        var processor = new RetryAnnotationBeanPostProcessor();
        processor.setProxyTargetClass(true);
        // Spring retry defaults to insertion outside existing advice. Append it inside the
        // circuit breaker so fallbacks see only the final attempt and OPEN never retries.
        processor.setBeforeExistingAdvisors(false);
        return processor;
    }
}
