
package com.example.online.exam.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "exam_results")
public class ExamResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long studentId;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private int score;

    @Column(nullable = false)
    private int totalQuestions;

    @Column(nullable = false)
    private int percentage;

    @Column(nullable = false)
    private LocalDateTime examDate;

    // Timing fields; nullable for existing records.
    @Column(nullable = true)
    private LocalDateTime startTime;

    @Column(nullable = true)
    private LocalDateTime completionTime;

    @Column(nullable = true)
    private Long durationSeconds;

    // Null or false means the result is not yet published.
    // Nullable keeps existing database rows compatible during migration.
    @Column(nullable = true)
    private Boolean published = false;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    public ExamResult() {
    }

    // Original constructor retained for compatibility.
    public ExamResult(
            Long studentId,
            String subject,
            int score,
            int totalQuestions,
            int percentage) {

        this(
            studentId,
            subject,
            score,
            totalQuestions,
            percentage,
            null,
            LocalDateTime.now(),
            null
        );
    }

    // Constructor including exam timing.
    public ExamResult(
            Long studentId,
            String subject,
            int score,
            int totalQuestions,
            int percentage,
            LocalDateTime startTime,
            LocalDateTime completionTime,
            Long durationSeconds) {

        this.studentId = studentId;
        this.subject = subject;
        this.score = score;
        this.totalQuestions = totalQuestions;
        this.percentage = percentage;
        this.startTime = startTime;
        this.completionTime = completionTime;
        this.durationSeconds = durationSeconds;
        this.published = false;

        this.examDate = completionTime != null
            ? completionTime
            : LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getStudentId() {
        return studentId;
    }

    public String getSubject() {
        return subject;
    }

    public int getScore() {
        return score;
    }

    public int getTotalQuestions() {
        return totalQuestions;
    }

    public int getPercentage() {
        return percentage;
    }

    public LocalDateTime getExamDate() {
        return examDate;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public LocalDateTime getCompletionTime() {
        return completionTime;
    }

    public Long getDurationSeconds() {
        return durationSeconds;
    }

    public boolean isPublished() {
        return Boolean.TRUE.equals(published);
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public boolean publishAt(LocalDateTime publicationTime) {
        if (isPublished()) {
            return false;
        }

        this.published = true;
        this.publishedAt = publicationTime;
        return true;
    }

    public void setPublished(boolean published) {
        this.published = published;
    }
}
