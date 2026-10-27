package com.robomart.product.config;

import java.time.Duration;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
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
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, error) -> new TopicPartition(record.topic() + ".DLT", -1));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(40));
        var backoff = new ExponentialBackOffWithMaxRetries(2);
        backoff.setInitialInterval(500);
        backoff.setMultiplier(2);
        backoff.setMaxInterval(2000);
        return new DefaultErrorHandler(recoverer, backoff);
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.kafka.listener", name = "auto-startup", havingValue = "true", matchIfMissing = true)
    public KafkaAdmin.NewTopics productDeadLetterTopics(
            @Value("${product.kafka.dlt-replication-factor:1}") short replicationFactor) {
        if (replicationFactor < 1) throw new IllegalArgumentException("DLT replication factor must be positive");
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name("product.product.created.DLT").partitions(3).replicas(replicationFactor).build(),
                TopicBuilder.name("product.product.updated.DLT").partitions(3).replicas(replicationFactor).build(),
                TopicBuilder.name("product.product.deleted.DLT").partitions(3).replicas(replicationFactor).build());
    }
}
