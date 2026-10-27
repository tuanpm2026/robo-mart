package com.robomart.product.integration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class ProductOutboxMigrationIT {
    @Test
    void migrationPreservesLegacyNameUuidIdentityAndAllHistoricalRows() {
        try (var postgres = new PostgreSQLContainer<>("postgres:17-alpine").withDatabaseName("product_outbox_migration")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("7").load().migrate();
            var jdbc = new JdbcTemplate(source);
            long id = jdbc.queryForObject("INSERT INTO outbox_events(aggregate_type,aggregate_id,event_type,payload) "
                    + "VALUES ('PRODUCT','legacy','PRODUCT_CREATED','{}'::jsonb) RETURNING id", Long.class);
            UUID previousId = UUID.nameUUIDFromBytes(("product-outbox-" + id).getBytes(StandardCharsets.UTF_8));
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
            assertThat(jdbc.queryForObject("SELECT event_id FROM outbox_events WHERE id=?", UUID.class, id)).isEqualTo(previousId);
            assertThat(jdbc.queryForObject("SELECT published FROM outbox_events WHERE id=?", Boolean.class, id)).isFalse();
            assertThat(jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE id=?", String.class, id)).isEqualTo("{}");
            assertThat(jdbc.queryForObject("SELECT claim_token IS NULL AND claimed_until IS NULL FROM outbox_events WHERE id=?",
                    Boolean.class, id)).isTrue();
        }
    }
}
