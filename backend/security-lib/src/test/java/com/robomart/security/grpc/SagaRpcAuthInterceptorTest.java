package com.robomart.security.grpc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.List;
import java.util.Map;

class SagaRpcAuthInterceptorTest {
    private final JwtDecoder decoder = mock(JwtDecoder.class);
    private final SagaRpcAuthInterceptor interceptor =
            new SagaRpcAuthInterceptor(decoder, "robomart-api");
    private final ServerCall<Object, Object> call = mock(ServerCall.class);
    private final ServerCallHandler<Object, Object> next = mock(ServerCallHandler.class);

    @Test
    void requiresBearer() {
        interceptor.interceptCall(call, new Metadata(), next);
        verify(call).close(argThat(s -> s.getCode() == Status.Code.UNAUTHENTICATED), any());
        verifyNoInteractions(decoder, next);
    }

    @Test
    void rejectsInvalidSignature() {
        when(decoder.decode("token")).thenThrow(new JwtException("bad signature"));
        interceptor.interceptCall(call, headers(), next);
        verify(call).close(argThat(s -> s.getCode() == Status.Code.UNAUTHENTICATED), any());
        verifyNoInteractions(next);
    }

    @Test
    void requiresAudienceClientAndRoleTogether() {
        for (Jwt token :
                List.of(
                        jwt(
                                "other",
                                "checkout-saga-service",
                                Map.of("roles", List.of("SAGA_SERVICE"))),
                        jwt("robomart-api", "customer", Map.of("roles", List.of("SAGA_SERVICE"))),
                        jwt(
                                "robomart-api",
                                "checkout-saga-service",
                                Map.of("roles", List.of("ADMIN"))),
                        jwt("robomart-api", "checkout-saga-service", Map.of()))) {
            when(decoder.decode("token")).thenReturn(token);
            interceptor.interceptCall(call, headers(), next);
        }
        verify(call, times(4))
                .close(argThat(s -> s.getCode() == Status.Code.PERMISSION_DENIED), any());
        verifyNoInteractions(next);
    }

    @Test
    void missingAudienceOrMalformedRolesAreDenied() {
        for (Jwt jwt :
                List.of(
                        Jwt.withTokenValue("token")
                                .header("alg", "RS256")
                                .subject("saga")
                                .claim("azp", "checkout-saga-service")
                                .build(),
                        Jwt.withTokenValue("token")
                                .header("alg", "RS256")
                                .subject("saga")
                                .audience(List.of("robomart-api"))
                                .claim("azp", "checkout-saga-service")
                                .claim("realm_access", "invalid")
                                .build())) {
            when(decoder.decode("token")).thenReturn(jwt);
            interceptor.interceptCall(call, headers(), next);
        }
        verify(call, times(2))
                .close(argThat(status -> status.getCode() == Status.Code.PERMISSION_DENIED), any());
        verifyNoInteractions(next);
    }

    @Test
    void permitsVerifiedSagaIdentity() {
        when(decoder.decode("token"))
                .thenReturn(
                        jwt(
                                "robomart-api",
                                "checkout-saga-service",
                                Map.of("roles", List.of("SAGA_SERVICE"))));
        Metadata headers = headers();
        interceptor.interceptCall(call, headers, next);
        verify(next).startCall(call, headers);
        verifyNoInteractions(call);
    }

    private static Jwt jwt(String audience, String client, Map<String, Object> roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("saga")
                .audience(List.of(audience))
                .claim("azp", client)
                .claim("realm_access", roles)
                .build();
    }

    private static Metadata headers() {
        Metadata headers = new Metadata();
        headers.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer token");
        return headers;
    }
}
