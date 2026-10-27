package com.robomart.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class DlqMigrationIT {
    @Container private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void oldFalseResolutionAndPreviewOnlyRecordsRemainVisibleForManualReview() {
        var data = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(data).target("5").load().migrate();
        var jdbc = new JdbcTemplate(data);
        jdbc.update("INSERT INTO failed_events(event_type,original_topic,payload_preview,status) "
                + "VALUES ('old','old','preview','RESOLVED'),('pending','pending','pending-preview','PENDING')");
        Flyway.configure().dataSource(data).load().migrate();
        assertThat(jdbc.queryForList("SELECT status FROM failed_events", String.class)).containsOnly("LEGACY_REVIEW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM failed_events WHERE raw_record_available", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT payload_preview FROM failed_events WHERE event_type='old'", String.class))
                .isEqualTo("preview");
    }
}
