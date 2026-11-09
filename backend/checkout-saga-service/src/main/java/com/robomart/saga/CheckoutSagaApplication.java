package com.robomart.saga;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.robomart.saga", "com.robomart.security"})
@EnableScheduling
public class CheckoutSagaApplication {
    public static void main(String[] args) {
        SpringApplication.run(CheckoutSagaApplication.class, args);
    }
}
