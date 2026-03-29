package com.robomart.cart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
        scanBasePackages = "com.robomart",
        excludeName = {
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
                "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
        }
)
public class CartServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CartServiceApplication.class, args);
    }
}
