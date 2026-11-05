package com.example.online.exam.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "app_announcement_requests")
public class AnnouncementRequest {

    @Id
    @Column(name = "request_id", nullable = false, length = 36)
    private String requestId;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "created_count", nullable = false)
    private int createdCount;

    @Column(name = "announcement_title", length = 120)
    private String title;

    @Column(name = "announcement_message", length = 1000)
    private String message;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AnnouncementRequest() {
    }

    public AnnouncementRequest(
            String requestId,
            String requestFingerprint,
            int createdCount) {
        this(requestId, requestFingerprint, createdCount, null, null);
    }

    public AnnouncementRequest(
            String requestId,
            String requestFingerprint,
            int createdCount,
            String title,
            String message) {
        this.requestId = requestId;
        this.requestFingerprint = requestFingerprint;
        this.createdCount = createdCount;
        this.title = title;
        this.message = message;
    }

    @PrePersist
    void setCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public int getCreatedCount() {
        return createdCount;
    }

    public String getRequestId() {
        return requestId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }
}
