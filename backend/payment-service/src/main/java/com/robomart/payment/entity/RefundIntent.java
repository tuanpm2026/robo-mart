package com.robomart.payment.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Durable request binding and receipt; a retry never creates another provider operation. */
@Entity
@Table(name = "refund_intents")
public class RefundIntent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "payment_id", nullable = false)
    private Long paymentId;
    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(nullable = false, length = 20)
    private String status = "PENDING";
    private UUID processingToken;
    private Instant processingUntil;
    @Column(length = 100)
    private String transactionId;

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public void setPaymentId(Long value) { paymentId = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = value; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal value) { amount = value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String value) { currency = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public UUID getProcessingToken() { return processingToken; }
    public void setProcessingToken(UUID value) { processingToken = value; }
    public Instant getProcessingUntil() { return processingUntil; }
    public void setProcessingUntil(Instant value) { processingUntil = value; }
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String value) { transactionId = value; }
}
