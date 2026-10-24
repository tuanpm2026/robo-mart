package com.robomart.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.robomart.notification.client.OrderServiceClient;

class OrderClientConfigTest {

    @Test
    void shouldAuthenticateBackgroundOrderLookupWithMachineIdentity() {
        var manager = mock(OAuth2AuthorizedClientManager.class);
        var registration = ClientRegistration.withRegistrationId("notification")
                .clientId("notification-service").clientSecret("test-only")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri("http://keycloak/token").build();
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "machine-token", Instant.now(), Instant.now().plusSeconds(300));
        when(manager.authorize(any())).thenReturn(
                new OAuth2AuthorizedClient(registration, "notification-service", token));
        var beans = new StaticListableBeanFactory();
        beans.addBean("manager", manager);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = new OrderClientConfig().notificationOrderRestClient(
                builder, "http://orders", true, beans.getBeanProvider(OAuth2AuthorizedClientManager.class));
        server.expect(requestTo("http://orders/api/v1/admin/orders/42"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer machine-token"))
                .andRespond(withSuccess("{\"data\":{\"id\":42,\"userId\":\"customer\",\"items\":[]}}",
                        MediaType.APPLICATION_JSON));

        assertThat(new OrderServiceClient(client).getOrderDetail("42").id()).isEqualTo(42L);
        ArgumentCaptor<OAuth2AuthorizeRequest> request = ArgumentCaptor.forClass(OAuth2AuthorizeRequest.class);
        verify(manager).authorize(request.capture());
        assertThat(request.getValue().getPrincipal().getName()).isEqualTo("notification-service");
        assertThat(request.getValue().getClientRegistrationId()).isEqualTo("notification");
        server.verify();
    }

    @Test
    void shouldPropagateAuthenticationFailuresForKafkaRetryInsteadOfDroppingNotification() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://orders");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://orders/api/v1/admin/orders/42"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        OrderServiceClient client = new OrderServiceClient(builder.build());

        assertThatThrownBy(() -> client.getOrderDetail("42")).isInstanceOf(HttpClientErrorException.Unauthorized.class);
        server.verify();
    }

    @Test
    void shouldStillSkipMissingOrders() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://orders");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://orders/api/v1/admin/orders/42"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(new OrderServiceClient(builder.build()).getOrderDetail("42")).isNull();
        server.verify();
    }
}
