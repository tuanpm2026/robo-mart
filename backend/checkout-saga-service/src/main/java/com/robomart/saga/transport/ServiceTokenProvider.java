package com.robomart.saga.transport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Component
public class ServiceTokenProvider {
    private final RestClient client;
    private final String tokenUrl;
    private final String secret;
    private String token = "";
    private Instant expires = Instant.EPOCH;

    public ServiceTokenProvider(
            @Value("${saga.identity.token-url}") String tokenUrl,
            @Value("${saga.identity.client-secret}") String secret) {
        var factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = RestClient.builder().requestFactory(factory).build();
        this.tokenUrl = tokenUrl;
        this.secret = secret;
    }

    public synchronized String get() {
        if (expires.isAfter(Instant.now().plusSeconds(15))) return token;
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", "checkout-saga-service");
        form.add("client_secret", secret);
        Map<?, ?> response =
                client.post()
                        .uri(tokenUrl)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(Map.class);
        if (response == null || !(response.get("access_token") instanceof String value))
            throw new IllegalStateException("Missing service token");
        token = value;
        expires = Instant.now().plusSeconds(((Number) response.get("expires_in")).longValue());
        return token;
    }
}
