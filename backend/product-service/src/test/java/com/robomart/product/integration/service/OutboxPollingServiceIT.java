package com.robomart.product.integration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.robomart.product.entity.OutboxEvent;
import com.robomart.product.repository.OutboxEventRepository;
import com.robomart.product.service.OutboxPollingService;
import com.robomart.test.IntegrationTest;

@IntegrationTest
class OutboxPollingServiceIT {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxPollingService outboxPollingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM outbox_events");
    }

    @Test
    void shouldPublishEventAndMarkAsPublished() {
        String payload = "{\"id\":1,\"sku\":\"TEST-001\",\"name\":\"Test Product\"," +
                "\"price\":\"29.99\",\"categoryId\":1,\"categoryName\":\"Test Category\"," +
                "\"stockQuantity\":10}";

        var event = new OutboxEvent("PRODUCT", "1", "PRODUCT_CREATED", payload);
        outboxEventRepository.save(event);

        outboxPollingService.pollAndPublish();

        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().isPublished()).isTrue();
        assertThat(events.getFirst().getPublishedAt()).isNotNull();
    }

    @Test
    void shouldProcessBatchOfEvents() {
        for (int i = 1; i <= 3; i++) {
            String payload = String.format(
                    "{\"id\":%d,\"sku\":\"BATCH-%03d\",\"name\":\"Product %d\"," +
                    "\"price\":\"%.2f\",\"categoryId\":1,\"categoryName\":\"Test\"," +
                    "\"stockQuantity\":%d}", i, i, i, i * 10.0, i * 5);
            outboxEventRepository.save(new OutboxEvent("PRODUCT", String.valueOf(i), "PRODUCT_CREATED", payload));
        }

        outboxPollingService.pollAndPublish();

        List<OutboxEvent> allEvents = outboxEventRepository.findAll();
        assertThat(allEvents).allMatch(OutboxEvent::isPublished);
    }
}
