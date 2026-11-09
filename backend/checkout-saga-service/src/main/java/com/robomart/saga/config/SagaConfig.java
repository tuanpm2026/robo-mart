package com.robomart.saga.config;

import com.robomart.security.converter.KeycloakRealmRoleConverter;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class SagaConfig {
    @Bean
    public NewTopic sagaRequests() {
        return TopicBuilder.name("order.checkout.requests.v1").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic sagaDeadLetters() {
        return TopicBuilder.name("order.checkout.requests.v1.DLT")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public DefaultErrorHandler sagaErrorHandler(KafkaTemplate<Object, Object> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template);
        recoverer.setFailIfSendResultIsError(true);
        var handler =
                new DefaultErrorHandler(
                        recoverer, new FixedBackOff(2000, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.addNotRetryableExceptions(
                IllegalArgumentException.class, tools.jackson.core.JacksonException.class);
        return handler;
    }

    @Bean
    public SecurityFilterChain security(HttpSecurity http, KeycloakRealmRoleConverter roles)
            throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers("/actuator/**")
                                        .permitAll()
                                        .anyRequest()
                                        .hasRole("ADMIN"))
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
                .build();
    }
}
