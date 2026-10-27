package com.robomart.inventory.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerExecutorProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;

/** Native gRPC does not inherit the servlet virtual-thread executor. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.threads.virtual.enabled", havingValue = "true")
public class GrpcExecutionConfig {

    @Bean(destroyMethod = "close")
    ExecutorService grpcVirtualExecutor() {
        var snapshots = ContextSnapshotFactory.builder().build();
        return ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor(), snapshots::captureAll);
    }

    @Bean
    GrpcServerExecutorProvider grpcServerExecutorProvider(
            @Qualifier("grpcVirtualExecutor") ExecutorService executor) {
        return () -> executor;
    }
}
