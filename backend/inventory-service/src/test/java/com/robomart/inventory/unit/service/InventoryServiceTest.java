package com.robomart.inventory.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.inventory.entity.InventoryItem;
import com.robomart.inventory.entity.OutboxEvent;
import com.robomart.inventory.entity.StockMovement;
import com.robomart.inventory.enums.StockMovementType;
import com.robomart.inventory.exception.InsufficientStockException;
import com.robomart.inventory.exception.LockAcquisitionException;
import com.robomart.inventory.repository.InventoryItemRepository;
import com.robomart.inventory.repository.OutboxEventRepository;
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
}
