package com.robomart.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.robomart.common.exception.ResourceNotFoundException;
import com.robomart.order.entity.Order;
import com.robomart.order.entity.OrderItem;
import com.robomart.order.entity.OrderStatusHistory;
import com.robomart.order.enums.OrderStatus;
import com.robomart.order.exception.OrderNotCancellableException;
import com.robomart.order.repository.OrderItemRepository;
import com.robomart.order.repository.OrderRepository;
import com.robomart.order.repository.OrderStatusHistoryRepository;
import com.robomart.order.saga.OrderSagaOrchestrator;
import com.robomart.order.web.OrderDetailResponse;
import com.robomart.order.web.OrderItemResponse;
import com.robomart.order.web.OrderStatusHistoryResponse;
import com.robomart.order.web.OrderSummaryResponse;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final Set<OrderStatus> CANCELLABLE_STATUSES = Set.of(OrderStatus.PENDING, OrderStatus.CONFIRMED);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final OrderSagaOrchestrator orderSagaOrchestrator;
    private final TransactionTemplate transactionTemplate;

    public OrderService(
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            OrderStatusHistoryRepository orderStatusHistoryRepository,
            OrderSagaOrchestrator orderSagaOrchestrator,
            TransactionTemplate transactionTemplate) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.orderSagaOrchestrator = orderSagaOrchestrator;
        this.transactionTemplate = transactionTemplate;
    }

    public Order createOrder(String userId, List<OrderItemRequest> items, String shippingAddress) {
        Order order = transactionTemplate.execute(status -> {
            Order newOrder = new Order();
            newOrder.setUserId(userId);
            newOrder.setStatus(OrderStatus.PENDING);
            newOrder.setShippingAddress(shippingAddress);

            BigDecimal totalAmount = items.stream()
                    .map(item -> item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            newOrder.setTotalAmount(totalAmount);

            Order savedOrder = orderRepository.save(newOrder);

            for (OrderItemRequest itemReq : items) {
                OrderItem orderItem = new OrderItem();
                orderItem.setOrder(savedOrder);
                orderItem.setProductId(Long.parseLong(itemReq.productId()));
                orderItem.setProductName(itemReq.productName());
                orderItem.setQuantity(itemReq.quantity());
                orderItem.setUnitPrice(itemReq.unitPrice());
                orderItem.setSubtotal(itemReq.unitPrice().multiply(BigDecimal.valueOf(itemReq.quantity())));
                orderItemRepository.save(orderItem);
            }

            OrderStatusHistory history = new OrderStatusHistory();
            history.setOrder(savedOrder);
            history.setStatus(OrderStatus.PENDING);
            history.setChangedAt(Instant.now());
            orderStatusHistoryRepository.save(history);

            return savedOrder;
        });

        // Load items eagerly before passing to saga (outside transaction)
        try {
            List<OrderItem> orderItems = orderItemRepository.findByOrderId(order.getId());
            order.setItems(orderItems);
            orderSagaOrchestrator.executeSaga(order);
        } catch (Exception e) {
            log.error("Unexpected error during saga execution for orderId={}: {}", order.getId(), e.getMessage(), e);
            try {
                final Long orderId = order.getId();
                transactionTemplate.execute(status -> {
                    Order fresh = orderRepository.findById(orderId).orElse(null);
                    if (fresh != null && fresh.getStatus() == OrderStatus.PENDING) {
                        fresh.setStatus(OrderStatus.CANCELLED);
                        fresh.setCancellationReason("Internal error during order processing");
                        orderRepository.save(fresh);
                    }
                    return null;
                });
            } catch (Exception innerEx) {
                log.error("Failed to cancel order {} after saga error: {}", order.getId(), innerEx.getMessage());
            }
            throw e;
        }
        return order;
    }

    public Order getOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        order.setItems(orderItemRepository.findByOrderId(orderId));
        return order;
    }

    public Page<OrderSummaryResponse> getOrdersByUser(String userId, int page, int size) {
        Page<Order> orders = orderRepository.findByUserId(
                userId, PageRequest.of(page, size, Sort.by("createdAt").descending()));
        List<Long> orderIds = orders.getContent().stream().map(Order::getId).toList();
        Map<Long, Long> itemCounts = orderIds.isEmpty() ? Map.of()
                : orderItemRepository.countsByOrderIds(orderIds).stream()
                        .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
        return orders.map(order -> new OrderSummaryResponse(
                order.getId(),
                order.getCreatedAt(),
                order.getTotalAmount(),
                order.getStatus(),
                itemCounts.getOrDefault(order.getId(), 0L).intValue(),
                order.getCancellationReason()));
    }

    public OrderDetailResponse getOrderForUser(Long orderId, String userId) {
        Order order = getOrder(orderId);
        if (!order.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Order not found: " + orderId);
        }
        List<OrderStatusHistory> history =
                orderStatusHistoryRepository.findByOrderIdOrderByChangedAtAsc(orderId);
        List<OrderItemResponse> itemResponses = order.getItems().stream()
                .map(item -> new OrderItemResponse(
                        item.getProductId(),
                        item.getProductName(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getSubtotal()))
                .toList();
        List<OrderStatusHistoryResponse> historyResponses = history.stream()
                .map(h -> new OrderStatusHistoryResponse(h.getStatus(), h.getChangedAt()))
                .toList();
        return new OrderDetailResponse(
                order.getId(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getTotalAmount(),
                order.getStatus(),
                order.getShippingAddress(),
                order.getCancellationReason(),
                itemResponses,
                historyResponses);
    }

    public void cancelOrder(Long orderId, String reason, String cancelledBy) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        if (!order.getUserId().equals(cancelledBy)) {
            throw new ResourceNotFoundException("Order not found: " + orderId);
        }

        if (!CANCELLABLE_STATUSES.contains(order.getStatus())) {
            throw new OrderNotCancellableException(
                    "Order " + orderId + " cannot be cancelled in state: " + order.getStatus());
        }

        order.setItems(orderItemRepository.findByOrderId(orderId));

        String cancelReason = (reason != null && !reason.isBlank()) ? reason : "Customer requested cancellation";

        try {
            if (order.getStatus() == OrderStatus.PENDING) {
                orderSagaOrchestrator.cancelPendingSaga(order, cancelReason, cancelledBy);
            } else {
                orderSagaOrchestrator.cancelConfirmedSaga(order, cancelReason, cancelledBy);
            }
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new OrderNotCancellableException(
                    "Order " + orderId + " cannot be cancelled in state: " + order.getStatus()
                            + " (concurrent modification — please retry)");
        }
    }

    public record OrderItemRequest(String productId, String productName, int quantity, BigDecimal unitPrice) {
    }
}
