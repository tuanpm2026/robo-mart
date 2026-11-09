package com.robomart.payment.config;

import com.robomart.security.grpc.SagaRpcAuthInterceptor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Configuration(proxyBeanMethods = false)
public class SagaParticipantSecurityConfig {
    @Bean
    SagaRpcAuthInterceptor sagaRpcAuthInterceptor(
            JwtDecoder decoder,
            @Value("${spring.security.oauth2.resourceserver.jwt.audiences:robomart-api}")
                    String audience) {
        return new SagaRpcAuthInterceptor(decoder, audience);
    }
}
