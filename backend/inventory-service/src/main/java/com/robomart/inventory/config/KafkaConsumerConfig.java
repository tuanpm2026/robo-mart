package com.robomart.inventory.config;

import java.time.Duration;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.admin.Admin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "robomart.inventory", name = "consumer-enabled", havingValue = "true", matchIfMissing = true)
public class KafkaConsumerConfig {
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

    @Bean(name = "kafka", destroyMethod = "close")
    public InventoryKafkaHealthIndicator kafkaHealth(KafkaAdmin admin) {
        return new InventoryKafkaHealthIndicator(Admin.create(admin.getConfigurationProperties()));
    }

    @Bean
    public KafkaAdmin.NewTopics inventoryLifecycleTopics() {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name("order.order.status-changed.DLT").partitions(3).replicas(1).build(),
                TopicBuilder.name("order.order.cancelled.DLT").partitions(3).replicas(1).build());
    }
}
