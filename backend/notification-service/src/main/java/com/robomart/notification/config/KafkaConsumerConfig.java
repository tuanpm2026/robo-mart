package com.robomart.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.config.ContainerCustomizer;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration(proxyBeanMethods = false)
public class KafkaConsumerConfig {
    @Bean
    public ContainerCustomizer<Object, Object, ConcurrentMessageListenerContainer<Object, Object>> listenerSettings() {
        return container -> {
            container.getContainerProperties().setShutdownTimeout(20000);
            container.getContainerProperties().setDeliveryAttemptHeader(true);
        };
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer recoverer) {
        var retry = new ExponentialBackOffWithMaxRetries(2);
        retry.setInitialInterval(500);
        retry.setMultiplier(2);
        retry.setMaxInterval(2000);
        return new DefaultErrorHandler(recoverer, retry);
    }
}
