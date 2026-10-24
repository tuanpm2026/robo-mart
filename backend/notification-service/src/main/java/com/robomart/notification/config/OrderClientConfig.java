package com.robomart.notification.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;

/** Authenticates background Kafka consumers without relying on a user's HTTP security context. */
@Configuration(proxyBeanMethods = false)
public class OrderClientConfig {

    @Bean
    RestClient notificationOrderRestClient(RestClient.Builder builder,
            @Value("${notification.order-service.url}") String orderServiceUrl,
            @Value("${notification.service-auth.enabled:false}") boolean serviceAuthEnabled,
            ObjectProvider<OAuth2AuthorizedClientManager> managers) {
        builder.baseUrl(orderServiceUrl);
        if (serviceAuthEnabled) {
            OAuth2AuthorizedClientManager manager = managers.getObject();
            builder.requestInterceptor((request, body, execution) -> {
                var authorized = manager.authorize(OAuth2AuthorizeRequest
                        .withClientRegistrationId("notification")
                        .principal("notification-service")
                        .build());
                if (authorized == null) {
                    throw new IllegalStateException("Notification client credentials authorization failed");
                }
                request.getHeaders().setBearerAuth(authorized.getAccessToken().getTokenValue());
                return execution.execute(request, body);
            });
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "notification.service-auth", name = "enabled", havingValue = "true")
    OAuth2AuthorizedClientManager notificationAuthorizedClientManager(
            ClientRegistrationRepository registrations, OAuth2AuthorizedClientService clients) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }
}
