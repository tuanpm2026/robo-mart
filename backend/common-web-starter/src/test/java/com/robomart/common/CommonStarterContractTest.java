package com.robomart.common;

import com.robomart.common.audit.AuditAspect;
import com.robomart.common.audit.AuditAutoConfiguration;
import com.robomart.common.config.CommonWebAutoConfiguration;
import com.robomart.common.config.JacksonConfig;
import com.robomart.common.exception.GlobalExceptionHandler;
import com.robomart.common.exception.SecurityExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Compatibility entry point also runs in the existing CI library selection. No component scan. */
class CommonStarterContractTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonWebAutoConfiguration.class,
                    JacksonConfig.class, AuditAutoConfiguration.class));

    @Test
    void servletConsumersReceiveExactlyOneSharedBeanWithoutComponentScanningOrTracer() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(GlobalExceptionHandler.class)
                    .hasSingleBean(SecurityExceptionHandler.class).hasSingleBean(AuditAspect.class);
            assertThat(context).hasBean("jsonCustomizer");
            assertThat(context).doesNotHaveBean("dataSource").doesNotHaveBean("entityManagerFactory");
        });
    }

    @Test
    void optionalSecurityCanBeAbsentWithoutClassLoadingFailures() {
        runner.withClassLoader(new FilteredClassLoader("org.springframework.security"))
                .run(context -> assertThat(context).hasSingleBean(GlobalExceptionHandler.class)
                        .doesNotHaveBean(SecurityExceptionHandler.class).doesNotHaveBean(AuditAspect.class));
    }

    @Test
    void applicationAdviceOverridesTheDefault() {
        runner.withBean(GlobalExceptionHandler.class, () -> new GlobalExceptionHandler(io.micrometer.tracing.Tracer.NOOP))
                .run(context -> assertThat(context).hasSingleBean(GlobalExceptionHandler.class));
    }
}
