package com.example.online.exam.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "app_announcement_deliveries",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_announcement_delivery_request_student",
                columnNames = {"request_id", "student_id"}))
public class AnnouncementDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private AnnouncementRequest announcementRequest;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AnnouncementDeliveryStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "failure_reason", length = 200)
    private String failureReason;

    @Column(name = "announcement_title", length = 120)
    private String title;

    @Column(name = "announcement_message", length = 1000)
    private String message;

    protected AnnouncementDelivery() {
    }

    public AnnouncementDelivery(
            AnnouncementRequest announcementRequest,
            Long studentId) {
        this(announcementRequest, studentId, null, null);
    }

    public AnnouncementDelivery(
            AnnouncementRequest announcementRequest,
            Long studentId,
            String title,
            String message) {
        this.announcementRequest = announcementRequest;
        this.studentId = studentId;
        this.status = AnnouncementDeliveryStatus.PENDING;
        this.title = title;
        this.message = message;
    }

    @PrePersist
    void setCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getRequestId() {
        return announcementRequest.getRequestId();
    }

    public Long getStudentId() {
        return studentId;
    }

    public AnnouncementDeliveryStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getLastAttemptAt() {
        return lastAttemptAt;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }
}
