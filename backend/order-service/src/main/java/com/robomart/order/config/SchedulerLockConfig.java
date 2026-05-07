package com.robomart.order.config;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Distributed scheduler lock so DeadSagaDetectionJob runs on at most one pod
 * per check interval. Without this, multi-pod deployments would have every
 * instance pick up the same stuck orders and run compensation gRPC calls
 * concurrently (double refund / double inventory release risk).
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build()
        );
    }
}
