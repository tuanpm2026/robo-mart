package com.robomart.notification.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.robomart.notification.entity.NotificationLog;
import com.robomart.notification.enums.NotificationType;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    List<NotificationLog> findByOrderId(String orderId);

    List<NotificationLog> findByRecipient(String recipient);

    List<NotificationLog> findByNotificationType(NotificationType notificationType);

    boolean existsByOrderIdAndNotificationType(String orderId, NotificationType notificationType);
}
