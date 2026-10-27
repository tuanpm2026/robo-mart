package com.robomart.build;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Warms core Spring classes without importing application configuration or auto-configuration. */
public final class AotCacheTraining {

    private AotCacheTraining() {
    }

    public static void main(String[] args) {
        try (var context = start(args)) {
            // Closing the isolated context also exercises deterministic Spring cleanup.
        }
    }

    static ConfigurableApplicationContext start(String... args) {
        var application = new SpringApplication(TrainingConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setRegisterShutdownHook(false);
        application.setAdditionalProfiles("aot-training");
        return application.run(args);
    }

    // Deliberately no component scan, imports, auto-configuration, scheduling or application beans.
    @Profile("aot-training")
    @Configuration(proxyBeanMethods = false)
    static class TrainingConfiguration {
    }
}
