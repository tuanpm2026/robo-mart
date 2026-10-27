package com.robomart.notification.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "notification.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {}
