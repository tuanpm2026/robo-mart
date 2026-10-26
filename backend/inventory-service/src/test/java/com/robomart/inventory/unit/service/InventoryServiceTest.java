package com.robomart.inventory.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.robomart.common.exception.ResourceNotFoundException;
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
import com.robomart.inventory.service.DistributedLockService;
import com.robomart.inventory.service.InventoryService;

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("InventoryService Unit Tests")
class InventoryServiceTest {

    @Mock
    private InventoryItemRepository inventoryItemRepository;

    @Mock
    private StockMovementRepository stockMovementRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationLineRepository reservationLineRepository;

    @Mock
    private DistributedLockService distributedLockService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Captor
    private ArgumentCaptor<StockMovement> stockMovementCaptor;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    private InventoryService inventoryService;

    private static final Long PRODUCT_ID = 1L;
    private static final String ORDER_ID = "order-123";
    private static final String LOCK_VALUE = "test-lock-value";

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryService(
                inventoryItemRepository,
                stockMovementRepository,
                outboxEventRepository,
                reservationRepository,
                reservationLineRepository,
                distributedLockService,
                transactionTemplate,
                objectMapper
        );
    }

    /**
     * Sets up standard mocking for distributed lock acquisition and transaction execution.
     * The TransactionTemplate mock is configured to actually invoke the callback.
     */
    @SuppressWarnings("unchecked")
    private void setupLockAndTransactionMocks() {
        when(distributedLockService.generateLockValue()).thenReturn(LOCK_VALUE);
        when(distributedLockService.acquireLock(anyString(), eq(LOCK_VALUE), any(Duration.class)))
                .thenReturn(true);
        lenient().when(distributedLockService.isLockHeld(anyString(), eq(LOCK_VALUE)))
                .thenReturn(true);
        lenient().when(distributedLockService.releaseLock(anyString(), eq(LOCK_VALUE)))
                .thenReturn(true);
        when(transactionTemplate.execute(any(TransactionCallback.class)))
                .thenAnswer(invocation -> {
                    TransactionCallback<?> callback = invocation.getArgument(0);
                    return callback.doInTransaction(null);
                });
    }

    /**
     * Creates a test InventoryItem with the specified quantities.
     */
    private InventoryItem createTestInventoryItem(Long productId, int available, int reserved, int total) {
        InventoryItem item = new InventoryItem();
        item.setProductId(productId);
        item.setAvailableQuantity(available);
        item.setReservedQuantity(reserved);
        item.setTotalQuantity(total);
        item.setLowStockThreshold(10);
        return item;
    }

    @Nested
    @DisplayName("reserveStock")
    class ReserveStock {

        @Test
        @DisplayName("should reserve stock when sufficient quantity is available")
        void shouldReserveStockWhenSufficientQuantity() throws Exception {
            // given
            setupLockAndTransactionMocks();
            InventoryItem item = createTestInventoryItem(PRODUCT_ID, 100, 0, 100);
            when(inventoryItemRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(item));
            when(inventoryItemRepository.save(any(InventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
            when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
            when(stockMovementRepository.save(any(StockMovement.class))).thenAnswer(inv -> inv.getArgument(0));

            // when
            InventoryItem result = inventoryService.reserveStock(PRODUCT_ID, 5, ORDER_ID);

            // then
            assertThat(result.getAvailableQuantity()).isEqualTo(95);
            assertThat(result.getReservedQuantity()).isEqualTo(5);
        }

        @Test
        @DisplayName("should throw InsufficientStockException when not enough quantity available")
        void shouldThrowInsufficientStockWhenNotEnoughQuantity() throws Exception {
            // given
            setupLockAndTransactionMocks();
            InventoryItem item = createTestInventoryItem(PRODUCT_ID, 3, 0, 3);
            when(inventoryItemRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(item));

            // when/then
            assertThatThrownBy(() -> inventoryService.reserveStock(PRODUCT_ID, 5, ORDER_ID))
                    .isInstanceOf(InsufficientStockException.class);
        }

        @Test
        @DisplayName("should throw LockAcquisitionException when lock is unavailable")
        void shouldThrowLockAcquisitionExceptionWhenLockUnavailable() {
            // given
            when(distributedLockService.generateLockValue()).thenReturn(LOCK_VALUE);
            when(distributedLockService.acquireLock(anyString(), eq(LOCK_VALUE), any(Duration.class)))
                    .thenReturn(false);

            // when/then
            assertThatThrownBy(() -> inventoryService.reserveStock(PRODUCT_ID, 5, ORDER_ID))
                    .isInstanceOf(LockAcquisitionException.class);
        }

        @Test
        @DisplayName("should create StockMovement with RESERVE type on successful reservation")
        void shouldCreateStockMovementOnReserve() throws Exception {
            // given
            setupLockAndTransactionMocks();
            InventoryItem item = createTestInventoryItem(PRODUCT_ID, 100, 0, 100);
            when(inventoryItemRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(item));
            when(inventoryItemRepository.save(any(InventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
            when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
            when(stockMovementRepository.save(any(StockMovement.class))).thenAnswer(inv -> inv.getArgument(0));

            // when
            inventoryService.reserveStock(PRODUCT_ID, 5, ORDER_ID);

            // then
            verify(stockMovementRepository).save(stockMovementCaptor.capture());
            StockMovement captured = stockMovementCaptor.getValue();
            assertThat(captured.getType()).isEqualTo(StockMovementType.RESERVE);
            assertThat(captured.getQuantity()).isEqualTo(5);
            assertThat(captured.getOrderId()).isEqualTo(ORDER_ID);
        }

        @Test
        @DisplayName("should publish outbox event with type stock_reserved on successful reservation")
        void shouldPublishOutboxEventOnReserve() throws Exception {
            // given
            setupLockAndTransactionMocks();
            InventoryItem item = createTestInventoryItem(PRODUCT_ID, 100, 0, 100);
            when(inventoryItemRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(item));
            when(inventoryItemRepository.save(any(InventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
            when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
            when(stockMovementRepository.save(any(StockMovement.class))).thenAnswer(inv -> inv.getArgument(0));

            // when
            inventoryService.reserveStock(PRODUCT_ID, 5, ORDER_ID);

            // then
            verify(outboxEventRepository).save(outboxEventCaptor.capture());
            OutboxEvent captured = outboxEventCaptor.getValue();
            assertThat(captured.getEventType()).isEqualTo("stock_reserved");
            assertThat(captured.getAggregateType()).isEqualTo("InventoryItem");
            assertThat(captured.getAggregateId()).isEqualTo(PRODUCT_ID.toString());
        }

        @Test
        @DisplayName("should publish low stock alert when available drops below threshold")
        void shouldPublishLowStockAlertWhenBelowThreshold() throws Exception {
            // given
            setupLockAndTransactionMocks();
            InventoryItem item = createTestInventoryItem(PRODUCT_ID, 12, 0, 12);
            item.setLowStockThreshold(10);
            when(inventoryItemRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(item));
            when(inventoryItemRepository.save(any(InventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
            when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
            when(stockMovementRepository.save(any(StockMovement.class))).thenAnswer(inv -> inv.getArgument(0));

            // when
            inventoryService.reserveStock(PRODUCT_ID, 5, ORDER_ID);

            // then — available becomes 7, which is below threshold of 10
            // Expect 2 outbox events: stock_reserved + stock_low_alert
            verify(outboxEventRepository, times(2)).save(outboxEventCaptor.capture());
            assertThat(outboxEventCaptor.getAllValues())
                    .extracting(OutboxEvent::getEventType)
                    .containsExactly("stock_reserved", "stock_low_alert");
        }

        @Test
        @DisplayName("should not publish low stock alert when available stays above threshold")
        void shouldNotPublishLowStockAlertWhenAboveThreshold() throws Exception {
            // given
            setupLockAndTransactionMocks();
            InventoryItem item = createTestInventoryItem(PRODUCT_ID, 100, 0, 100);
            item.setLowStockThreshold(10);
            when(inventoryItemRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(item));
            when(inventoryItemRepository.save(any(InventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
            when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
            when(stockMovementRepository.save(any(StockMovement.class))).thenAnswer(inv -> inv.getArgument(0));

            // when
            inventoryService.reserveStock(PRODUCT_ID, 5, ORDER_ID);

            // then — available becomes 95, which is above threshold of 10
            // Expect only 1 outbox event: stock_reserved
            verify(outboxEventRepository, times(1)).save(outboxEventCaptor.capture());
            assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo("stock_reserved");
        }

        @Test
        @DisplayName("should compensate and throw when lock expires during transaction")
        @SuppressWarnings("unchecked")
        void shouldCompensateWhenLockExpiresDuringTransaction() throws Exception {
            // given — setup lock that works for acquire but reports expired after transaction
            when(distributedLockService.generateLockValue()).thenReturn(LOCK_VALUE);
            when(distributedLockService.acquireLock(anyString(), eq(LOCK_VALUE), any(Duration.class)))
                    .thenReturn(true);
            when(distributedLockService.isLockHeld(anyString(), eq(LOCK_VALUE)))
                    .thenReturn(false)  // First call: lock expired (triggers compensation)
                    .thenReturn(true);  // Subsequent calls: lock held (compensation completes normally)
            lenient().when(distributedLockService.releaseLock(anyString(), eq(LOCK_VALUE)))
                    .thenReturn(true);
            when(transactionTemplate.execute(any(TransactionCallback.class)))
                    .thenAnswer(invocation -> {
                        TransactionCallback<?> callback = invocation.getArgument(0);
                        return callback.doInTransaction(null);
                    });

            InventoryItem item = createTestInventoryItem(PRODUCT_ID, 100, 0, 100);
            when(inventoryItemRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(item));
            when(inventoryItemRepository.save(any(InventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
            when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
            when(stockMovementRepository.save(any(StockMovement.class))).thenAnswer(inv -> inv.getArgument(0));

            // when/then — should throw RuntimeException about lock expiry
            assertThatThrownBy(() -> inventoryService.reserveStock(PRODUCT_ID, 5, ORDER_ID))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Lock expired");
        }
    }

    @Nested
    @DisplayName("releaseStock")
    class ReleaseStock {

        @Test
        @DisplayName("should release stock and publish stock_released event")
        void shouldReleaseStockAndPublishEvent() throws Exception {
            // given
            setupLockAndTransactionMocks();
            InventoryItem item = createTestInventoryItem(PRODUCT_ID, 90, 10, 100);
            when(inventoryItemRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(item));
            when(inventoryItemRepository.save(any(InventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
            when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
            when(stockMovementRepository.save(any(StockMovement.class))).thenAnswer(inv -> inv.getArgument(0));

            // when
            InventoryItem result = inventoryService.releaseStock(PRODUCT_ID, 5, ORDER_ID);

            // then
            assertThat(result.getAvailableQuantity()).isEqualTo(95);
            assertThat(result.getReservedQuantity()).isEqualTo(5);

            verify(outboxEventRepository).save(outboxEventCaptor.capture());
            assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo("stock_released");

            verify(stockMovementRepository).save(stockMovementCaptor.capture());
            assertThat(stockMovementCaptor.getValue().getType()).isEqualTo(StockMovementType.RELEASE);
        }

        @Test
        @DisplayName("should throw IllegalStateException when releasing more than reserved")
        void shouldThrowExceptionWhenReleasingMoreThanReserved() throws Exception {
            // given
            setupLockAndTransactionMocks();
            InventoryItem item = createTestInventoryItem(PRODUCT_ID, 97, 3, 100);
            when(inventoryItemRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(item));

            // when/then
            assertThatThrownBy(() -> inventoryService.releaseStock(PRODUCT_ID, 5, ORDER_ID))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("getInventory")
    class GetInventory {

        @Test
        @DisplayName("should return inventory item when product exists")
        void shouldGetInventorySuccessfully() {
            // given
            InventoryItem item = createTestInventoryItem(PRODUCT_ID, 100, 0, 100);
            when(inventoryItemRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(item));

            // when
            InventoryItem result = inventoryService.getInventory(PRODUCT_ID);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getProductId()).isEqualTo(PRODUCT_ID);
            assertThat(result.getAvailableQuantity()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("restockItem")
    class RestockItem {

        @Test
        @DisplayName("should increase available and total quantities")
        void restockItem_validProduct_increasesAvailableAndTotal() throws Exception {
            // given
            InventoryItem item = createTestInventoryItem(PRODUCT_ID, 50, 5, 55);
            when(inventoryItemRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(item));
            when(inventoryItemRepository.save(any(InventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(stockMovementRepository.save(any(StockMovement.class))).thenAnswer(inv -> inv.getArgument(0));
            when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            // when
            InventoryItem result = inventoryService.restockItem(PRODUCT_ID, 20, "Manual restock");

            // then
            assertThat(result.getAvailableQuantity()).isEqualTo(70);
            assertThat(result.getTotalQuantity()).isEqualTo(75);
        }

        @Test
        @DisplayName("should throw IllegalArgumentException when quantity <= 0")
        void restockItem_invalidQuantity_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> inventoryService.restockItem(PRODUCT_ID, 0, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Quantity must be positive");

            assertThatThrownBy(() -> inventoryService.restockItem(PRODUCT_ID, -5, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when product not found")
        void restockItem_productNotFound_throwsResourceNotFoundException() {
            // given
            when(inventoryItemRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> inventoryService.restockItem(PRODUCT_ID, 10, null))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("should create StockMovement with RESTOCK type")
        void restockItem_createsStockMovementWithRestockType() throws Exception {
            // given
            InventoryItem item = createTestInventoryItem(PRODUCT_ID, 50, 0, 50);
            when(inventoryItemRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(item));
            when(inventoryItemRepository.save(any(InventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(stockMovementRepository.save(any(StockMovement.class))).thenAnswer(inv -> inv.getArgument(0));
            when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            // when
            inventoryService.restockItem(PRODUCT_ID, 10, "Test restock");

            // then
            verify(stockMovementRepository).save(stockMovementCaptor.capture());
            StockMovement captured = stockMovementCaptor.getValue();
            assertThat(captured.getType()).isEqualTo(StockMovementType.RESTOCK);
            assertThat(captured.getQuantity()).isEqualTo(10);
            assertThat(captured.getReason()).isEqualTo("Test restock");
        }

        @Test
        @DisplayName("should create outbox event with stock_restocked type")
        void restockItem_createsOutboxEventWithStockRestockedType() throws Exception {
            // given
            InventoryItem item = createTestInventoryItem(PRODUCT_ID, 50, 0, 50);
            when(inventoryItemRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(item));
            when(inventoryItemRepository.save(any(InventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(stockMovementRepository.save(any(StockMovement.class))).thenAnswer(inv -> inv.getArgument(0));
            when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
            when(objectMapper.writeValueAsString(any())).thenReturn("{\"productId\":1}");

            // when
            inventoryService.restockItem(PRODUCT_ID, 10, null);

            // then
            verify(outboxEventRepository).save(outboxEventCaptor.capture());
            OutboxEvent captured = outboxEventCaptor.getValue();
            assertThat(captured.getEventType()).isEqualTo("stock_restocked");
            assertThat(captured.getAggregateType()).isEqualTo("InventoryItem");
            assertThat(captured.getAggregateId()).isEqualTo(PRODUCT_ID.toString());
        }

        @Test
        @DisplayName("should use default reason when reason is null")
        void restockItem_nullReason_usesDefaultReason() throws Exception {
            // given
            InventoryItem item = createTestInventoryItem(PRODUCT_ID, 50, 0, 50);
            when(inventoryItemRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(item));
            when(inventoryItemRepository.save(any(InventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(stockMovementRepository.save(any(StockMovement.class))).thenAnswer(inv -> inv.getArgument(0));
            when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            // when
            inventoryService.restockItem(PRODUCT_ID, 10, null);

            // then
            verify(stockMovementRepository).save(stockMovementCaptor.capture());
            assertThat(stockMovementCaptor.getValue().getReason()).isEqualTo("Admin restock");
        }

        @Test
        @DisplayName("should propagate OptimisticLockingFailureException on concurrent save")
        void restockItem_concurrentModification_throwsOptimisticLockingFailure() {
            // given
            InventoryItem item = createTestInventoryItem(PRODUCT_ID, 50, 0, 50);
            when(inventoryItemRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(item));
            when(inventoryItemRepository.save(any(InventoryItem.class)))
                    .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(
                            InventoryItem.class.getName(), PRODUCT_ID));

            // when / then
            assertThatThrownBy(() -> inventoryService.restockItem(PRODUCT_ID, 10, null))
                    .isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
        }
    }

    @Nested
    @DisplayName("bulkRestock")
    class BulkRestock {

        @Test
        @DisplayName("should restock all specified products")
        void bulkRestock_multipleProducts_restocksAll() throws Exception {
            // given
            Long productId2 = 2L;
            InventoryItem item1 = createTestInventoryItem(PRODUCT_ID, 30, 0, 30);
            InventoryItem item2 = createTestInventoryItem(productId2, 10, 0, 10);
            when(inventoryItemRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(item1));
            when(inventoryItemRepository.findByProductId(productId2)).thenReturn(Optional.of(item2));
            when(inventoryItemRepository.save(any(InventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(stockMovementRepository.save(any(StockMovement.class))).thenAnswer(inv -> inv.getArgument(0));
            when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            // when
            var results = inventoryService.bulkRestock(List.of(PRODUCT_ID, productId2), 50, "Bulk");

            // then
            assertThat(results).hasSize(2);
            assertThat(results.get(0).getAvailableQuantity()).isEqualTo(80);
            assertThat(results.get(1).getAvailableQuantity()).isEqualTo(60);
            verify(stockMovementRepository, times(2)).save(any(StockMovement.class));
        }
    }

    @Nested
    @DisplayName("findReservation")
    class FindReservation {

        @Test
        @DisplayName("findReservation delegates to repository by orderId")
        void findReservationDelegatesToRepository() {
            Reservation reservation = new Reservation("res-1", ORDER_ID, ReservationStatus.RESERVED);
            when(reservationRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(reservation));

            Optional<Reservation> result = inventoryService.findReservation(ORDER_ID);

            assertThat(result).containsSame(reservation);
        }
    }

    /** Stubs the TransactionTemplate to run its callback inline (no Redis locks involved). */
    @SuppressWarnings("unchecked")
    private void setupTransactionOnly() {
        when(transactionTemplate.execute(any(TransactionCallback.class)))
                .thenAnswer(invocation -> {
                    TransactionCallback<?> callback = invocation.getArgument(0);
                    return callback.doInTransaction(null);
                });
    }

    private void stubStockWrites() {
        when(inventoryItemRepository.save(any(InventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stockMovementRepository.save(any(StockMovement.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("reserveOrder")
    class ReserveOrder {

        private static final Long PRODUCT_2 = 2L;

        @Test
        @DisplayName("reserves every line in one transaction and stores the lines")
        void reservesAllLinesAndStoresThem() {
            setupLockAndTransactionMocks();
            stubStockWrites();
            InventoryItem item1 = createTestInventoryItem(PRODUCT_ID, 100, 0, 100);
            InventoryItem item2 = createTestInventoryItem(PRODUCT_2, 50, 0, 50);
            when(reservationRepository.insertIfAbsent(anyString(), eq(ORDER_ID), eq("RESERVED"))).thenReturn(1);
            when(inventoryItemRepository.findByProductIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(item1));
            when(inventoryItemRepository.findByProductIdForUpdate(PRODUCT_2)).thenReturn(Optional.of(item2));

            Map<Long, Integer> quantities = new LinkedHashMap<>();
            quantities.put(PRODUCT_2, 3);
            quantities.put(PRODUCT_ID, 5);
            InventoryService.ReservationResult result = inventoryService.reserveOrder(ORDER_ID, quantities);

            assertThat(result.replayed()).isFalse();
            assertThat(result.reservationId()).isNotBlank();
            assertThat(item1.getAvailableQuantity()).isEqualTo(95);
            assertThat(item1.getReservedQuantity()).isEqualTo(5);
            assertThat(item2.getAvailableQuantity()).isEqualTo(47);
            assertThat(item2.getReservedQuantity()).isEqualTo(3);

            ArgumentCaptor<ReservationLine> lines = ArgumentCaptor.forClass(ReservationLine.class);
            verify(reservationLineRepository, times(2)).save(lines.capture());
            assertThat(lines.getAllValues())
                    .extracting(ReservationLine::getProductId, ReservationLine::getQuantity)
                    .containsExactly(tuple(PRODUCT_ID, 5), tuple(PRODUCT_2, 3));

            // Locks are taken in product-id order and all released afterwards
            InOrder inOrder = inOrder(distributedLockService);
            inOrder.verify(distributedLockService).acquireLock(eq("inventory:lock:product:1"), eq(LOCK_VALUE), any(Duration.class));
            inOrder.verify(distributedLockService).acquireLock(eq("inventory:lock:product:2"), eq(LOCK_VALUE), any(Duration.class));
            verify(distributedLockService).releaseLock("inventory:lock:product:1", LOCK_VALUE);
            verify(distributedLockService).releaseLock("inventory:lock:product:2", LOCK_VALUE);
        }

        @Test
        @DisplayName("replays an existing RESERVED reservation without touching stock")
        void replaysExistingReservation() {
            setupLockAndTransactionMocks();
            when(reservationRepository.insertIfAbsent(anyString(), eq(ORDER_ID), eq("RESERVED"))).thenReturn(0);
            when(reservationRepository.findByOrderId(ORDER_ID))
                    .thenReturn(Optional.of(new Reservation("existing", ORDER_ID, ReservationStatus.RESERVED)));

            InventoryService.ReservationResult result = inventoryService.reserveOrder(ORDER_ID, Map.of(PRODUCT_ID, 5));

            assertThat(result.replayed()).isTrue();
            assertThat(result.reservationId()).isEqualTo("existing");
            verify(inventoryItemRepository, never()).findByProductIdForUpdate(anyLong());
            verify(reservationLineRepository, never()).save(any(ReservationLine.class));
        }

        @Test
        @DisplayName("rejects a reserve after a RELEASED tombstone without touching stock")
        void rejectsAfterReleasedTombstone() {
            setupLockAndTransactionMocks();
            when(reservationRepository.insertIfAbsent(anyString(), eq(ORDER_ID), eq("RESERVED"))).thenReturn(0);
            when(reservationRepository.findByOrderId(ORDER_ID))
                    .thenReturn(Optional.of(new Reservation("tomb", ORDER_ID, ReservationStatus.RELEASED)));

            assertThatThrownBy(() -> inventoryService.reserveOrder(ORDER_ID, Map.of(PRODUCT_ID, 5)))
                    .isInstanceOf(ReservationReleasedException.class);

            verify(inventoryItemRepository, never()).findByProductIdForUpdate(anyLong());
            verify(distributedLockService).releaseLock("inventory:lock:product:1", LOCK_VALUE);
        }

        @Test
        @DisplayName("fails fast and releases already-acquired locks when a lock is unavailable")
        void releasesAcquiredLocksWhenALockIsUnavailable() {
            when(distributedLockService.generateLockValue()).thenReturn(LOCK_VALUE);
            when(distributedLockService.acquireLock(eq("inventory:lock:product:1"), eq(LOCK_VALUE), any(Duration.class)))
                    .thenReturn(true);
            when(distributedLockService.acquireLock(eq("inventory:lock:product:2"), eq(LOCK_VALUE), any(Duration.class)))
                    .thenReturn(false);

            assertThatThrownBy(() -> inventoryService.reserveOrder(ORDER_ID, Map.of(PRODUCT_ID, 1, PRODUCT_2, 1)))
                    .isInstanceOf(LockAcquisitionException.class);

            verify(distributedLockService).releaseLock("inventory:lock:product:1", LOCK_VALUE);
            verify(distributedLockService, never()).releaseLock("inventory:lock:product:2", LOCK_VALUE);
            verify(transactionTemplate, never()).execute(any());
        }

        @Test
        @DisplayName("propagates InsufficientStockException so the whole transaction rolls back")
        void propagatesInsufficientStock() {
            setupLockAndTransactionMocks();
            when(reservationRepository.insertIfAbsent(anyString(), eq(ORDER_ID), eq("RESERVED"))).thenReturn(1);
            when(inventoryItemRepository.findByProductIdForUpdate(PRODUCT_ID))
                    .thenReturn(Optional.of(createTestInventoryItem(PRODUCT_ID, 2, 0, 2)));

            assertThatThrownBy(() -> inventoryService.reserveOrder(ORDER_ID, Map.of(PRODUCT_ID, 5)))
                    .isInstanceOf(InsufficientStockException.class);
            verify(reservationLineRepository, never()).save(any(ReservationLine.class));
        }

        @Test
        @DisplayName("throws ResourceNotFoundException for an unknown product")
        void throwsNotFoundForUnknownProduct() {
            setupLockAndTransactionMocks();
            when(reservationRepository.insertIfAbsent(anyString(), eq(ORDER_ID), eq("RESERVED"))).thenReturn(1);
            when(inventoryItemRepository.findByProductIdForUpdate(PRODUCT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> inventoryService.reserveOrder(ORDER_ID, Map.of(PRODUCT_ID, 5)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("still succeeds (row locks held) when a Redis lock expired during the transaction")
        void succeedsWhenRedisLockExpired() {
            setupLockAndTransactionMocks();
            stubStockWrites();
            when(distributedLockService.isLockHeld(anyString(), eq(LOCK_VALUE))).thenReturn(false);
            when(reservationRepository.insertIfAbsent(anyString(), eq(ORDER_ID), eq("RESERVED"))).thenReturn(1);
            when(inventoryItemRepository.findByProductIdForUpdate(PRODUCT_ID))
                    .thenReturn(Optional.of(createTestInventoryItem(PRODUCT_ID, 100, 0, 100)));

            InventoryService.ReservationResult result = inventoryService.reserveOrder(ORDER_ID, Map.of(PRODUCT_ID, 5));

            assertThat(result.replayed()).isFalse();
        }

        @Test
        @DisplayName("rejects empty or non-positive lines before taking any lock")
        void rejectsInvalidLines() {
            assertThatThrownBy(() -> inventoryService.reserveOrder(ORDER_ID, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> inventoryService.reserveOrder(ORDER_ID, Map.of(PRODUCT_ID, 0)))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(distributedLockService, never()).acquireLock(anyString(), anyString(), any(Duration.class));
        }
    }

    @Nested
    @DisplayName("releaseReservation")
    class ReleaseReservation {

        @Test
        @DisplayName("claims the release and restores exactly the stored lines")
        void restoresStoredLines() {
            setupTransactionOnly();
            stubStockWrites();
            InventoryItem item = createTestInventoryItem(PRODUCT_ID, 90, 10, 100);
            when(reservationRepository.claimRelease(ORDER_ID)).thenReturn(1);
            when(reservationLineRepository.findByOrderIdOrderByProductIdAsc(ORDER_ID))
                    .thenReturn(List.of(new ReservationLine(ORDER_ID, PRODUCT_ID, 4)));
            when(inventoryItemRepository.findByProductIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(item));

            InventoryService.ReleaseOutcome outcome = inventoryService.releaseReservation(ORDER_ID);

            assertThat(outcome).isEqualTo(InventoryService.ReleaseOutcome.RELEASED);
            assertThat(item.getAvailableQuantity()).isEqualTo(94);
            assertThat(item.getReservedQuantity()).isEqualTo(6);
            verify(stockMovementRepository).save(stockMovementCaptor.capture());
            assertThat(stockMovementCaptor.getValue().getType()).isEqualTo(StockMovementType.RELEASE);
            assertThat(stockMovementCaptor.getValue().getQuantity()).isEqualTo(4);
            verify(distributedLockService, never()).acquireLock(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("is a no-op when the reservation is already released")
        void noOpWhenAlreadyReleased() {
            setupTransactionOnly();
            when(reservationRepository.claimRelease(ORDER_ID)).thenReturn(0);
            when(reservationRepository.findByOrderId(ORDER_ID))
                    .thenReturn(Optional.of(new Reservation("res-1", ORDER_ID, ReservationStatus.RELEASED)));

            assertThat(inventoryService.releaseReservation(ORDER_ID))
                    .isEqualTo(InventoryService.ReleaseOutcome.ALREADY_RELEASED);
            verify(inventoryItemRepository, never()).findByProductIdForUpdate(anyLong());
            verify(reservationRepository, never()).insertIfAbsent(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("records a RELEASED tombstone and restores nothing when no reservation exists")
        void tombstoneWhenNoReservation() {
            setupTransactionOnly();
            when(reservationRepository.claimRelease(ORDER_ID)).thenReturn(0);
            when(reservationRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
            when(reservationRepository.insertIfAbsent(anyString(), eq(ORDER_ID), eq("RELEASED"))).thenReturn(1);

            assertThat(inventoryService.releaseReservation(ORDER_ID))
                    .isEqualTo(InventoryService.ReleaseOutcome.NO_RESERVATION);
            verify(inventoryItemRepository, never()).findByProductIdForUpdate(anyLong());
            verify(reservationLineRepository, never()).findByOrderIdOrderByProductIdAsc(anyString());
        }

        @Test
        @DisplayName("claims again when a reserve commits between the claim and the tombstone insert")
        void retriesClaimWhenReserveWinsTombstoneRace() {
            setupTransactionOnly();
            when(reservationRepository.claimRelease(ORDER_ID)).thenReturn(0, 1);
            when(reservationRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
            when(reservationRepository.insertIfAbsent(anyString(), eq(ORDER_ID), eq("RELEASED"))).thenReturn(0);
            when(reservationLineRepository.findByOrderIdOrderByProductIdAsc(ORDER_ID)).thenReturn(List.of());

            assertThat(inventoryService.releaseReservation(ORDER_ID))
                    .isEqualTo(InventoryService.ReleaseOutcome.RELEASED);
            verify(reservationRepository, times(2)).claimRelease(ORDER_ID);
        }

        @Test
        @DisplayName("claims again when the reservation became RESERVED after the first claim attempt")
        void retriesClaimWhenReservationAppearsReserved() {
            setupTransactionOnly();
            when(reservationRepository.claimRelease(ORDER_ID)).thenReturn(0, 1);
            when(reservationRepository.findByOrderId(ORDER_ID))
                    .thenReturn(Optional.of(new Reservation("res-1", ORDER_ID, ReservationStatus.RESERVED)));
            when(reservationLineRepository.findByOrderIdOrderByProductIdAsc(ORDER_ID)).thenReturn(List.of());

            assertThat(inventoryService.releaseReservation(ORDER_ID))
                    .isEqualTo(InventoryService.ReleaseOutcome.RELEASED);
            verify(reservationRepository, never()).insertIfAbsent(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("gives up with a ConcurrencyFailureException when it cannot settle the reservation")
        void throwsConcurrencyFailureWhenUnsettled() {
            setupTransactionOnly();
            when(reservationRepository.claimRelease(ORDER_ID)).thenReturn(0);
            when(reservationRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
            when(reservationRepository.insertIfAbsent(anyString(), eq(ORDER_ID), eq("RELEASED"))).thenReturn(0);

            assertThatThrownBy(() -> inventoryService.releaseReservation(ORDER_ID))
                    .isInstanceOf(ConcurrencyFailureException.class);
        }

        @Test
        @DisplayName("fails (rolling back the claim) when a stored line exceeds the reserved quantity")
        void failsWhenStoredLineExceedsReserved() {
            setupTransactionOnly();
            when(reservationRepository.claimRelease(ORDER_ID)).thenReturn(1);
            when(reservationLineRepository.findByOrderIdOrderByProductIdAsc(ORDER_ID))
                    .thenReturn(List.of(new ReservationLine(ORDER_ID, PRODUCT_ID, 4)));
            when(inventoryItemRepository.findByProductIdForUpdate(PRODUCT_ID))
                    .thenReturn(Optional.of(createTestInventoryItem(PRODUCT_ID, 100, 1, 101)));

            assertThatThrownBy(() -> inventoryService.releaseReservation(ORDER_ID))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
