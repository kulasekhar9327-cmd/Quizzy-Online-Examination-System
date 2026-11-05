package com.example.online.exam.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "student_notification_preferences")
public class StudentNotificationPreference {

    @Id
    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "exam_email_enabled", nullable = false)
    private boolean examEmailEnabled = true;

    @Column(name = "result_email_enabled", nullable = false)
    private boolean resultEmailEnabled = true;

    @Column(name = "support_email_enabled", nullable = false)
    private boolean supportEmailEnabled = true;

    @Column(name = "announcement_email_enabled", nullable = false)
    private boolean announcementEmailEnabled = true;

    @Column(name = "in_app_enabled", nullable = false)
    private boolean inAppEnabled = true;

    protected StudentNotificationPreference() {
    }

    public StudentNotificationPreference(Long studentId) {
        this.studentId = studentId;
    }

    public void update(
            boolean examEmailEnabled,
            boolean resultEmailEnabled,
            boolean supportEmailEnabled,
            boolean announcementEmailEnabled,
            boolean inAppEnabled) {
        this.examEmailEnabled = examEmailEnabled;
        this.resultEmailEnabled = resultEmailEnabled;
        this.supportEmailEnabled = supportEmailEnabled;
        this.announcementEmailEnabled = announcementEmailEnabled;
        this.inAppEnabled = inAppEnabled;
    }

    public Long getStudentId() {
        return studentId;
    }

    public boolean isExamEmailEnabled() {
        return examEmailEnabled;
    }

    public boolean isResultEmailEnabled() {
        return resultEmailEnabled;
    }

    public boolean isSupportEmailEnabled() {
        return supportEmailEnabled;
    }

    public boolean isAnnouncementEmailEnabled() {
        return announcementEmailEnabled;
    }

    public boolean isInAppEnabled() {
        return inAppEnabled;
    }
}
