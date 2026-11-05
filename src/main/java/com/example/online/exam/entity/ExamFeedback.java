package com.example.online.exam.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
        name = "exam_feedback",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_exam_feedback_attempt",
                columnNames = "exam_result_id"))
public class ExamFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_result_id", nullable = false)
    private ExamResult examResult;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Column(name = "overall_rating", nullable = false)
    private int overallRating;

    @Column(name = "difficulty_rating", nullable = false)
    private int difficultyRating;

    @Column(name = "quality_rating", nullable = false)
    private int qualityRating;

    @Column(name = "experience_rating", nullable = false)
    private int experienceRating;

    @Column(name = "feedback_comment", length = 1000)
    private String comment;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    protected ExamFeedback() {
    }

    public ExamFeedback(
            ExamResult examResult,
            Student student,
            int overallRating,
            int difficultyRating,
            int qualityRating,
            int experienceRating,
            String comment) {
        this.examResult = examResult;
        this.student = student;
        this.overallRating = overallRating;
        this.difficultyRating = difficultyRating;
        this.qualityRating = qualityRating;
        this.experienceRating = experienceRating;
        this.comment = comment;
    }

    @PrePersist
    void setSubmittedAt() {
        if (submittedAt == null) {
            submittedAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public ExamResult getExamResult() {
        return examResult;
    }

    public Student getStudent() {
        return student;
    }

    public int getOverallRating() {
        return overallRating;
    }

    public int getDifficultyRating() {
        return difficultyRating;
    }

    public int getQualityRating() {
        return qualityRating;
    }

    public int getExperienceRating() {
        return experienceRating;
    }

    public String getComment() {
        return comment;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }
}
