package com.robomart.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;

import com.robomart.notification.service.ProcessedEventRetentionJob;
import com.robomart.notification.service.ReconciliationScheduler;
import com.robomart.notification.service.ReconciliationService;
import com.robomart.notification.web.ReconciliationResult;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.listener.auto-startup=false", "notification.scheduling.enabled=false"})
@ActiveProfiles("test")
@Import(NotificationMaintenanceIT.Database.class)
class NotificationMaintenanceIT {
    @TestConfiguration(proxyBeanMethods = false)
    static class Database {
        @Bean @ServiceConnection
        PostgreSQLContainer<?> maintenancePostgres() {
            return new PostgreSQLContainer<>("postgres:17-alpine").withDatabaseName("notification_maintenance");
        }
    }
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LockProvider locks;
    @Autowired private ReconciliationScheduler scheduler;
    @Autowired private ProcessedEventRetentionJob retention;
    @MockitoBean private ReconciliationService reconciliation;

    @Test
    void heldDistributedLockSkipsDuplicateReconciliation() {
        var lock = locks.lock(new LockConfiguration(Instant.now(), "NotificationInventoryReconciliation",
                Duration.ofMinutes(5), Duration.ZERO)).orElseThrow();
        scheduler.runDailyInventoryReconciliation();
        verifyNoInteractions(reconciliation);
        lock.unlock();
        when(reconciliation.runInventoryReconciliation()).thenReturn(
                new ReconciliationResult("INVENTORY", List.of(), false, Instant.now()));
        scheduler.runDailyInventoryReconciliation();
        verify(reconciliation).runInventoryReconciliation();
    }

    @Test
    void retentionHonorsDistributedLockAndKeepsActiveFailedRecordDedupIdentity() {
        jdbc.update("DELETE FROM failed_events");
        jdbc.update("DELETE FROM processed_events");
        jdbc.update("INSERT INTO processed_events(consumer_group,event_id,processed_at) VALUES "
                + "('g','old',now()-interval '40 days'),('g','new',now()-interval '1 day'),"
                + "('g','held',now()-interval '40 days'),('other','held',now()-interval '40 days')");
        jdbc.update("INSERT INTO failed_events(event_type,original_topic,status,raw_record_available,"
                + "source_event_id,source_consumer_group) VALUES ('test','source','PENDING',true,'held','g')");
        var lock = locks.lock(new LockConfiguration(Instant.now(), "NotificationProcessedRetention",
                Duration.ofMinutes(5), Duration.ZERO)).orElseThrow();
        retention.cleanup();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class)).isEqualTo(4);
        lock.unlock();
        retention.cleanup();
        assertThat(jdbc.queryForList("SELECT consumer_group||':'||event_id FROM processed_events ORDER BY event_id",
                String.class)).containsExactly("g:held", "g:new");
    }
}
