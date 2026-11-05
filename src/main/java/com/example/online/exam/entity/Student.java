
package com.example.online.exam.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;
import jakarta.persistence.Table;

@Entity
@Table(name = "students")
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(name = "password_recovery_token_hash", length = 64)
    private String passwordRecoveryTokenHash;

    @Column(name = "password_recovery_expires_at")
    private LocalDateTime passwordRecoveryExpiresAt;

    @Column(name = "password_recovery_window_started_at")
    private LocalDateTime passwordRecoveryWindowStartedAt;

    @Column(name = "password_recovery_requests_in_window")
    private Integer passwordRecoveryRequestsInWindow;

    @Column(name = "password_recovery_last_request_id", length = 36)
    private String passwordRecoveryLastRequestId;

    public Student() {
    }

    public Student(String fullName, String email, String password) {
        this.fullName = fullName;
        this.email = email;
        this.password = password;
    }

    public Long getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getPasswordRecoveryTokenHash() {
        return passwordRecoveryTokenHash;
    }

    public void setPasswordRecoveryTokenHash(String passwordRecoveryTokenHash) {
        this.passwordRecoveryTokenHash = passwordRecoveryTokenHash;
    }

    public LocalDateTime getPasswordRecoveryExpiresAt() {
        return passwordRecoveryExpiresAt;
    }

    public void setPasswordRecoveryExpiresAt(
            LocalDateTime passwordRecoveryExpiresAt) {
        this.passwordRecoveryExpiresAt = passwordRecoveryExpiresAt;
    }

    public LocalDateTime getPasswordRecoveryWindowStartedAt() {
        return passwordRecoveryWindowStartedAt;
    }

    public void setPasswordRecoveryWindowStartedAt(
            LocalDateTime passwordRecoveryWindowStartedAt) {
        this.passwordRecoveryWindowStartedAt = passwordRecoveryWindowStartedAt;
    }

    public Integer getPasswordRecoveryRequestsInWindow() {
        return passwordRecoveryRequestsInWindow;
    }

    public void setPasswordRecoveryRequestsInWindow(
            Integer passwordRecoveryRequestsInWindow) {
        this.passwordRecoveryRequestsInWindow =
                passwordRecoveryRequestsInWindow;
    }

    public String getPasswordRecoveryLastRequestId() {
        return passwordRecoveryLastRequestId;
    }

    public void setPasswordRecoveryLastRequestId(
            String passwordRecoveryLastRequestId) {
        this.passwordRecoveryLastRequestId = passwordRecoveryLastRequestId;
    }

    public void clearPasswordRecovery() {
        passwordRecoveryTokenHash = null;
        passwordRecoveryExpiresAt = null;
    }
}
