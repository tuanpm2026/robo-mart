package com.robomart.inventory.service;

import com.robomart.common.audit.AuditAction;
import com.robomart.common.audit.Auditable;
import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.inventory.config.RedisLockConfig;
import com.robomart.inventory.dto.InventoryMetricsResponse;
import com.robomart.inventory.dto.ReconciliationSummaryResponse;
import com.robomart.inventory.entity.InventoryItem;
import com.robomart.inventory.entity.OutboxEvent;
import com.robomart.inventory.entity.Reservation;
import com.robomart.inventory.entity.ReservationLine;
import com.robomart.inventory.entity.StockMovement;
import com.robomart.inventory.enums.ReservationStatus;
import com.robomart.inventory.enums.StockMovementType;
import com.robomart.inventory.exception.InsufficientStockException;
import com.robomart.inventory.exception.LockAcquisitionException;
import com.robomart.inventory.exception.ReservationReleasedException;
import com.robomart.inventory.repository.InventoryItemRepository;
import com.robomart.inventory.repository.OutboxEventRepository;
import com.robomart.inventory.repository.ReservationLineRepository;
import com.robomart.inventory.repository.ReservationRepository;
import com.robomart.inventory.repository.StockMovementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Service for managing inventory operations with distributed locking and transactional outbox.
 *
 * All write operations (reserve, release) use Redis distributed locks to prevent race conditions
 * in distributed environments. Outbox events are created for all state changes to enable event-driven
 * communication with other services.
 *
 * <p>Order-level reservations ({@link #reserveOrder} / {@link #releaseReservation}, used by the gRPC
 * saga endpoints) persist their lines and are released only from those stored lines, each in a single
 * transaction guarded by the {@code reservations.order_id} unique key and row locks.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final InventoryItemRepository inventoryItemRepository;
    private final StockMovementRepository stockMovementRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationLineRepository reservationLineRepository;
    private final DistributedLockService distributedLockService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public InventoryService(
            InventoryItemRepository inventoryItemRepository,
            StockMovementRepository stockMovementRepository,
            OutboxEventRepository outboxEventRepository,
            ReservationRepository reservationRepository,
            ReservationLineRepository reservationLineRepository,
            DistributedLockService distributedLockService,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper
    ) {
        this.inventoryItemRepository = inventoryItemRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.reservationRepository = reservationRepository;
        this.reservationLineRepository = reservationLineRepository;
        this.distributedLockService = distributedLockService;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Reserves stock for a product with distributed locking.
     *
     * Flow:
     * 1. Acquire Redis lock (outside transaction)
     * 2. Begin DB transaction
     * 3. Load InventoryItem with pessimistic lock
     * 4. Validate sufficient stock
     * 5. Update quantities (decrement available, increment reserved)
     * 6. Save InventoryItem
     * 7. Create StockMovement record
     * 8. Create outbox event (stock_reserved)
     * 9. Check low stock threshold and create alert if needed
     * 10. Commit transaction
     * 11. Verify lock still held (compensate if expired)
     * 12. Release Redis lock
     *
     * @param productId the product ID
     * @param quantity  quantity to reserve
     * @param orderId   the order ID requesting the reservation
     * @return the updated InventoryItem
     * @throws LockAcquisitionException  if lock cannot be acquired
     * @throws ResourceNotFoundException if product not found
     * @throws InsufficientStockException if not enough stock available
     */
    @Auditable(action = AuditAction.UPDATE, entityType = "INVENTORY", entityIdExpression = "#result?.productId?.toString()")
    public InventoryItem reserveStock(Long productId, int quantity, String orderId) {
        String lockKey = RedisLockConfig.LOCK_KEY_PREFIX + productId;
        String lockValue = distributedLockService.generateLockValue();

        log.info("Attempting to reserve stock: productId={}, quantity={}, orderId={}",
                productId, quantity, orderId);

        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive, got: " + quantity);
        }

        // Step 1: Acquire Redis lock (outside transaction)
        if (!distributedLockService.acquireLock(lockKey, lockValue, RedisLockConfig.DEFAULT_LOCK_TTL)) {
            log.warn("Failed to acquire lock for product {}", productId);
            throw new LockAcquisitionException(productId, lockKey);
        }

        try {
            // Steps 2-10: Execute DB operations within transaction
            InventoryItem result = transactionTemplate.execute(status -> {
                InventoryItem item = inventoryItemRepository.findByProductId(productId)
                        .orElseThrow(() -> new ResourceNotFoundException("InventoryItem", productId));
                return applyReservation(item, quantity, orderId);
            });

            // Step 11: Verify lock still held (compensate if expired)
            if (!distributedLockService.isLockHeld(lockKey, lockValue)) {
                log.error("Lock expired during transaction for product {}, triggering compensation", productId);
                // Compensate by releasing the reservation
                compensateReservation(productId, quantity, orderId);
                throw new RuntimeException("Lock expired during reservation - compensation triggered");
            }

            return result;

        } finally {
            // Step 12: Release Redis lock
            distributedLockService.releaseLock(lockKey, lockValue);
        }
    }

    /**
     * Releases reserved stock back to available inventory with distributed locking.
     *
     * Flow similar to reserveStock but with opposite quantity adjustments.
     *
     * @param productId the product ID
     * @param quantity  quantity to release
     * @param orderId   the order ID releasing the reservation
     * @return the updated InventoryItem
     * @throws LockAcquisitionException  if lock cannot be acquired
     * @throws ResourceNotFoundException if product not found
     * @throws IllegalStateException     if not enough reserved quantity
     */
    public InventoryItem releaseStock(Long productId, int quantity, String orderId) {
        String lockKey = RedisLockConfig.LOCK_KEY_PREFIX + productId;
        String lockValue = distributedLockService.generateLockValue();

        log.info("Attempting to release stock: productId={}, quantity={}, orderId={}",
                productId, quantity, orderId);

        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive, got: " + quantity);
        }

        // Step 1: Acquire Redis lock (outside transaction)
        if (!distributedLockService.acquireLock(lockKey, lockValue, RedisLockConfig.DEFAULT_LOCK_TTL)) {
            log.warn("Failed to acquire lock for product {}", productId);
            throw new LockAcquisitionException(productId, lockKey);
        }

        try {
            // Steps 2-10: Execute DB operations within transaction
            InventoryItem result = transactionTemplate.execute(status -> {
                InventoryItem item = inventoryItemRepository.findByProductId(productId)
                        .orElseThrow(() -> new ResourceNotFoundException("InventoryItem", productId));
                return applyRelease(item, quantity, orderId);
            });

            // Verify lock still held (compensate if expired)
            if (!distributedLockService.isLockHeld(lockKey, lockValue)) {
                log.error("Lock expired during transaction for product {}, triggering compensation", productId);
                // Compensate by re-reserving the stock
                compensateRelease(productId, quantity, orderId);
                throw new RuntimeException("Lock expired during release - compensation triggered");
            }

            return result;

        } finally {
            // Release Redis lock
            distributedLockService.releaseLock(lockKey, lockValue);
        }
    }

    /**
     * Gets current inventory for a product (read-only, no locking needed).
     *
     * @param productId the product ID
     * @return the InventoryItem
     * @throws ResourceNotFoundException if product not found
     */
    public InventoryItem getInventory(Long productId) {
        log.debug("Retrieving inventory for productId={}", productId);
        return inventoryItemRepository.findByProductId(productId)
                .orElseThrow(() -> new ResourceNotFoundException("InventoryItem", productId));
    }

    /**
     * Looks up the reservation record for an order, if any. The record is the idempotency anchor for
     * the gRPC reserve/release calls (keyed by {@code orderId}).
     *
     * @param orderId the order ID
     * @return the reservation record, or empty if no reservation has ever been recorded for the order
     */
    @Transactional(readOnly = true)
    public Optional<Reservation> findReservation(String orderId) {
        return reservationRepository.findByOrderId(orderId);
    }

    /**
     * Reserves every line of an order atomically: the reservation row, its lines and all stock
     * decrements commit (or roll back) in ONE transaction, so a partial failure never leaves stock
     * reserved without a reservation, nor a reservation without its lines.
     *
     * <p>Flow:
     * <ol>
     *   <li>Acquire the Redis lock of every product, in product-id order (outside the transaction).</li>
     *   <li>Insert the reservation row first ({@code ON CONFLICT (order_id) DO NOTHING}). If a row
     *       already exists, nothing is decremented: a RESERVED row is replayed (idempotent retry), a
     *       RELEASED row (including a release tombstone) fails with
     *       {@link ReservationReleasedException}.</li>
     *   <li>For each product (product-id order) load the item {@code FOR UPDATE}, validate and
     *       decrement stock, and store the reservation line.</li>
     * </ol>
     * The DB row locks, not the Redis locks, are what keep the stock consistent; the Redis locks only
     * reduce contention and are retained until Wave 7 replaces them with conditional SQL updates.
     *
     * @param orderId    the order ID (idempotency key)
     * @param quantities quantity to reserve per product ID (each must be positive)
     * @return the new reservation, or the existing one when replayed
     * @throws LockAcquisitionException     if any product lock cannot be acquired
     * @throws ResourceNotFoundException    if a product has no inventory item
     * @throws InsufficientStockException   if any product lacks available stock
     * @throws ReservationReleasedException if the order's reservation was already released
     */
    @Auditable(action = AuditAction.UPDATE, entityType = "RESERVATION", entityIdExpression = "#orderId")
    public ReservationResult reserveOrder(String orderId, Map<Long, Integer> quantities) {
        if (quantities.isEmpty()) {
            throw new IllegalArgumentException("At least one item is required");
        }
        SortedMap<Long, Integer> lines = new TreeMap<>(quantities);
        lines.forEach((productId, quantity) -> {
            if (quantity == null || quantity <= 0) {
                throw new IllegalArgumentException(
                        "Quantity must be positive for product " + productId + ", got: " + quantity);
            }
        });

        log.info("Attempting to reserve order: orderId={}, lines={}", orderId, lines);

        String lockValue = distributedLockService.generateLockValue();
        List<String> heldLocks = new ArrayList<>();
        try {
            for (Long productId : lines.keySet()) {
                String lockKey = RedisLockConfig.LOCK_KEY_PREFIX + productId;
                if (!distributedLockService.acquireLock(lockKey, lockValue, RedisLockConfig.DEFAULT_LOCK_TTL)) {
                    log.warn("Failed to acquire lock for product {} while reserving orderId={}", productId, orderId);
                    throw new LockAcquisitionException(productId, lockKey);
                }
                heldLocks.add(lockKey);
            }

            ReservationResult result = transactionTemplate.execute(status -> reserveOrderInTransaction(orderId, lines));

            for (String lockKey : heldLocks) {
                if (!distributedLockService.isLockHeld(lockKey, lockValue)) {
                    // Row locks kept the committed reservation consistent; nothing to compensate.
                    log.warn("Redis lock {} expired while reserving orderId={}", lockKey, orderId);
                }
            }
            return result;
        } finally {
            for (String lockKey : heldLocks) {
                distributedLockService.releaseLock(lockKey, lockValue);
            }
        }
    }

    private ReservationResult reserveOrderInTransaction(String orderId, SortedMap<Long, Integer> lines) {
        String reservationId = UUID.randomUUID().toString();
        if (reservationRepository.insertIfAbsent(reservationId, orderId, ReservationStatus.RESERVED.name()) == 0) {
            Reservation existing = reservationRepository.findByOrderId(orderId)
                    .orElseThrow(() -> new IllegalStateException("Reservation for order " + orderId + " vanished"));
            if (existing.getStatus() == ReservationStatus.RELEASED) {
                log.warn("Reserve rejected: reservation already released for orderId={}", orderId);
                throw new ReservationReleasedException(orderId);
            }
            log.info("Reserve replayed: existing reservationId={} for orderId={}",
                    existing.getReservationId(), orderId);
            return new ReservationResult(existing.getReservationId(), true);
        }

        lines.forEach((productId, quantity) -> {
            InventoryItem item = inventoryItemRepository.findByProductIdForUpdate(productId)
                    .orElseThrow(() -> new ResourceNotFoundException("InventoryItem", productId));
            applyReservation(item, quantity, orderId);
            reservationLineRepository.save(new ReservationLine(orderId, productId, quantity));
        });

        log.info("Order reserved: orderId={}, reservationId={}, lineCount={}", orderId, reservationId, lines.size());
        return new ReservationResult(reservationId, false);
    }

    /**
     * Releases an order's reservation, restoring exactly the stored reservation lines (never anything
     * supplied by the caller). Idempotent and safe under concurrency; everything runs in ONE
     * transaction:
     * <ul>
     *   <li>The release is claimed atomically ({@code RESERVED -> RELEASED} conditional update); only
     *       the single claimer restores stock, so concurrent or retried releases restore it once.</li>
     *   <li>If the reservation is already RELEASED, nothing happens.</li>
     *   <li>If no reservation exists, a RELEASED tombstone is inserted and nothing is restored; a
     *       reserve arriving later for the same order then fails instead of reserving stock.</li>
     * </ul>
     *
     * @param orderId the order ID
     * @return what the call did
     * @throws IllegalStateException if a stored line can no longer be released (reserved quantity
     *                               below the line quantity); the whole release is rolled back
     */
    public ReleaseOutcome releaseReservation(String orderId) {
        log.info("Attempting to release reservation: orderId={}", orderId);
        return transactionTemplate.execute(status -> releaseReservationInTransaction(orderId));
    }

    private ReleaseOutcome releaseReservationInTransaction(String orderId) {
        // Two passes cover a reserve that commits between our claim attempt and our tombstone insert:
        // the insert then conflicts and the second claim sees the committed RESERVED row.
        for (int attempt = 0; attempt < 2; attempt++) {
            if (reservationRepository.claimRelease(orderId) == 1) {
                List<ReservationLine> lines = reservationLineRepository.findByOrderIdOrderByProductIdAsc(orderId);
                for (ReservationLine line : lines) {
                    InventoryItem item = inventoryItemRepository.findByProductIdForUpdate(line.getProductId())
                            .orElseThrow(() -> new ResourceNotFoundException("InventoryItem", line.getProductId()));
                    applyRelease(item, line.getQuantity(), orderId);
                }
                log.info("Reservation released: orderId={}, lineCount={}", orderId, lines.size());
                return ReleaseOutcome.RELEASED;
            }

            Optional<Reservation> existing = reservationRepository.findByOrderId(orderId);
            if (existing.isPresent()) {
                if (existing.get().getStatus() == ReservationStatus.RELEASED) {
                    log.info("Release idempotent no-op: already released for orderId={}", orderId);
                    return ReleaseOutcome.ALREADY_RELEASED;
                }
                continue; // RESERVED row committed after our claim attempt: claim again
            }

            if (reservationRepository.insertIfAbsent(
                    UUID.randomUUID().toString(), orderId, ReservationStatus.RELEASED.name()) == 1) {
                log.warn("Release for orderId={} found no reservation: recorded RELEASED tombstone, nothing restored",
                        orderId);
                return ReleaseOutcome.NO_RESERVATION;
            }
        }
        throw new ConcurrencyFailureException("Concurrent reservation activity for order " + orderId + ", retry");
    }

    /**
     * Result of {@link #reserveOrder}.
     *
     * @param reservationId the reservation ID to return to the caller
     * @param replayed      true if the order was already reserved and nothing was decremented
     */
    public record ReservationResult(String reservationId, boolean replayed) {
    }

    /** Result of {@link #releaseReservation}. */
    public enum ReleaseOutcome {
        /** This call claimed the reservation and restored its stored lines. */
        RELEASED,
        /** The reservation was already released; nothing changed. */
        ALREADY_RELEASED,
        /** No reservation existed; a RELEASED tombstone was recorded and nothing was restored. */
        NO_RESERVATION
    }

    /**
     * Compensates for a reservation when lock expires during transaction.
     * Releases the reserved stock back to available.
     *
     * @param productId the product ID
     * @param quantity  quantity to compensate
     * @param orderId   the order ID
     */
    private void compensateReservation(Long productId, int quantity, String orderId) {
        log.warn("Compensating reservation: productId={}, quantity={}, orderId={}",
                productId, quantity, orderId);
        try {
            releaseStock(productId, quantity, orderId + "-COMPENSATION");
        } catch (Exception e) {
            log.error("Compensation failed for productId={}, manual intervention may be required", productId, e);
        }
    }

    /**
     * Compensates for a release when lock expires during transaction.
     * Re-reserves the stock.
     *
     * @param productId the product ID
     * @param quantity  quantity to compensate
     * @param orderId   the order ID
     */
    private void compensateRelease(Long productId, int quantity, String orderId) {
        log.warn("Compensating release: productId={}, quantity={}, orderId={}",
                productId, quantity, orderId);
        try {
            reserveStock(productId, quantity, orderId + "-COMPENSATION");
        } catch (Exception e) {
            log.error("Compensation failed for productId={}, manual intervention may be required", productId, e);
        }
    }

    /**
     * Returns dashboard metrics: count of low-stock items.
     *
     * @return InventoryMetricsResponse with lowStockCount
     */
    @Transactional(readOnly = true)
    public InventoryMetricsResponse getMetrics() {
        long count = inventoryItemRepository.countLowStockItems();
        return new InventoryMetricsResponse(count);
    }

    /**
     * Lists all inventory items with pagination (read-only, no locking needed).
     *
     * @param pageable pagination and sort parameters
     * @return page of InventoryItems
     */
    @Transactional(readOnly = true)
    public Page<InventoryItem> listInventory(Pageable pageable) {
        return inventoryItemRepository.findAll(pageable);
    }

    /**
     * Restocks a product by increasing available and total quantities.
     * Uses @Transactional with optimistic locking (@Version on InventoryItem).
     * Admin restock does not require distributed locking (single-user operation).
     *
     * @param productId the product ID
     * @param quantity  quantity to add
     * @param reason    audit reason (nullable)
     * @return updated InventoryItem
     * @throws ResourceNotFoundException  if product not found
     * @throws IllegalArgumentException   if quantity <= 0
     */
    @Auditable(action = AuditAction.UPDATE, entityType = "InventoryItem", entityIdExpression = "#result?.id?.toString()")
    @Transactional
    public InventoryItem restockItem(Long productId, int quantity, String reason) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive, got: " + quantity);
        }

        InventoryItem item = inventoryItemRepository.findByProductId(productId)
                .orElseThrow(() -> new ResourceNotFoundException("InventoryItem", productId));

        item.setAvailableQuantity(item.getAvailableQuantity() + quantity);
        item.setTotalQuantity(item.getTotalQuantity() + quantity);
        InventoryItem saved = inventoryItemRepository.save(item);

        StockMovement movement = new StockMovement();
        movement.setInventoryItemId(saved.getId());
        movement.setType(StockMovementType.RESTOCK);
        movement.setQuantity(quantity);
        movement.setReason(reason != null ? reason : "Admin restock");
        stockMovementRepository.save(movement);

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("productId", productId);
            payload.put("quantity", quantity);
            payload.put("availableQuantity", saved.getAvailableQuantity());
            payload.put("totalQuantity", saved.getTotalQuantity());

            OutboxEvent event = new OutboxEvent(
                    "InventoryItem",
                    productId.toString(),
                    "stock_restocked",
                    objectMapper.writeValueAsString(payload)
            );
            outboxEventRepository.save(event);
        } catch (Exception e) {
            log.error("Failed to create stock_restocked outbox event", e);
            throw new RuntimeException("Failed to create outbox event", e);
        }

        log.info("Stock restocked: productId={}, quantity={}, newAvailable={}, newTotal={}",
                productId, quantity, saved.getAvailableQuantity(), saved.getTotalQuantity());

        return saved;
    }

    /**
     * Returns a reconciliation summary of all inventory items.
     *
     * @return ReconciliationSummaryResponse with all inventory items
     */
    @Transactional(readOnly = true)
    public ReconciliationSummaryResponse getReconciliationSummary() {
        List<ReconciliationSummaryResponse.ProductInventorySummary> items = inventoryItemRepository.findAll().stream()
                .map(i -> new ReconciliationSummaryResponse.ProductInventorySummary(
                        i.getProductId(),
                        i.getAvailableQuantity(),
                        i.getReservedQuantity(),
                        i.getTotalQuantity()))
                .toList();
        return new ReconciliationSummaryResponse(items, java.time.Instant.now());
    }

    /**
     * Bulk restocks multiple products with the same quantity.
     * All operations run in a single transaction.
     *
     * @param productIds list of product IDs to restock
     * @param quantity   quantity to add to each product
     * @param reason     audit reason (nullable)
     * @return list of updated InventoryItems
     */
    @Transactional
    public List<InventoryItem> bulkRestock(List<Long> productIds, int quantity, String reason) {
        return productIds.stream()
                .distinct()
                .map(productId -> restockItem(productId, quantity, reason))
                .toList();
    }

    /**
     * Validates and applies a reservation of {@code quantity} units to an already-loaded item:
     * decrements available, increments reserved, and writes the stock movement, the
     * {@code stock_reserved} outbox event and (if needed) a {@code stock_low_alert}. Must run
     * inside a transaction.
     */
    private InventoryItem applyReservation(InventoryItem item, int quantity, String orderId) {
        Long productId = item.getProductId();
        // Step 4: Validate sufficient stock
        if (item.getAvailableQuantity() < quantity) {
            throw new InsufficientStockException(
                    productId, quantity, item.getAvailableQuantity()
            );
        }

        // Step 5: Update quantities
        item.setAvailableQuantity(item.getAvailableQuantity() - quantity);
        item.setReservedQuantity(item.getReservedQuantity() + quantity);

        // Step 6: Save InventoryItem
        InventoryItem savedItem = inventoryItemRepository.save(item);

        // Step 7: Create StockMovement record
        StockMovement movement = new StockMovement();
        movement.setInventoryItemId(savedItem.getId());
        movement.setType(StockMovementType.RESERVE);
        movement.setQuantity(quantity);
        movement.setOrderId(orderId);
        movement.setReason("Stock reserved for order " + orderId);
        stockMovementRepository.save(movement);

        // Step 8: Create outbox event (stock_reserved)
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("productId", productId);
            payload.put("quantity", quantity);
            payload.put("orderId", orderId);
            payload.put("availableQuantity", savedItem.getAvailableQuantity());
            payload.put("reservedQuantity", savedItem.getReservedQuantity());

            OutboxEvent reservedEvent = new OutboxEvent(
                    "InventoryItem",
                    productId.toString(),
                    "stock_reserved",
                    objectMapper.writeValueAsString(payload)
            );
            outboxEventRepository.save(reservedEvent);
        } catch (Exception e) {
            log.error("Failed to create stock_reserved outbox event", e);
            throw new RuntimeException("Failed to create outbox event", e);
        }

        // Step 9: Check low stock threshold and create alert if needed
        if (savedItem.getAvailableQuantity() < savedItem.getLowStockThreshold()) {
            log.warn("Low stock alert for product {}: available={}, threshold={}",
                    productId, savedItem.getAvailableQuantity(), savedItem.getLowStockThreshold());

            try {
                Map<String, Object> alertPayload = new HashMap<>();
                alertPayload.put("productId", productId);
                alertPayload.put("availableQuantity", savedItem.getAvailableQuantity());
                alertPayload.put("lowStockThreshold", savedItem.getLowStockThreshold());

                OutboxEvent alertEvent = new OutboxEvent(
                        "InventoryItem",
                        productId.toString(),
                        "stock_low_alert",
                        objectMapper.writeValueAsString(alertPayload)
                );
                outboxEventRepository.save(alertEvent);
            } catch (Exception e) {
                log.error("Failed to create stock_low_alert outbox event", e);
                // Don't fail the transaction for alert event failure
            }
        }

        log.info("Stock reserved successfully: productId={}, quantity={}, orderId={}, newAvailable={}",
                productId, quantity, orderId, savedItem.getAvailableQuantity());

        return savedItem;
    }

    /**
     * Validates and applies a release of {@code quantity} reserved units on an already-loaded item:
     * increments available, decrements reserved, and writes the stock movement and the
     * {@code stock_released} outbox event. Must run inside a transaction.
     */
    private InventoryItem applyRelease(InventoryItem item, int quantity, String orderId) {
        Long productId = item.getProductId();
        // Validate sufficient reserved quantity
        if (item.getReservedQuantity() < quantity) {
            throw new IllegalStateException(String.format(
                    "Cannot release %d units for product %d: only %d units reserved",
                    quantity, productId, item.getReservedQuantity()
            ));
        }

        // Update quantities
        item.setAvailableQuantity(item.getAvailableQuantity() + quantity);
        item.setReservedQuantity(item.getReservedQuantity() - quantity);

        // Save InventoryItem
        InventoryItem savedItem = inventoryItemRepository.save(item);

        // Create StockMovement record
        StockMovement movement = new StockMovement();
        movement.setInventoryItemId(savedItem.getId());
        movement.setType(StockMovementType.RELEASE);
        movement.setQuantity(quantity);
        movement.setOrderId(orderId);
        movement.setReason("Stock released from order " + orderId);
        stockMovementRepository.save(movement);

        // Create outbox event (stock_released)
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("productId", productId);
            payload.put("quantity", quantity);
            payload.put("orderId", orderId);
            payload.put("availableQuantity", savedItem.getAvailableQuantity());
            payload.put("reservedQuantity", savedItem.getReservedQuantity());

            OutboxEvent releasedEvent = new OutboxEvent(
                    "InventoryItem",
                    productId.toString(),
                    "stock_released",
                    objectMapper.writeValueAsString(payload)
            );
            outboxEventRepository.save(releasedEvent);
        } catch (Exception e) {
            log.error("Failed to create stock_released outbox event", e);
            throw new RuntimeException("Failed to create outbox event", e);
        }

        log.info("Stock released successfully: productId={}, quantity={}, orderId={}, newAvailable={}",
                productId, quantity, orderId, savedItem.getAvailableQuantity());

        return savedItem;
    }
}
