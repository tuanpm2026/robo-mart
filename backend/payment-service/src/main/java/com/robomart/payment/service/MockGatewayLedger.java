package com.robomart.payment.service;

import com.robomart.payment.exception.PaymentDeclinedException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.function.Supplier;

import javax.sql.DataSource;

/** Durable provider simulator: settlement survives rollback/restart of the caller's transaction. */
@Service
public class MockGatewayLedger {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate settlementTransaction;

    public MockGatewayLedger(@Qualifier("mockGatewayDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        settlementTransaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        settlementTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public GatewayResult settle(String operationKey, BigDecimal amount, String currency, String identity,
            Supplier<GatewayResult> operation) {
        return settle(operationKey, amount, currency, identity, null, operation);
    }

    public GatewayResult settle(String operationKey, BigDecimal amount, String currency, String identity,
            String orderId, Supplier<GatewayResult> operation) {
        Settlement settled =
                settlementTransaction.execute(
                        status -> {
                            // Distinct namespace from PaymentService's outer order/key locks
                            // prevents self-deadlock.
                            lockOrderBinding(orderId, operationKey);
                            if (orderId != null && !orderId.isBlank()) {
                                var closed =
                                        jdbc.queryForList(
                                                "SELECT order_id FROM mock_gateway_charge_closures"
                                                    + " WHERE order_id=?",
                                                String.class,
                                                orderId);
                                if (!closed.isEmpty())
                                    throw new PaymentDeclinedException(
                                            "Charge admission permanently closed");
                            }
                            jdbc.queryForObject(
                                    "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 0))",
                                    Integer.class,
                                    "mock-provider:" + operationKey);
                            var existing =
                                    jdbc.query(
                                            """
                                            SELECT amount, currency, request_identity, status, transaction_id, failure_message
                                            FROM mock_gateway_operations WHERE operation_key = ?
                                            """,
                                            (rs, row) -> {
                                                if (rs.getBigDecimal("amount").compareTo(amount)
                                                                != 0
                                                        || !rs.getString("currency")
                                                                .equals(currency)
                                                        || !rs.getString("request_identity")
                                                                .equals(identity)) {
                                                    throw new IllegalArgumentException(
                                                            "Gateway idempotency key has a"
                                                                + " different request");
                                                }
                                                return new Settlement(
                                                        rs.getString("status").equals("DECLINED")
                                                                ? null
                                                                : new GatewayResult(
                                                                        rs.getString(
                                                                                "transaction_id"),
                                                                        rs.getString("status")),
                                                        rs.getString("failure_message"));
                                            },
                                            operationKey);
                            if (!existing.isEmpty()) {
                                return existing.getFirst();
                            }
                            GatewayResult result = null;
                            String decline = null;
                            try {
                                result = operation.get();
                            } catch (PaymentDeclinedException e) {
                                if (operationKey.startsWith("refund:")) {
                                    throw e; // Preserve the existing refund recovery path after a
                                    // provider rejection.
                                }
                                decline = e.getMessage();
                            }
                            jdbc.update(
                                    """
                                    INSERT INTO mock_gateway_operations
                                    (operation_key, amount, currency, request_identity, status, transaction_id,
                                     failure_message, request_order_id)
                                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                    """,
                                    operationKey,
                                    amount,
                                    currency,
                                    identity,
                                    result == null ? "DECLINED" : result.status(),
                                    result == null ? null : result.transactionId(),
                                    decline,
                                    orderId == null || orderId.isBlank() ? null : orderId);
                            return new Settlement(result, decline);
                        });
        if (settled == null) {
            throw new IllegalStateException("Gateway settlement returned no result");
        }
        if (settled.result() == null) {
            throw new PaymentDeclinedException(settled.decline());
        }
        return settled.result();
    }

    /**
     * Provider lock serializes closure with even a caller paused after acquiring an application
     * intent.
     */
    public ChargeReceipt chargeOutcome(
            String order,
            String key,
            BigDecimal amount,
            String currency,
            String user,
            boolean close) {
        String operation = "charge:" + key;
        String identity = order.length() + ":" + order + user.length() + ":" + user;
        return settlementTransaction.execute(
                status -> {
                    jdbc.execute("SET LOCAL lock_timeout='1s'");
                    lockOrderBinding(order, operation);
                    var receipts =
                            jdbc.query(
                                    "SELECT amount,currency,request_identity,status,transaction_id "
                                            + "FROM mock_gateway_operations WHERE operation_key=?",
                                    (rs, row) -> {
                                        validateBinding(
                                                amount,
                                                currency,
                                                identity,
                                                rs.getBigDecimal(1),
                                                rs.getString(2),
                                                rs.getString(3));
                                        return new ChargeReceipt(
                                                "COMPLETED".equals(rs.getString(4))
                                                        ? "SUCCEEDED"
                                                        : "DECLINED_FINAL",
                                                rs.getString(5));
                                    },
                                    operation);
                    if (!receipts.isEmpty()) return receipts.getFirst();
                    var closed =
                            jdbc.query(
                                    "SELECT operation_key,amount,currency,request_identity "
                                            + "FROM mock_gateway_charge_closures WHERE order_id=?",
                                    (rs, row) -> {
                                        if (!operation.equals(rs.getString(1)))
                                            throw new IllegalArgumentException(
                                                    "Closed order key mismatch");
                                        validateBinding(
                                                amount,
                                                currency,
                                                identity,
                                                rs.getBigDecimal(2),
                                                rs.getString(3),
                                                rs.getString(4));
                                        return new ChargeReceipt("NO_CHARGE_FINAL", null);
                                    },
                                    order);
                    if (!closed.isEmpty()) return closed.getFirst();
                    if (!close) return new ChargeReceipt("UNKNOWN", null);
                    jdbc.update(
                            "INSERT INTO"
                                + " mock_gateway_charge_closures(order_id,operation_key,amount,currency,request_identity)"
                                + " VALUES (?,?,?,?,?)",
                            order,
                            operation,
                            amount,
                            currency,
                            identity);
                    return new ChargeReceipt("NO_CHARGE_FINAL", null);
                });
    }

    /**
     * Absence remains unknown; a committed positive receipt can fence a stalled application
     * finalizer.
     */
    public java.util.Optional<GatewayResult> refundReceipt(
            String transactionId, String key, BigDecimal amount) {
        var results =
                jdbc.query(
                        "SELECT amount,currency,request_identity,status,transaction_id FROM"
                            + " mock_gateway_operations WHERE operation_key=?",
                        (rs, n) -> {
                            validateBinding(
                                    amount,
                                    "",
                                    transactionId,
                                    rs.getBigDecimal(1),
                                    rs.getString(2),
                                    rs.getString(3));
                            if (!"REFUNDED".equals(rs.getString(4)))
                                throw new IllegalStateException("Unknown provider refund status");
                            return new GatewayResult(rs.getString(5), rs.getString(4));
                        },
                        "refund:" + transactionId + ":" + key);
        return results.stream().findFirst();
    }

    private static void validateBinding(
            BigDecimal amount,
            String currency,
            String identity,
            BigDecimal storedAmount,
            String storedCurrency,
            String storedIdentity) {
        if (amount.compareTo(storedAmount) != 0
                || !currency.equals(storedCurrency)
                || !identity.equals(storedIdentity)) {
            throw new IllegalArgumentException(
                    "Provider operation has a different request binding");
        }
    }

    public record ChargeReceipt(String state, String transactionId) {}

    private void lockOrderBinding(String orderId, String operationKey) {
        if (orderId == null || orderId.isBlank()) {
            return;
        }
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 0))",
                Integer.class, "mock-provider:order:" + orderId);
        var boundKeys =
                jdbc.queryForList(
                        "SELECT operation_key FROM mock_gateway_operations WHERE request_order_id ="
                            + " ?",
                        String.class,
                        orderId);
        if (!boundKeys.isEmpty() && !boundKeys.getFirst().equals(operationKey)) {
            throw new IllegalArgumentException(
                    "Order is already bound to another gateway idempotency key");
        }
    }

    private record Settlement(GatewayResult result, String decline) {
    }
}
