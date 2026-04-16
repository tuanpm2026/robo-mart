package com.robomart.order.unit.saga;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.robomart.order.config.SagaProperties;
import com.robomart.order.entity.Order;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.repository.OrderRepository;
import com.robomart.order.saga.DeadSagaDetectionJob;
import com.robomart.order.saga.OrderSagaOrchestrator;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeadSagaDetectionJob")
class DeadSagaDetectionJobTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderSagaOrchestrator sagaOrchestrator;

    private SagaProperties sagaProperties;
    private DeadSagaDetectionJob job;

    @BeforeEach
    void setUp() {
        sagaProperties = new SagaProperties();
        job = new DeadSagaDetectionJob(orderRepository, sagaOrchestrator, sagaProperties);
    }

    private Order buildStuckOrder(OrderStatus status) {
        Order order = new Order();
        try {
            var idField = com.robomart.common.entity.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(order, 42L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        order.setUserId("user-1");
        order.setTotalAmount(new BigDecimal("99.99"));
        order.setStatus(status);
        order.setItems(new ArrayList<>());
        return order;
    }

    @Test
    @DisplayName("detectsAndRecoversStuckSagas")
    void detectsAndRecoversStuckSagas() {
        Order stuck = buildStuckOrder(OrderStatus.PAYMENT_PROCESSING);
        when(orderRepository.findStuckSagas(anyList(), any(Instant.class)))
            .thenReturn(List.of(stuck));

        job.detectAndRecoverDeadSagas();

        verify(sagaOrchestrator).handleDeadSaga(stuck);
    }

    @Test
    @DisplayName("skipsWhenDetectionDisabled")
    void skipsWhenDetectionDisabled() {
        sagaProperties.getDeadSagaDetection().setEnabled(false);

        job.detectAndRecoverDeadSagas();

        verifyNoInteractions(orderRepository, sagaOrchestrator);
    }

    @Test
    @DisplayName("doesNothingWhenNoStuckOrders")
    void doesNothingWhenNoStuckOrders() {
        when(orderRepository.findStuckSagas(anyList(), any(Instant.class)))
            .thenReturn(List.of());

        job.detectAndRecoverDeadSagas();

        verify(sagaOrchestrator, never()).handleDeadSaga(any());
    }

    @Test
    @DisplayName("continuesAfterIndividualRecoveryFailure")
    void continuesAfterIndividualRecoveryFailure() {
        Order order1 = buildStuckOrder(OrderStatus.INVENTORY_RESERVING);
        Order order2 = buildStuckOrder(OrderStatus.PAYMENT_PROCESSING);
        when(orderRepository.findStuckSagas(anyList(), any(Instant.class)))
            .thenReturn(List.of(order1, order2));
        doThrow(new RuntimeException("recovery failed"))
            .when(sagaOrchestrator).handleDeadSaga(order1);

        // Should not throw — must process order2 despite order1 failure
        assertDoesNotThrow(() -> job.detectAndRecoverDeadSagas());
        verify(sagaOrchestrator).handleDeadSaga(order2);
    }
}
