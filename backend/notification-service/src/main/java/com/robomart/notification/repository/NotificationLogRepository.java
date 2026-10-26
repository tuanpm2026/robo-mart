package com.robomart.notification.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.robomart.notification.entity.NotificationLog;
import com.robomart.notification.enums.NotificationStatus;
import com.robomart.notification.enums.NotificationType;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    List<NotificationLog> findByOrderId(String orderId);

    List<NotificationLog> findByRecipient(String recipient);

    List<NotificationLog> findByNotificationType(NotificationType notificationType);

    Optional<NotificationLog> findByOrderIdAndNotificationType(String orderId, NotificationType notificationType);

    /**
     * Atomically re-claims a notification for another delivery attempt: only a FAILED row, or a
     * PENDING row whose claim was last touched before {@code staleBefore} (its sender crashed or
     * lost the race to record the outcome), moves back to PENDING. The row lock taken by the UPDATE
     * serializes concurrent re-claimers, so at most one of them gets {@code 1}.
     *
     * @return 1 when this caller now owns the claim, 0 otherwise (SENT, or a fresh PENDING claim)
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE NotificationLog n
               SET n.status = com.robomart.notification.enums.NotificationStatus.PENDING,
                   n.attempts = n.attempts + 1,
                   n.errorMessage = null,
                   n.updatedAt = :now
             WHERE n.id = :id
               AND (n.status = com.robomart.notification.enums.NotificationStatus.FAILED
                    OR (n.status = com.robomart.notification.enums.NotificationStatus.PENDING
                        AND n.updatedAt < :staleBefore))
            """)
    int reclaim(@Param("id") Long id, @Param("now") Instant now, @Param("staleBefore") Instant staleBefore);

    @Modifying
    @Transactional
    @Query("""
            UPDATE NotificationLog n
               SET n.status = :status, n.errorMessage = :errorMessage, n.updatedAt = :now
             WHERE n.id = :id
            """)
    int updateStatus(@Param("id") Long id, @Param("status") NotificationStatus status,
                     @Param("errorMessage") String errorMessage, @Param("now") Instant now);
}
