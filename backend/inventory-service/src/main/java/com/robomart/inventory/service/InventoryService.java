package com.robomart.inventory.service;

import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.inventory.config.RedisLockConfig;
import com.robomart.inventory.dto.InventoryMetricsResponse;
import com.robomart.inventory.entity.InventoryItem;
import com.robomart.inventory.entity.OutboxEvent;
import com.robomart.inventory.entity.StockMovement;
import com.robomart.inventory.enums.StockMovementType;
import com.robomart.inventory.exception.InsufficientStockException;
import com.robomart.inventory.exception.LockAcquisitionException;
import com.robomart.inventory.repository.InventoryItemRepository;
import com.robomart.inventory.repository.OutboxEventRepository;
import com.robomart.inventory.repository.StockMovementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for managing inventory operations with distributed locking and transactional outbox.
 *
 * All write operations (reserve, release) use Redis distributed locks to prevent race conditions
 * in distributed environments. Outbox events are created for all state changes to enable event-driven
 * communication with other services.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final InventoryItemRepository inventoryItemRepository;
    private final StockMovementRepository stockMovementRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final DistributedLockService distributedLockService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public InventoryService(
            InventoryItemRepository inventoryItemRepository,
            StockMovementRepository stockMovementRepository,
            OutboxEventRepository outboxEventRepository,
            DistributedLockService distributedLockService,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper
    ) {
        this.inventoryItemRepository = inventoryItemRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.outboxEventRepository = outboxEventRepository;
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
                // Step 3: Load InventoryItem
                InventoryItem item = inventoryItemRepository.findByProductId(productId)
                        .orElseThrow(() -> new ResourceNotFoundException("InventoryItem", productId));

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
                // Load InventoryItem
                InventoryItem item = inventoryItemRepository.findByProductId(productId)
                        .orElseThrow(() -> new ResourceNotFoundException("InventoryItem", productId));

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
}
