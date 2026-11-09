package com.robomart.saga;

import com.robomart.common.saga.CheckoutSnapshot;
import com.robomart.events.order.CheckoutSagaRequestedEvent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class SagaStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SagaStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public void accept(CheckoutSagaRequestedEvent e) {
        String json = e.getSnapshotJson().toString();
        CheckoutSnapshot s = mapper.readValue(json, CheckoutSnapshot.class);
        if (!s.orderId().contentEquals(e.getOrderId())
                || !s.sagaId().contentEquals(e.getSagaId())
                || s.epoch() != e.getOwnerEpoch()
                || s.workflowVersion() != e.getWorkflowVersion()
                || !List.of("START", "CANCEL").contains(e.getRequestType().toString())
                || e.getRequestSequence() < 1
                || e.getReason().length() > 1000
                || e.getEventId().toString().isBlank()
                || e.getEventId().length() > 100
                || !e.getRequestId().toString().equals(s.sagaId() + ":" + e.getRequestType())
                || "START".contentEquals(e.getRequestType()) && e.getRequestSequence() != 1
                || "CANCEL".contentEquals(e.getRequestType()) && e.getRequestSequence() < 2)
            throw new IllegalArgumentException("Invalid workflow envelope");
        // The lock also serializes CANCEL-before-START while the workflow row does not yet exist.
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                Object.class,
                "saga:" + s.orderId());
        String hash =
                hash(
                        (e.getRequestType()
                                        + "\n"
                                        + e.getRequestSequence()
                                        + "\n"
                                        + json
                                        + "\n"
                                        + e.getReason())
                                .getBytes(StandardCharsets.UTF_8));
        var duplicates =
                jdbc.queryForList(
                        "SELECT payload_hash FROM saga_inbox WHERE event_id=? OR request_id=?",
                        String.class,
                        e.getEventId().toString(),
                        e.getRequestId().toString());
        if (!duplicates.isEmpty()) {
            if (duplicates.stream().anyMatch(h -> !h.equals(hash)))
                throw new IllegalArgumentException("Request identity conflict");
            return;
        }
        UUID id = UUID.fromString(s.sagaId());
        jdbc.update(
                "INSERT INTO"
                    + " saga_instances(saga_id,order_id,owner_epoch,workflow_version,snapshot_json,state,request_sequence,due_at)"
                    + " VALUES (?,?,?,?,?,'RESERVE',?,now()) ON CONFLICT(order_id) DO NOTHING",
                id,
                s.orderId(),
                s.epoch(),
                s.workflowVersion(),
                json,
                e.getRequestSequence());
        var binding =
                jdbc.queryForMap(
                        "SELECT saga_id,owner_epoch,snapshot_json FROM saga_instances WHERE"
                                + " order_id=? FOR UPDATE",
                        s.orderId());
        if (!id.equals(binding.get("saga_id"))
                || ((Number) binding.get("owner_epoch")).longValue() != s.epoch()
                || !json.equals(binding.get("snapshot_json")))
            throw new IllegalArgumentException("Immutable workflow binding conflict");
        if ("CANCEL".contentEquals(e.getRequestType())) {
            jdbc.update(
                    "UPDATE saga_instances SET cancel_requested=true,reason=CASE WHEN reason=''"
                        + " THEN ? ELSE reason END, request_sequence=GREATEST(request_sequence,?),"
                        + " phase_started_at=CASE WHEN state='CHECKOUT_CONFIRMED' THEN now() ELSE"
                        + " phase_started_at END, attempts=CASE WHEN state='CHECKOUT_CONFIRMED'"
                        + " THEN 0 ELSE attempts END, state=CASE WHEN state='CHECKOUT_CONFIRMED'"
                        + " THEN 'BEGIN_CANCEL' ELSE state END,due_at=CASE WHEN manual_review_at IS"
                        + " NULL AND state<>'CANCELLED' THEN now() ELSE due_at END,updated_at=now()"
                        + " WHERE saga_id=?",
                    e.getReason().toString(),
                    e.getRequestSequence(),
                    id);
        }
        jdbc.update(
                "INSERT INTO saga_inbox(event_id,request_id,payload_hash,saga_id) VALUES (?,?,?,?)",
                e.getEventId().toString(),
                e.getRequestId().toString(),
                hash,
                id);
    }

    @Transactional
    public List<Work> claim(int limit, Duration lease) {
        UUID token = UUID.randomUUID();
        return jdbc.query(
                "UPDATE saga_instances SET claim_token=?,claim_until=now()+(? * interval '1"
                        + " millisecond'),attempts=attempts+1 WHERE saga_id IN (SELECT saga_id FROM"
                        + " saga_instances WHERE due_at<=now() AND manual_review_at IS NULL AND"
                        + " (claim_until IS NULL OR claim_until<now()) ORDER BY due_at LIMIT ? FOR"
                        + " UPDATE SKIP LOCKED) RETURNING *",
                (r, n) ->
                        new Work(
                                r.getObject("saga_id", UUID.class),
                                mapper.readValue(
                                        r.getString("snapshot_json"), CheckoutSnapshot.class),
                                r.getString("state"),
                                r.getBoolean("cancel_requested"),
                                r.getString("reason"),
                                r.getString("reservation_id"),
                                r.getString("payment_id"),
                                r.getString("charge_resolution"),
                                r.getInt("attempts"),
                                r.getTimestamp("phase_started_at").toInstant(),
                                token),
                token,
                lease.toMillis(),
                limit);
    }

    /** Persist an immutable operation intent before allowing the network side effect. */
    @Transactional
    public boolean intent(Work w, String operation, byte[] payload) {
        var locked =
                jdbc.queryForList(
                        "SELECT saga_id FROM saga_instances WHERE saga_id=? AND claim_token=? AND"
                                + " claim_until>now() FOR UPDATE",
                        w.id(),
                        w.token());
        if (locked.isEmpty()) return false;
        String hash = hash(payload);
        jdbc.update(
                "INSERT INTO saga_steps(saga_id,operation_key,payload_hash) VALUES (?,?,?) ON"
                        + " CONFLICT DO NOTHING",
                w.id(),
                w.key(operation),
                hash);
        String original =
                jdbc.queryForObject(
                        "SELECT payload_hash FROM saga_steps WHERE saga_id=? AND operation_key=?",
                        String.class,
                        w.id(),
                        w.key(operation));
        if (!hash.equals(original))
            throw new IllegalStateException("Operation payload changed: " + operation);
        return true;
    }

    @Transactional
    public boolean advance(Work w, Transition t) {
        // A cancellation arriving during a forward RPC wins the next transition, while preserving
        // its receipt.
        int count =
                jdbc.update(
                        "UPDATE saga_instances SET state=CASE WHEN cancel_requested AND ? IN"
                            + " ('RESERVE','CHARGE','CONFIRM','CHECKOUT_CONFIRMED') THEN"
                            + " 'BEGIN_CANCEL' ELSE ?"
                            + " END,reservation_id=?,payment_id=?,charge_resolution=?,reason=CASE"
                            + " WHEN reason='' THEN ? ELSE reason END,"
                            + " attempts=0,phase_started_at=now(),last_error='',claim_token=NULL,claim_until=NULL,updated_at=now(),"
                            + " due_at=CASE WHEN ? IN"
                            + " ('CHECKOUT_CONFIRMED','CANCELLED','CANCEL_REJECTED') AND"
                            + " NOT(cancel_requested AND ?='CHECKOUT_CONFIRMED') THEN NULL ELSE"
                            + " now() END WHERE saga_id=? AND claim_token=? AND claim_until>now()",
                        t.state(),
                        t.state(),
                        t.reservationId(),
                        t.paymentId(),
                        t.resolution(),
                        t.reason(),
                        t.state(),
                        t.state(),
                        w.id(),
                        w.token());
        return count == 1;
    }

    public void retry(Work w, String error, boolean manual, Duration delay) {
        jdbc.update(
                "UPDATE saga_instances SET due_at=CASE WHEN ? THEN NULL ELSE now()+(? * interval '1"
                    + " millisecond') END, manual_review_at=CASE WHEN ? THEN now() ELSE NULL"
                    + " END,last_error=?,claim_token=NULL,claim_until=NULL,updated_at=now() WHERE"
                    + " saga_id=? AND claim_token=? AND claim_until>now()",
                manual,
                delay.toMillis(),
                manual,
                error.substring(0, Math.min(1000, error.length())),
                w.id(),
                w.token());
    }

    @Transactional
    public void resume(UUID id, String actor, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 1000)
            throw new IllegalArgumentException("A bounded operator reason is required");
        int count =
                jdbc.update(
                        "UPDATE saga_instances SET"
                            + " manual_review_at=NULL,attempts=0,phase_started_at=now(),due_at=now(),updated_at=now()"
                            + " WHERE saga_id=? AND manual_review_at IS NOT NULL AND (claim_until"
                            + " IS NULL OR claim_until<now())",
                        id);
        if (count != 1)
            throw new IllegalArgumentException("Workflow is not awaiting manual review");
        jdbc.update(
                "INSERT INTO saga_operator_audit(saga_id,actor,reason) VALUES (?,?,?)",
                id,
                actor,
                reason);
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList(
                "SELECT saga_id,order_id,state,attempts,last_error,manual_review_at,updated_at FROM"
                        + " saga_instances ORDER BY updated_at DESC LIMIT 100");
    }

    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
