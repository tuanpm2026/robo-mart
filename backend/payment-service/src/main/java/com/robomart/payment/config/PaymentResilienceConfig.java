package com.robomart.payment.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

@Configuration(proxyBeanMethods = false)
@EnableResilientMethods(proxyTargetClass = true)
public class PaymentResilienceConfig {
}
