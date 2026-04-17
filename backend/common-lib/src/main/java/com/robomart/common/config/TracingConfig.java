package com.robomart.common.config;

import io.micrometer.core.instrument.binder.grpc.ObservationGrpcClientInterceptor;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcServerInterceptor;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GlobalClientInterceptor;
import org.springframework.grpc.server.GlobalServerInterceptor;

@Configuration
public class TracingConfig {

    @Bean
    @GlobalClientInterceptor
    public ObservationGrpcClientInterceptor grpcTracingClientInterceptor(ObservationRegistry registry) {
        return new ObservationGrpcClientInterceptor(registry);
    }

    @Bean
    @GlobalServerInterceptor
    public ObservationGrpcServerInterceptor grpcTracingServerInterceptor(ObservationRegistry registry) {
        return new ObservationGrpcServerInterceptor(registry);
    }
}
