package com.robomart.notification.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import com.robomart.notification.kafka.RawRecordRecoverer;

@Configuration
public class KafkaDlqConfig {
    static final String DLQ_TOPIC = "notification.dlq";
    @Value("${spring.kafka.bootstrap-servers}") private String bootstrap;

    @Value("${spring.kafka.listener.auto-startup:true}") private boolean autoStartup;

    @Bean
    public org.apache.kafka.clients.admin.NewTopic notificationDlqTopic() {
        return TopicBuilder.name(DLQ_TOPIC).partitions(1).replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, "604800000").build();
    }

    @Bean
    public KafkaAdmin notificationKafkaAdmin() {
        KafkaAdmin admin = new KafkaAdmin(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap));
        admin.setOperationTimeout(5);
        admin.setAutoCreate(autoStartup);
        return admin;
    }

    @Bean
    public ProducerFactory<byte[], byte[]> rawRecordProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30000);
        props.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG,
                "com.robomart.common.kafka.CorrelationIdKafkaProducerInterceptor");
        var factory = new DefaultKafkaProducerFactory<byte[], byte[]>(props);
        factory.setPhysicalCloseTimeout(2);
        return factory;
    }

    @Bean
    public KafkaTemplate<byte[], byte[]> rawRecordKafkaTemplate(
            @Qualifier("rawRecordProducerFactory") ProducerFactory<byte[], byte[]> factory) {
        var template = new KafkaTemplate<>(factory);
        template.setObservationEnabled(true);
        return template;
    }

    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
            @Qualifier("rawRecordKafkaTemplate") KafkaTemplate<byte[], byte[]> template) {
        return new RawRecordRecoverer(template);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<byte[], byte[]> dlqListenerContainerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        var factory = new ConcurrentKafkaListenerContainerFactory<byte[], byte[]>();
        factory.setAutoStartup(autoStartup);
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
        // This is the terminal archive: failed persistence must keep retrying rather than skip the record.
        var retry = new ExponentialBackOff(1000, 2);
        retry.setMaxInterval(30000);
        factory.setCommonErrorHandler(new DefaultErrorHandler(retry));
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }
}
