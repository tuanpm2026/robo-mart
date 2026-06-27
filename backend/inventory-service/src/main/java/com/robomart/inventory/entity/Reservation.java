package com.robomart.inventory.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.robomart.inventory.enums.ReservationStatus;

/**
 * Per-order inventory reservation record. The {@code orderId} unique constraint is the idempotency
 * key: a reserve gRPC call that is retried (or replayed after a saga-step timeout) finds the
 * existing row and replays the original {@code reservationId} instead of decrementing stock again,
 * and a release call short-circuits once the row is {@link ReservationStatus#RELEASED}.
 */
@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false, unique = true, length = 36)
    private String reservationId;

    @Column(name = "order_id", nullable = false, unique = true, length = 100)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Reservation() {
    }

    public Reservation(String reservationId, String orderId, ReservationStatus status) {
        this.reservationId = reservationId;
        this.orderId = orderId;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public String getReservationId() {
        return reservationId;
    }

    public String getOrderId() {
        return orderId;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public void setStatus(ReservationStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
