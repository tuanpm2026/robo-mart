package com.robomart.common.audit;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

@AutoConfiguration
@ComponentScan(basePackageClasses = AuditAutoConfiguration.class)
public class AuditAutoConfiguration {}
