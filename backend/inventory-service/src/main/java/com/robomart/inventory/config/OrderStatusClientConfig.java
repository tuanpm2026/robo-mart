package com.robomart.inventory.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ClientCredentialsOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.service.registry.HttpServiceGroupConfigurer;
import org.springframework.web.service.registry.ImportHttpServices;

import com.robomart.inventory.client.OrderStatusClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "robomart.inventory", name = "expiry-enabled", havingValue = "true", matchIfMissing = true)
@ImportHttpServices(group = "inventory-order-status", types = OrderStatusClient.class)
public class OrderStatusClientConfig {
    @Bean
    HttpServiceGroupConfigurer<RestClient.Builder> orderStatusAuthentication(
            @Value("${INVENTORY_SERVICE_CLIENT_SECRET:unconfigured}") String secret,
            @Value("${INVENTORY_SERVICE_TOKEN_URI:http://localhost:8180/realms/robomart/protocol/openid-connect/token}")
                    String tokenUri) {
        var registration = ClientRegistration.withRegistrationId("inventory")
                .clientId("inventory-service").clientSecret(secret.isBlank() ? "unconfigured" : secret)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).tokenUri(tokenUri).build();
        var registrations = new InMemoryClientRegistrationRepository(registration);
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations,
                new InMemoryOAuth2AuthorizedClientService(registrations));
        var tokenFactory = new SimpleClientHttpRequestFactory();
        tokenFactory.setConnectTimeout(Duration.ofSeconds(1));
        tokenFactory.setReadTimeout(Duration.ofSeconds(2));
        var tokenClient = new RestClientClientCredentialsTokenResponseClient();
        tokenClient.setRestClient(RestClient.builder().requestFactory(tokenFactory)
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new FormHttpMessageConverter());
                    converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                }).defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build());
        var provider = new ClientCredentialsOAuth2AuthorizedClientProvider();
        provider.setAccessTokenResponseClient(tokenClient);
        manager.setAuthorizedClientProvider(provider);
        return groups -> groups.filterByName("inventory-order-status").forEachClient((group, builder) ->
                builder.requestInterceptor((request, body, execution) -> {
                    var client = manager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId("inventory")
                            .principal("inventory-service").build());
                    if (client == null) {
                        throw new IllegalStateException("Inventory service credentials authorization failed");
                    }
                    request.getHeaders().setBearerAuth(client.getAccessToken().getTokenValue());
                    return execution.execute(request, body);
                }));
    }
}
