package com.example.online.exam.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Check;

import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "exam_monitoring_attempts",
        indexes = {
            @Index(
                    name = "idx_monitor_attempt_status_disqualified",
                    columnList = "status,disqualified_at")
        })
@Check(
        name = "ck_monitor_attempt_warning_count",
        constraints = "warning_count >= 0 AND warning_count <= 5")
public class ExamMonitoringAttempt {

    public static final String DISQUALIFICATION_REASON =
            "The attempt reached the server-confirmed monitoring warning limit.";

    @Id
    @Column(name = "attempt_id", nullable = false, length = 36)
    private String attemptId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "exam_identifier", nullable = false, length = 120)
    private String examIdentifier;

    @Column(name = "exam_subject", length = 120)
    private String examSubject;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "deadline", nullable = false)
    private LocalDateTime deadline;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "warning_count", nullable = false)
    private int warningCount;

    @Column(name = "disqualified_at")
    private Instant disqualifiedAt;

    @Column(name = "disqualification_reason", length = 255)
    private String disqualificationReason;

    protected ExamMonitoringAttempt() {
    }

    public ExamMonitoringAttempt(
            String attemptId,
            Long studentId,
            String examIdentifier,
            String examSubject,
            LocalDateTime startedAt,
            LocalDateTime deadline) {
        this.attemptId = attemptId;
        this.studentId = studentId;
        this.examIdentifier = examIdentifier;
        this.examSubject = examSubject;
        this.startedAt = startedAt;
        this.deadline = deadline;
        this.status = Status.ACTIVE;
        this.warningCount = 0;
    }

    public void acceptWarning(int warningNumber) {
        if (status != Status.ACTIVE
                || warningNumber != warningCount + 1
                || warningNumber > 5) {
            throw new IllegalStateException(
                    "Warning progression is not valid for this attempt.");
        }
        warningCount = warningNumber;
        if (warningCount == 5) {
            disqualify(Instant.now());
        }
    }

    public void disqualify(Instant timestamp) {
        if (status != Status.ACTIVE) {
            return;
        }
        warningCount = 5;
        status = Status.DISQUALIFIED;
        disqualifiedAt = timestamp;
        disqualificationReason = DISQUALIFICATION_REASON;
    }

    public void finish(Status terminalStatus) {
        if (status == Status.ACTIVE && terminalStatus != Status.ACTIVE) {
            status = terminalStatus;
        }
    }

    public String getAttemptId() {
        return attemptId;
    }

    public Long getStudentId() {
        return studentId;
    }

    public String getExamIdentifier() {
        return examIdentifier;
    }

    public String getExamSubject() {
        return examSubject;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getDeadline() {
        return deadline;
    }

    public Status getStatus() {
        return status;
    }

    public int getWarningCount() {
        return warningCount;
    }

    public Instant getDisqualifiedAt() {
        return disqualifiedAt;
    }

    public String getDisqualificationReason() {
        return disqualificationReason;
    }

    public enum Status {
        ACTIVE,
        SUBMITTED,
        CANCELLED,
        EXPIRED,
        DISQUALIFIED
    }
}
