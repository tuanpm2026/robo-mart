package com.robomart.payment.config;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariDataSource;

/** Separate provider resources keep settlement independent without starving the application's pool. */
@Configuration(proxyBeanMethods = false)
public class MockGatewayDataSourceConfig {

    @Bean(name = "mockGatewayDataSource", defaultCandidate = false, destroyMethod = "close")
    HikariDataSource mockGatewayDataSource(JdbcConnectionDetails connectionDetails) {
        HikariDataSource provider = new HikariDataSource();
        provider.setJdbcUrl(connectionDetails.getJdbcUrl());
        provider.setUsername(connectionDetails.getUsername());
        provider.setPassword(connectionDetails.getPassword());
        provider.setDriverClassName(connectionDetails.getDriverClassName());
        provider.setPoolName("mock-payment-provider");
        provider.setMaximumPoolSize(2);
        provider.setMinimumIdle(0);
        provider.setConnectionTimeout(1000);
        return provider;
    }
}
