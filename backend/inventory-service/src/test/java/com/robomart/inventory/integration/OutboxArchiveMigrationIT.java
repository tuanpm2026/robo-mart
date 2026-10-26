package com.robomart.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class OutboxArchiveMigrationIT {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void retiredRestockRowsKeepTheirPayloadAndValidRowsReceiveStableIds() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).target("6").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO outbox_events(aggregate_type, aggregate_id, event_type, payload) "
                + "VALUES ('InventoryItem', '42', 'stock_restocked', '{\"quantity\":7}'::jsonb)");
        jdbc.update("INSERT INTO outbox_events(aggregate_type, aggregate_id, event_type, payload) "
                + "VALUES ('InventoryItem', '43', 'stock_reserved', '{\"quantity\":2}'::jsonb)");
        Flyway.configure().dataSource(dataSource).load().migrate();
        assertThat(jdbc.queryForObject("SELECT payload->>'quantity' FROM retired_outbox_events", String.class))
                .isEqualTo("7");
        assertThat(jdbc.queryForObject("SELECT retirement_reason FROM retired_outbox_events", String.class))
                .contains("No external restock event contract");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type='stock_restocked'",
                Integer.class)).isZero();
        UUID eventId = jdbc.queryForObject("SELECT event_id FROM outbox_events WHERE aggregate_id='43'", UUID.class);
        assertThat(eventId).isNotNull();
        Flyway.configure().dataSource(dataSource).load().migrate();
        assertThat(jdbc.queryForObject("SELECT event_id FROM outbox_events WHERE aggregate_id='43'", UUID.class))
                .isEqualTo(eventId);
    }
}
