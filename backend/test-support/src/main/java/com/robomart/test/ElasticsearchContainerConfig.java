package com.robomart.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

@TestConfiguration(proxyBeanMethods = false)
public class ElasticsearchContainerConfig {

    private static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:9.4.8")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("discovery.type", "single-node");

    static {
        ELASTICSEARCH.start();
    }

    @Bean
    DynamicPropertyRegistrar elasticsearchProperties() {
        return registry -> {
            registry.add("spring.elasticsearch.uris", ELASTICSEARCH::getHttpHostAddress);
        };
    }
}
