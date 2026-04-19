package com.robomart.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

@TestConfiguration(proxyBeanMethods = false)
public class ElasticsearchContainerConfig {

    private static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:9.1.2")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("discovery.type", "single-node")
                    .withReuse(true);

    static {
        ELASTICSEARCH.start();
    }

    @Bean
    public ElasticsearchContainer elasticsearchContainer() {
        return ELASTICSEARCH;
    }

    @Bean
    DynamicPropertyRegistrar elasticsearchProperties(ElasticsearchContainer es) {
        return registry -> {
            registry.add("spring.elasticsearch.uris", es::getHttpHostAddress);
        };
    }
}
