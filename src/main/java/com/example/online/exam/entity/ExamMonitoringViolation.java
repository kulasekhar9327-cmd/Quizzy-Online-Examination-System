package com.example.online.exam.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "exam_monitoring_violations",
        indexes = {
            @Index(
                    name = "idx_monitoring_student_attempt",
                    columnList = "student_id,attempt_id")
        },
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_monitoring_client_event",
                    columnNames = "client_event_id")
        })
public class ExamMonitoringViolation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "exam_identifier", nullable = false, length = 120)
    private String examIdentifier;

    @Column(name = "exam_subject", length = 120)
    private String examSubject;

    @Column(name = "attempt_id", nullable = false, length = 36)
    private String attemptId;

    @Column(name = "violation_type", nullable = false, length = 32)
    private String violationType;

    @Column(name = "warning_number", nullable = false)
    private int warningNumber;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "client_event_id", nullable = false, length = 36)
    private String clientEventId;

    protected ExamMonitoringViolation() {
    }

    public ExamMonitoringViolation(
            Long studentId,
            String examIdentifier,
            String examSubject,
            String attemptId,
            String violationType,
            int warningNumber,
            Instant occurredAt,
            String clientEventId) {
        this.studentId = studentId;
        this.examIdentifier = examIdentifier;
        this.examSubject = examSubject;
        this.attemptId = attemptId;
        this.violationType = violationType;
        this.warningNumber = warningNumber;
        this.occurredAt = occurredAt;
        this.clientEventId = clientEventId;
    }

    public Long getId() {
        return id;
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

    public String getAttemptId() {
        return attemptId;
    }

    public String getViolationType() {
        return violationType;
    }

    public int getWarningNumber() {
        return warningNumber;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getClientEventId() {
        return clientEventId;
    }
}
