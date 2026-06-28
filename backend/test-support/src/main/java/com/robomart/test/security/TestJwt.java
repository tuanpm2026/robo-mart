package com.robomart.test.security;

/**
 * Fixed bearer tokens recognised by {@link TestJwtDecoderConfig} in integration tests of
 * JWT-secured services. Import {@code TestJwtDecoderConfig} on the test and send one of these as
 * the {@code Authorization} header to exercise role-based access without a real Keycloak.
 */
public final class TestJwt {

    public static final String ADMIN_TOKEN = "test-admin-token";
    public static final String CUSTOMER_TOKEN = "test-customer-token";

    private TestJwt() {
    }

    public static String adminBearer() {
        return "Bearer " + ADMIN_TOKEN;
    }

    public static String customerBearer() {
        return "Bearer " + CUSTOMER_TOKEN;
    }
}
