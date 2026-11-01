package com.robomart.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.robomart.inventory.service.InventoryService;
import com.robomart.test.InventoryIntegrationTest;

@InventoryIntegrationTest
class InventoryStockSnapshotIT {
    @Autowired private InventoryService inventory;
    @Autowired private JdbcTemplate jdbc;

    private long product() {
        long id = ThreadLocalRandom.current().nextLong(2000000, 9000000);
        inventory.initializeProduct(id);
        return id;
    }

    private List<Map<String, Object>> snapshots(long product) {
        return jdbc.queryForList("SELECT (payload->>'availableQuantity')::int AS available, "
                + "(payload->>'inventoryVersion')::bigint AS version FROM outbox_events "
                + "WHERE aggregate_id=? AND event_type='stock_updated' ORDER BY id", Long.toString(product));
    }

    @Test
    void snapshotsFollowRestockReserveCommitAndRefundWithoutDuplicatingReplay() {
        long product = product();
        String order = UUID.randomUUID().toString();
        inventory.restockItem(product, 25, "Snapshot regression");
        inventory.reserveOrder(order, Map.of(product, 2));
        inventory.reserveOrder(order, Map.of(product, 2));
        inventory.commitReservation(order);
        inventory.commitReservation(order);
        inventory.releaseReservation(order);
        inventory.releaseReservation(order);

        var snapshots = snapshots(product);
        assertThat(snapshots).extracting(row -> row.get("available")).containsExactly(0, 25, 23, 23, 25);
        assertThat(snapshots).extracting(row -> (Long) row.get("version")).isSorted().doesNotHaveDuplicates();
        var item = inventory.getInventory(product);
        assertThat(snapshots.getLast().get("version")).isEqualTo(item.getVersion().longValue());
        assertThat(item.getAvailableQuantity()).isEqualTo(25);
        assertThat(item.getReservedQuantity()).isZero();
        assertThat(item.getTotalQuantity()).isEqualTo(25);
    }

    @Test
    void productCreationReplayPreservesExistingStockAndDoesNotEmitAnotherSnapshot() {
        long product = product();
        inventory.restockItem(product, 17, "Existing stock");
        inventory.initializeProduct(product);
        assertThat(inventory.getInventory(product).getAvailableQuantity()).isEqualTo(17);
        assertThat(snapshots(product)).hasSize(2);
        assertThatThrownBy(() -> inventory.initializeProduct(0L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotFailureRollsBackStockMovementAndReservation() {
        long product = product();
        inventory.restockItem(product, 25, "Rollback baseline");
        int version = inventory.getInventory(product).getVersion();
        String order = UUID.randomUUID().toString();
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT reject_stock_snapshot CHECK "
                + "(event_type <> 'stock_updated' OR aggregate_id <> '" + product + "') NOT VALID");
        try {
            assertThatThrownBy(() -> inventory.restockItem(product, 5, "Must roll back"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> inventory.reserveOrder(order, Map.of(product, 2)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(inventory.getInventory(product).getAvailableQuantity()).isEqualTo(25);
            assertThat(inventory.getInventory(product).getVersion()).isEqualTo(version);
            assertThat(inventory.findReservation(order)).isEmpty();
            assertThat(snapshots(product)).hasSize(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements m JOIN inventory_items i "
                    + "ON i.id=m.inventory_item_id WHERE i.product_id=?", Integer.class, product)).isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT reject_stock_snapshot");
        }
    }

    @Test
    void snapshotFailureKeepsReleaseRecoverable() {
        long product = product();
        inventory.restockItem(product, 25, "Release baseline");
        String order = UUID.randomUUID().toString();
        inventory.reserveOrder(order, Map.of(product, 2));
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT reject_release_snapshot CHECK "
                + "(event_type <> 'stock_updated' OR aggregate_id <> '" + product + "') NOT VALID");
        try {
            assertThatThrownBy(() -> inventory.releaseReservation(order))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(inventory.getInventory(product).getAvailableQuantity()).isEqualTo(23);
            assertThat(inventory.findReservation(order).orElseThrow().getStatus().name()).isEqualTo("RESERVED");
        } finally {
            jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT reject_release_snapshot");
        }
        assertThat(inventory.releaseReservation(order)).isEqualTo(InventoryService.ReleaseOutcome.RELEASED);
        assertThat(inventory.getInventory(product).getAvailableQuantity()).isEqualTo(25);
    }
}
