package com.robomart.security.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.List;
import java.util.Map;

/** Attach only to the internal participant service, not to legacy public RPCs. */
public final class SagaRpcAuthInterceptor implements ServerInterceptor {
    private static final Metadata.Key<String> AUTH =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private final JwtDecoder decoder;
    private final String audience;

    public SagaRpcAuthInterceptor(JwtDecoder decoder, String audience) {
        this.decoder = decoder;
        this.audience = audience;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(
            ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
        String bearer = headers.get(AUTH);
        if (bearer == null || !bearer.startsWith("Bearer ") || bearer.substring(7).isBlank()) {
            call.close(
                    Status.UNAUTHENTICATED.withDescription("Service token required"),
                    new Metadata());
            return new ServerCall.Listener<>() {};
        }
        try {
            var jwt = decoder.decode(bearer.substring(7));
            Object access = jwt.getClaims().get("realm_access");
            Object roles = access instanceof Map<?, ?> values ? values.get("roles") : null;
            if (jwt.getAudience() == null
                    || !jwt.getAudience().contains(audience)
                    || !"checkout-saga-service".equals(jwt.getClaimAsString("azp"))
                    || !(roles instanceof List<?> values)
                    || !values.contains("SAGA_SERVICE")) {
                call.close(
                        Status.PERMISSION_DENIED.withDescription("Saga service identity required"),
                        new Metadata());
                return new ServerCall.Listener<>() {};
            }
            return next.startCall(call, headers);
        } catch (JwtException invalid) {
            call.close(
                    Status.UNAUTHENTICATED.withDescription("Invalid service token"),
                    new Metadata());
            return new ServerCall.Listener<>() {};
        }
    }
}
