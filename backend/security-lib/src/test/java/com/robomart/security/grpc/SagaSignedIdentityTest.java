package com.robomart.security.grpc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import io.grpc.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

class SagaSignedIdentityTest {
    @Test
    void realSignedIdentityRejectsWrongIssuerExpirySignatureAndCustomerRole() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair trusted = generator.generateKeyPair();
        var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) trusted.getPublic()).build();
        decoder.setJwtValidator(
                JwtValidators.createDefaultWithIssuer("https://issuer/realms/robomart"));
        var interceptor = new SagaRpcAuthInterceptor(decoder, "robomart-api");
        ServerCall<Object, Object> call = mock(ServerCall.class);
        ServerCallHandler<Object, Object> next = mock(ServerCallHandler.class);
        for (String token :
                List.of(
                        token(
                                trusted,
                                "https://other",
                                Instant.now().plusSeconds(300),
                                "SAGA_SERVICE"),
                        token(
                                trusted,
                                "https://issuer/realms/robomart",
                                Instant.now().minusSeconds(120),
                                "SAGA_SERVICE"),
                        token(
                                generator.generateKeyPair(),
                                "https://issuer/realms/robomart",
                                Instant.now().plusSeconds(300),
                                "SAGA_SERVICE"))) {
            interceptor.interceptCall(call, headers(token), next);
        }
        verify(call, times(3))
                .close(argThat(s -> s.getCode() == Status.Code.UNAUTHENTICATED), any());
        verifyNoInteractions(next);
        interceptor.interceptCall(
                call,
                headers(
                        token(
                                trusted,
                                "https://issuer/realms/robomart",
                                Instant.now().plusSeconds(300),
                                "CUSTOMER")),
                next);
        verify(call).close(argThat(s -> s.getCode() == Status.Code.PERMISSION_DENIED), any());
        Metadata valid =
                headers(
                        token(
                                trusted,
                                "https://issuer/realms/robomart",
                                Instant.now().plusSeconds(300),
                                "SAGA_SERVICE"));
        interceptor.interceptCall(call, valid, next);
        verify(next).startCall(call, valid);
    }

    private static String token(KeyPair key, String issuer, Instant expiry, String role)
            throws Exception {
        var jwt =
                new SignedJWT(
                        new JWSHeader(JWSAlgorithm.RS256),
                        new JWTClaimsSet.Builder()
                                .subject("service")
                                .issuer(issuer)
                                .audience("robomart-api")
                                .expirationTime(Date.from(expiry))
                                .claim("azp", "checkout-saga-service")
                                .claim("realm_access", Map.of("roles", List.of(role)))
                                .build());
        jwt.sign(new RSASSASigner(key.getPrivate()));
        return jwt.serialize();
    }

    private static Metadata headers(String token) {
        var headers = new Metadata();
        headers.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + token);
        return headers;
    }
}
