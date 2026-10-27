package com.robomart.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Import;

/** Integration context with only the infrastructure used by inventory service. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@IntegrationTest
@Import({PostgresContainerConfig.class, KafkaContainerConfig.class})
public @interface InventoryIntegrationTest {
}
