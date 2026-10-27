package com.robomart.notification.config;

import java.util.Map;

import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import com.robomart.notification.kafka.RawRecordRecoverer;

@Configuration(proxyBeanMethods = false)
public class KafkaDlqConfig {
    static final String DLQ_TOPIC = "notification.dlq";

    @Bean
    public org.apache.kafka.clients.admin.NewTopic notificationDlqTopic() {
        return TopicBuilder.name(DLQ_TOPIC).partitions(1).replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, "604800000").build();
    }

    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(KafkaTemplate<byte[], byte[]> template) {
        return new RawRecordRecoverer(template);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> dlqListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumers) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumers);
        // Terminal persistence failures retry indefinitely; there is no second replay/DLT path.
        var retry = new ExponentialBackOff(1000, 2);
        retry.setMaxInterval(30000);
        var handler = new DefaultErrorHandler(retry);
        // Even a conversion/programming defect must not acknowledge an unarchived terminal record.
        handler.setClassifications(Map.of(), true);
        factory.setCommonErrorHandler(handler);
        factory.getContainerProperties().setShutdownTimeout(20000);
        return factory;
    }
}
