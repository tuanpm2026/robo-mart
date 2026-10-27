package com.robomart.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import com.robomart.test.PostgresContainerConfig;
import com.robomart.test.security.TestJwt;
import com.robomart.test.security.TestJwtDecoderConfig;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PostgresContainerConfig.class, TestJwtDecoderConfig.class})
class PaymentOperationsRbacIT {
    @LocalServerPort private int port;

    @Test
    void notificationCanReadReconciliationAndAuditButCannotReadPaymentDetailsOrWrite() {
        var client = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + TestJwt.NOTIFICATION_TOKEN)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { }).build();
        for (String path : new String[]{"/api/v1/admin/payments/reconciliation-summary?size=100",
                "/api/v1/admin/payments/audit-logs?size=100"}) {
            assertThat(client.get().uri(path).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(client.get().uri("/api/v1/admin/payments/audit-logs?size=101").retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(client.get().uri("/api/v1/admin/payments/order/1").retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(client.post().uri("/api/v1/admin/payments/reconciliation-summary").retrieve()
                .toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
