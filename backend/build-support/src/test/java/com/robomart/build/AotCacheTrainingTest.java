package com.robomart.build;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AotCacheTrainingTest {

    @Test
    void trainingCannotDiscoverApplicationBeansOrAutoConfiguration() {
        try (var context = AotCacheTraining.start("--spring.config.location=optional:classpath:/absent/")) {
            assertThat(context.getEnvironment().getActiveProfiles()).contains("aot-training");
            assertThat(context.getBeanFactory().getBeanDefinitionNames())
                    .allMatch(name -> name.startsWith("org.springframework.context.")
                            || name.equals("aotCacheTraining.TrainingConfiguration")
                            || name.equals("org.springframework.boot.autoconfigure.internalCachingMetadataReaderFactory"));
            assertThat(context.getBeanDefinitionCount()).isLessThan(10);
        }
        AotCacheTraining.main(new String[]{"--spring.config.location=optional:classpath:/absent/"});
    }
}
