package com.robomart.notification.config;

import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ClientCredentialsOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

/** Background clients share a cached machine identity and bounded service/token requests. */
@Configuration(proxyBeanMethods = false)
public class OrderClientConfig {
    @Bean
    RestClient notificationOrderRestClient(RestClient.Builder builder,
            @Value("${notification.order-service.url}") String orderServiceUrl,
            @Value("${notification.service-auth.enabled:true}") boolean serviceAuthEnabled,
            @Qualifier("notificationAuthorizedClientManager") ObjectProvider<OAuth2AuthorizedClientManager> managers) {
        builder.baseUrl(orderServiceUrl);
        if (serviceAuthEnabled) builder.requestInterceptor(authentication(managers.getObject()));
        return builder.build();
    }

    static ClientHttpRequestInterceptor authentication(OAuth2AuthorizedClientManager manager) {
        return (request, body, execution) -> {
            var authorized = manager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId("notification")
                    .principal("notification-service").build());
            if (authorized == null) throw new IllegalStateException("Notification client credentials authorization failed");
            request.getHeaders().setBearerAuth(authorized.getAccessToken().getTokenValue());
            return execution.execute(request, body);
        };
    }

    @Bean
    RestClientCustomizer notificationRequestTimeouts() {
        return builder -> builder.requestFactory(requestFactory());
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(2));
        return factory;
    }

    @Bean
    @ConditionalOnProperty(prefix = "notification.service-auth", name = "enabled", havingValue = "true", matchIfMissing = true)
    OAuth2AuthorizedClientManager notificationAuthorizedClientManager(
            @Value("${spring.security.oauth2.client.registration.notification.client-id:notification-service}") String clientId,
            @Value("${spring.security.oauth2.client.registration.notification.client-secret:${NOTIFICATION_SERVICE_CLIENT_SECRET:unconfigured}}")
                    String secret,
            @Value("${spring.security.oauth2.client.provider.keycloak.token-uri:${NOTIFICATION_SERVICE_TOKEN_URI:http://localhost:8180/realms/robomart/protocol/openid-connect/token}}")
                    String tokenUri) {
        var registration = ClientRegistration.withRegistrationId("notification").clientId(clientId)
                .clientSecret(secret.isBlank() ? "unconfigured" : secret)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).tokenUri(tokenUri).build();
        var registrations = new InMemoryClientRegistrationRepository(registration);
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations,
                new InMemoryOAuth2AuthorizedClientService(registrations));
        var tokenClient = new RestClientClientCredentialsTokenResponseClient();
        tokenClient.setRestClient(RestClient.builder().requestFactory(requestFactory()).messageConverters(converters -> {
            converters.clear();
            converters.add(new FormHttpMessageConverter());
            converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
        }).defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build());
        var provider = new ClientCredentialsOAuth2AuthorizedClientProvider();
        provider.setAccessTokenResponseClient(tokenClient);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }
}
