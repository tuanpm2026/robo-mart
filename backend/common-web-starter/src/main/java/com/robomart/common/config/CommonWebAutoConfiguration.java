package com.robomart.common.config;

import com.robomart.common.exception.GlobalExceptionHandler;
import com.robomart.common.exception.SecurityExceptionHandler;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler")
public class CommonWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    GlobalExceptionHandler globalExceptionHandler(ObjectProvider<Tracer> tracer) {
        return new GlobalExceptionHandler(tracer.getIfAvailable(() -> Tracer.NOOP));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.access.AccessDeniedException")
    static class SecurityAdviceConfiguration {
        @Bean
        @ConditionalOnMissingBean
        SecurityExceptionHandler securityExceptionHandler(ObjectProvider<Tracer> tracer) {
            return new SecurityExceptionHandler(tracer.getIfAvailable(() -> Tracer.NOOP));
        }
    }
}
