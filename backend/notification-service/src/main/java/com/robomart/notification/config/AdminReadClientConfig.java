package com.robomart.notification.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.ImportHttpServices;

import com.robomart.notification.client.InventoryAdminReadClient;
import com.robomart.notification.client.OrderAdminReadClient;
import com.robomart.notification.client.PaymentAdminReadClient;
import com.robomart.notification.client.ProductAdminReadClient;

@Configuration(proxyBeanMethods = false)
public class AdminReadClientConfig {
    @Bean
    RestClientHttpServiceGroupConfigurer notificationReadAuthentication(
            @Value("${notification.service-auth.enabled:true}") boolean enabled,
            @Qualifier("notificationAuthorizedClientManager") ObjectProvider<OAuth2AuthorizedClientManager> managers) {
        return groups -> groups.filterByName("notification-order", "notification-inventory",
                "notification-payment", "notification-product").forEachClient((group, builder) -> {
                    if (enabled) builder.requestInterceptor(OrderClientConfig.authentication(managers.getObject()));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @ImportHttpServices(group = "notification-order", types = OrderAdminReadClient.class)
    static class OrderReads { }

    @Configuration(proxyBeanMethods = false)
    @ImportHttpServices(group = "notification-inventory", types = InventoryAdminReadClient.class)
    static class InventoryReads { }

    @Configuration(proxyBeanMethods = false)
    @ImportHttpServices(group = "notification-payment", types = PaymentAdminReadClient.class)
    static class PaymentReads { }

    @Configuration(proxyBeanMethods = false)
    @ImportHttpServices(group = "notification-product", types = ProductAdminReadClient.class)
    static class ProductReads { }
}
