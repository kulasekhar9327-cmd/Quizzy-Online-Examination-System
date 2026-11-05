package com.example.online.exam.repository;

import com.example.online.exam.entity.AppNotification;
import com.example.online.exam.entity.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface AppNotificationRepository
        extends JpaRepository<AppNotification, Long> {

    Page<AppNotification> findByRecipientTypeAndRecipientIdOrderByCreatedAtDesc(
            String recipientType, Long recipientId, Pageable pageable);

    long countByRecipientTypeAndRecipientIdAndReadAtIsNull(
            String recipientType, Long recipientId);

    Optional<AppNotification> findByIdAndRecipientTypeAndRecipientId(
            Long id, String recipientType, Long recipientId);

    Optional<AppNotification> findByRecipientTypeAndRecipientIdAndEventKey(
            String recipientType, Long recipientId, String eventKey);

    List<AppNotification> findByRecipientTypeAndEventKey(
            String recipientType, String eventKey);

    List<AppNotification> findByRecipientTypeAndEventKeyIn(
            String recipientType, List<String> eventKeys);

    long countByEventKey(String eventKey);

    Page<AppNotification> findByNotificationTypeOrderByCreatedAtDesc(
            NotificationType notificationType, Pageable pageable);

    @Modifying
    @Query("""
            update AppNotification notification
            set notification.readAt = current_timestamp
            where notification.recipientType = :recipientType
              and notification.recipientId = :recipientId
              and notification.readAt is null
            """)
    int markAllRead(
            @Param("recipientType") String recipientType,
            @Param("recipientId") Long recipientId);
}
