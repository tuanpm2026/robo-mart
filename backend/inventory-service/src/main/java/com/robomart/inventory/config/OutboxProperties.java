package com.robomart.inventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.robomart.outbox.OutboxSettings;

@Component
@ConfigurationProperties(prefix = "outbox")
public class OutboxProperties extends OutboxSettings { }
