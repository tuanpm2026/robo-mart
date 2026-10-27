package com.robomart.inventory.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.junit.jupiter.api.Test;

import com.robomart.inventory.config.InventoryKafkaHealthIndicator;

class InventoryKafkaHealthIndicatorTest {
    @Test
    void connectedClusterIsUpAndTimeoutIsDown() throws Exception {
        Admin admin = mock(Admin.class);
        DescribeClusterResult cluster = mock(DescribeClusterResult.class);
        @SuppressWarnings("unchecked") KafkaFuture<Collection<Node>> nodes = mock(KafkaFuture.class);
        when(admin.describeCluster()).thenReturn(cluster);
        when(cluster.nodes()).thenReturn(nodes);
        when(nodes.get(2, TimeUnit.SECONDS)).thenReturn(List.of(new Node(1, "broker", 9092)))
                .thenThrow(new TimeoutException("broker timeout"));
        try (var indicator = new InventoryKafkaHealthIndicator(admin)) {
            assertThat(indicator.health().getStatus().getCode()).isEqualTo("UP");
            assertThat(indicator.health().getStatus().getCode()).isEqualTo("DOWN");
        }
    }
}
