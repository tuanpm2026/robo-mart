package com.robomart.order.workflow;

import com.robomart.common.saga.CheckoutSnapshot;
import com.robomart.order.entity.Order;
import com.robomart.order.entity.OutboxEvent;
import com.robomart.order.exception.OrderNotCancellableException;
import com.robomart.order.repository.OutboxEventRepository;
import com.robomart.proto.saga.OrderWorkflowCommand;
import com.robomart.proto.saga.OrderWorkflowResult;
import com.robomart.proto.saga.WorkflowOrderRequest;
import com.robomart.proto.saga.WorkflowOrderResponse;

import io.grpc.Status;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Order remains the sole owner of business status, history, outbox and command receipts. */
@Service
public class OrderWorkflowCoordinator {
    public static final String TOPIC = "order.checkout.requests.v1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final OutboxEventRepository outbox;

    @Value("${robomart.order.external-saga.enabled:false}")
    private boolean enabled;

    public OrderWorkflowCoordinator(
            JdbcTemplate jdbc, ObjectMapper mapper, OutboxEventRepository outbox) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.outbox = outbox;
    }

    /** Called inside the Order creation transaction after pricing and line insertion. */
    public void enroll(Order order, String fingerprint) {
        if (!enabled) return;
        order.setOrchestratorOwner("EXTERNAL");
        order.setOrchestratorEpoch(1);
        order.setSagaId(UUID.randomUUID());
        order.setRequestSequence(1);
        order.setRequestFingerprint(fingerprint);
        var snapshot =
                new CheckoutSnapshot(
                        order.getId().toString(),
                        order.getSagaId().toString(),
                        1,
                        order.getUserId(),
                        order.getTotalAmount().toPlainString(),
                        "USD",
                        1,
                        order.getItems().stream()
                                .map(
                                        i ->
                                                new CheckoutSnapshot.Line(
                                                        i.getProductId().toString(),
                                                        i.getQuantity()))
                                .toList());
        order.setCheckoutSnapshot(mapper.writeValueAsString(snapshot));
        request(
                order.getId(),
                order.getSagaId().toString(),
                1,
                1,
                "START",
                order.getCheckoutSnapshot(),
                "");
    }

    public static String hash(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional
    public void requestCancellation(long id, String reason) {
        Row row = load(id, true);
        if (!"EXTERNAL".equals(row.owner())) throw new IllegalStateException("Embedded order");
        if (List.of("CANCELLATION_PENDING", "CANCELLED").contains(row.status())) return;
        if (!List.of("PENDING", "CONFIRMED").contains(row.status())) {
            throw new OrderNotCancellableException(
                    "Order cannot be cancelled in state " + row.status());
        }
        String actual =
                reason == null || reason.isBlank() ? "Customer requested cancellation" : reason;
        if (actual.length() > 1000)
            throw new IllegalArgumentException("Cancellation reason too long");
        change(row, "CANCELLATION_PENDING", "", "", actual);
        request(
                id,
                row.sagaId(),
                row.epoch(),
                row.sequence() + 1,
                "CANCEL",
                row.snapshot(),
                actual);
        jdbc.update("UPDATE orders SET request_sequence=request_sequence+1 WHERE id=?", id);
    }

    /**
     * Explicit handover is restricted to quiescent paid orders; in-flight legacy work must recover
     * first.
     */
    @Transactional
    public String adoptConfirmed(long id, String actor, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 1000)
            throw new IllegalArgumentException("Handover reason required");
        Row row = load(id, true);
        if (!"EMBEDDED".equals(row.owner())
                || !"CONFIRMED".equals(row.status())
                || row.payment().isBlank()
                || row.reservation().isBlank())
            throw new IllegalArgumentException(
                    "Only a quiescent confirmed embedded order can be adopted");
        String saga = UUID.randomUUID().toString();
        long epoch = row.epoch() + 1;
        var lines =
                jdbc.query(
                        "SELECT product_id,quantity FROM order_items WHERE order_id=? ORDER BY"
                                + " product_id",
                        (rs, n) ->
                                new CheckoutSnapshot.Line(
                                        Long.toString(rs.getLong(1)), rs.getInt(2)),
                        id);
        String amount =
                jdbc.queryForObject(
                                "SELECT total_amount FROM orders WHERE id=?",
                                java.math.BigDecimal.class,
                                id)
                        .toPlainString();
        String snapshot =
                mapper.writeValueAsString(
                        new CheckoutSnapshot(
                                Long.toString(id),
                                saga,
                                epoch,
                                row.user(),
                                amount,
                                "USD",
                                1,
                                lines));
        jdbc.update(
                "UPDATE orders SET"
                    + " orchestrator_owner='EXTERNAL',orchestrator_epoch=?,saga_id=?,checkout_snapshot=?,request_sequence=1,version=version+1,updated_at=now()"
                    + " WHERE id=?",
                epoch,
                UUID.fromString(saga),
                snapshot,
                id);
        jdbc.update(
                "INSERT INTO order_workflow_handover_audit(order_id,owner_epoch,actor,reason)"
                        + " VALUES (?,?,?,?)",
                id,
                epoch,
                actor,
                reason);
        request(id, saga, epoch, 1, "START", snapshot, "");
        return saga;
    }

    @Transactional(readOnly = true)
    public WorkflowOrderResponse get(WorkflowOrderRequest q) {
        Row row = load(parse(q.getOrderId()), false);
        owned(row, q.getSagaId(), q.getOwnerEpoch());
        return WorkflowOrderResponse.newBuilder()
                .setStatus(row.status())
                .setSnapshotJson(row.snapshot())
                .setReservationId(row.reservation())
                .setPaymentId(row.payment())
                .setCancellationReason(row.reason())
                .build();
    }

    @Transactional
    public OrderWorkflowResult execute(OrderWorkflowCommand q) {
        if (q.getCommandId().isBlank() || q.getCommandId().length() > 200)
            throw new IllegalArgumentException("Invalid command ID");
        Row row = load(parse(q.getOrderId()), true);
        owned(row, q.getSagaId(), q.getOwnerEpoch());
        String hash = hash(q.toByteArray());
        var receipts =
                jdbc.query(
                        "SELECT payload_hash,applied,result_status,message FROM"
                                + " order_workflow_receipts WHERE command_id=?",
                        (rs, n) -> {
                            if (!hash.equals(rs.getString(1)))
                                throw new IllegalArgumentException(
                                        "Command has a different payload");
                            return OrderWorkflowResult.newBuilder()
                                    .setApplied(rs.getBoolean(2))
                                    .setStatus(rs.getString(3))
                                    .setMessage(rs.getString(4))
                                    .build();
                        },
                        q.getCommandId());
        if (!receipts.isEmpty()) return receipts.getFirst();
        boolean applied = false;
        String status = row.status();
        switch (q.getAction()) {
            case "CONFIRM" -> {
                if ("PENDING".equals(status)
                        && !q.getPaymentId().isBlank()
                        && !q.getReservationId().isBlank()) {
                    change(row, "CONFIRMED", q.getReservationId(), q.getPaymentId(), "");
                    applied = true;
                    status = "CONFIRMED";
                }
            }
            case "BEGIN_CANCEL" -> {
                if (List.of("PENDING", "CONFIRMED").contains(status)) {
                    change(
                            row,
                            "CANCELLATION_PENDING",
                            q.getReservationId(),
                            q.getPaymentId(),
                            q.getReason());
                    status = "CANCELLATION_PENDING";
                    applied = true;
                } else
                    applied = "CANCELLATION_PENDING".equals(status) || "CANCELLED".equals(status);
            }
            case "FINALIZE_CANCEL" -> {
                if ("CANCELLATION_PENDING".equals(status)
                        && q.getInventoryClosed()
                        && terminalMoneyProof(row, q)
                        && (row.reservation().isBlank()
                                || q.getReservationId().isBlank()
                                || row.reservation().equals(q.getReservationId()))) {
                    change(row, "CANCELLED", q.getReservationId(), q.getPaymentId(), q.getReason());
                    applied = true;
                    status = "CANCELLED";
                } else applied = "CANCELLED".equals(status);
            }
            default -> throw new IllegalArgumentException("Unknown Order command");
        }
        jdbc.update(
                "INSERT INTO"
                    + " order_workflow_receipts(command_id,order_id,owner_epoch,payload_hash,applied,result_status,message)"
                    + " VALUES (?,?,?,?,?,?,?)",
                q.getCommandId(),
                row.id(),
                row.epoch(),
                hash,
                applied,
                status,
                applied ? "" : "Business state precondition failed");
        return OrderWorkflowResult.newBuilder()
                .setApplied(applied)
                .setStatus(status)
                .setMessage(applied ? "" : "Business state precondition failed")
                .build();
    }

    private boolean terminalMoneyProof(Row row, OrderWorkflowCommand q) {
        if (List.of("NO_CHARGE_FINAL", "DECLINED_FINAL").contains(q.getChargeResolution()))
            return row.payment().isBlank() && q.getPaymentId().isBlank();
        if (!"REFUNDED".equals(q.getChargeResolution()) || q.getPaymentId().isBlank()) return false;
        return row.payment().isBlank() || row.payment().equals(q.getPaymentId());
    }

    private void change(Row row, String target, String reservation, String payment, String reason) {
        reason = reason.isBlank() ? row.reason() : reason;
        if (reason.isBlank() && "CANCELLATION_PENDING".equals(target))
            reason = "Checkout could not be completed";
        int changed =
                jdbc.update(
                        "UPDATE orders SET"
                            + " status=?,reservation_id=COALESCE(NULLIF(?,''),reservation_id),"
                            + " payment_id=COALESCE(NULLIF(?,''),payment_id),"
                            + " cancellation_reason=NULLIF(?,''),version=version+1,updated_at=now()"
                            + " WHERE id=? AND status=? AND orchestrator_owner='EXTERNAL' AND"
                            + " orchestrator_epoch=?",
                        target,
                        reservation,
                        payment,
                        reason,
                        row.id(),
                        row.status(),
                        row.epoch());
        if (changed != 1) throw new IllegalStateException("Order command lost ownership");
        jdbc.update(
                "INSERT INTO order_status_history(order_id,status) VALUES (?,?)", row.id(), target);
        if ("CANCELLED".equals(target)) {
            outbox.save(
                    new OutboxEvent(
                            "Order",
                            Long.toString(row.id()),
                            "order_cancelled",
                            mapper.writeValueAsString(
                                    Map.of(
                                            "orderId",
                                            Long.toString(row.id()),
                                            "reason",
                                            reason,
                                            "cancelledBy",
                                            row.user()))));
        } else {
            outbox.save(
                    new OutboxEvent(
                            "Order",
                            Long.toString(row.id()),
                            "order_status_changed",
                            mapper.writeValueAsString(
                                    Map.of(
                                            "orderId",
                                            Long.toString(row.id()),
                                            "previousStatus",
                                            row.status(),
                                            "newStatus",
                                            target))));
        }
    }

    private void request(
            long id,
            String saga,
            long epoch,
            long sequence,
            String type,
            String snapshot,
            String reason) {
        outbox.save(
                new OutboxEvent(
                        "Order",
                        Long.toString(id),
                        "checkout_saga_requested",
                        mapper.writeValueAsString(
                                Map.of(
                                        "requestId",
                                        saga + ":" + type,
                                        "requestType",
                                        type,
                                        "requestSequence",
                                        sequence,
                                        "orderId",
                                        Long.toString(id),
                                        "sagaId",
                                        saga,
                                        "ownerEpoch",
                                        epoch,
                                        "snapshotJson",
                                        snapshot,
                                        "reason",
                                        reason))));
    }

    private Row load(long id, boolean lock) {
        var rows =
                jdbc.query(
                        "SELECT"
                            + " id,status,orchestrator_owner,orchestrator_epoch,saga_id,checkout_snapshot,request_sequence,"
                            + " COALESCE(reservation_id,''),COALESCE(payment_id,''),COALESCE(cancellation_reason,''),user_id"
                            + " FROM orders WHERE id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        (rs, n) ->
                                new Row(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getLong(4),
                                        rs.getString(5),
                                        rs.getString(6),
                                        rs.getLong(7),
                                        rs.getString(8),
                                        rs.getString(9),
                                        rs.getString(10),
                                        rs.getString(11)),
                        id);
        if (rows.isEmpty())
            throw Status.NOT_FOUND.withDescription("Order not found").asRuntimeException();
        return rows.getFirst();
    }

    private static long parse(String value) {
        long id = Long.parseLong(value);
        if (id < 1) throw new IllegalArgumentException("Invalid order ID");
        return id;
    }

    private static void owned(Row row, String saga, long epoch) {
        if (!"EXTERNAL".equals(row.owner()) || !saga.equals(row.sagaId()) || epoch != row.epoch()) {
            throw Status.PERMISSION_DENIED
                    .withDescription("Workflow ownership mismatch")
                    .asRuntimeException();
        }
    }

    private record Row(
            long id,
            String status,
            String owner,
            long epoch,
            String sagaId,
            String snapshot,
            long sequence,
            String reservation,
            String payment,
            String reason,
            String user) {}
}
