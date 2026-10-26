package com.robomart.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PaymentMigrationIT {
    @Container private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void migrationPreservesDeadOutboxAndOnlyBackfillsRefundAmountFromProviderReceipt() {
        var data = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(data).target("4").load().migrate();
        var jdbc = new JdbcTemplate(data);
        jdbc.update("INSERT INTO payments(order_id, amount, currency, status, transaction_id, idempotency_key) "
                + "VALUES ('known',20,'USD','REFUNDED','known-txn','known-key'), "
                + "('unknown',20,'USD','REFUNDED','unknown-txn','unknown-key')");
        jdbc.update("INSERT INTO mock_gateway_operations(operation_key,amount,currency,request_identity,status,transaction_id) "
                + "VALUES ('refund:known-txn',8,'','known-txn','REFUNDED','historical-receipt')");
        jdbc.update("INSERT INTO outbox_events(aggregate_type,aggregate_id,event_type,payload) "
                + "VALUES ('Payment','known','payment_refunded','{\"amount\":\"8\"}'::jsonb)");
        Flyway.configure().dataSource(data).load().migrate();
        assertThat(jdbc.queryForObject("SELECT refunded_amount::text FROM payments WHERE order_id='known'", String.class))
                .isEqualTo("8.00");
        assertThat(jdbc.queryForObject("SELECT refunded_amount IS NULL FROM payments WHERE order_id='unknown'", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT payload->>'amount' FROM retired_outbox_events", String.class)).isEqualTo("8");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name='outbox_events'",
                Integer.class)).isZero();
    }
}
