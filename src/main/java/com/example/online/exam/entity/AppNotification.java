package com.example.online.exam.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "app_notifications",
        indexes = @Index(
                name = "idx_app_notification_recipient_created",
                columnList = "recipient_type, recipient_id, created_at"),
        uniqueConstraints = @UniqueConstraint(
                name = "uk_app_notification_recipient_event",
                columnNames = {"recipient_type", "recipient_id", "event_key"}))
public class AppNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_type", nullable = false, length = 20)
    private String recipientType;

    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;

    @Column(name = "event_key", nullable = false, length = 160)
    private String eventKey;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 1000)
    private String message;

    @Column(name = "target_url", nullable = false, length = 500)
    private String targetUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", length = 32)
    private NotificationType notificationType;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    protected AppNotification() {
    }

    public AppNotification(
            String recipientType,
            Long recipientId,
            String eventKey,
            String title,
            String message,
            String targetUrl) {
        this.recipientType = recipientType;
        this.recipientId = recipientId;
        this.eventKey = eventKey;
        this.title = title;
        this.message = message;
        this.targetUrl = targetUrl;
        this.notificationType = NotificationType.RESULT_PUBLISHED;
        this.referenceId = referenceIdFromEventKey(eventKey);
    }

    public AppNotification(
            Long recipientId,
            String eventKey,
            String title,
            String message,
            NotificationType notificationType,
            String targetUrl,
            Long referenceId) {
        this("STUDENT", recipientId, eventKey, title, message, targetUrl);
        this.notificationType = notificationType;
        this.referenceId = referenceId;
    }

    @PrePersist
    void setCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    private Long referenceIdFromEventKey(String value) {
        if (value == null || !value.startsWith("result-published:")) {
            return null;
        }
        try {
            return Long.valueOf(value.substring("result-published:".length()));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public Long getId() {
        return id;
    }

    public String getRecipientType() {
        return recipientType;
    }

    public Long getRecipientId() {
        return recipientId;
    }

    public String getEventKey() {
        return eventKey;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public NotificationType getNotificationType() {
        return notificationType == null
                ? NotificationType.RESULT_PUBLISHED
                : notificationType;
    }

    public Long getReferenceId() {
        return referenceId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }

    public boolean isRead() {
        return readAt != null;
    }

    public void markRead() {
        if (readAt == null) {
            readAt = LocalDateTime.now();
        }
    }

    public void markUnread() {
        readAt = null;
    }
}
