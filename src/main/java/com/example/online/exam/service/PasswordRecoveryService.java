package com.example.online.exam.service;

import com.example.online.exam.entity.Student;
import com.example.online.exam.repository.StudentRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class PasswordRecoveryService {

    public static final int PASSWORD_MIN_LENGTH = 8;
    public static final int PASSWORD_MAX_LENGTH = 72;
    public static final int MAX_REQUESTS_PER_WINDOW = 3;
    public static final int TOKEN_VALIDITY_MINUTES = 30;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("[A-Za-z0-9_-]{43}");

    private final StudentRepository studentRepository;
    private final PasswordEncoder passwordEncoder;

    public PasswordRecoveryService(
            StudentRepository studentRepository,
            PasswordEncoder passwordEncoder) {
        this.studentRepository = studentRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public Optional<RecoveryEmail> issueRecovery(
            String email,
            String requestId) {
        if (email == null || email.isBlank() || email.length() > 254
                || !validRequestId(requestId)) {
            return Optional.empty();
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        Student student = studentRepository
                .findByEmailForRecovery(normalizedEmail)
                .orElse(null);
        if (student == null) {
            return Optional.empty();
        }
        if (requestId.equals(student.getPasswordRecoveryLastRequestId())) {
            return Optional.empty();
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart =
                student.getPasswordRecoveryWindowStartedAt();
        Integer requestCount =
                student.getPasswordRecoveryRequestsInWindow();
        if (windowStart == null
                || !windowStart.plusHours(1).isAfter(now)
                || requestCount == null) {
            student.setPasswordRecoveryWindowStartedAt(now);
            student.setPasswordRecoveryRequestsInWindow(0);
            requestCount = 0;
        }
        if (requestCount >= MAX_REQUESTS_PER_WINDOW) {
            return Optional.empty();
        }

        String token = newToken();
        LocalDateTime expiresAt = now.plusMinutes(TOKEN_VALIDITY_MINUTES);
        student.setPasswordRecoveryRequestsInWindow(requestCount + 1);
        student.setPasswordRecoveryLastRequestId(requestId);
        student.setPasswordRecoveryTokenHash(hashToken(token));
        student.setPasswordRecoveryExpiresAt(expiresAt);
        studentRepository.saveAndFlush(student);
        return Optional.of(new RecoveryEmail(
                student.getEmail(), token, expiresAt));
    }

    @Transactional(readOnly = true)
    public boolean isTokenUsable(String token) {
        if (!validTokenFormat(token)) {
            return false;
        }
        Student student = studentRepository
                .findByPasswordRecoveryTokenHash(hashToken(token))
                .orElse(null);
        return student != null
                && student.getPasswordRecoveryExpiresAt() != null
                && student.getPasswordRecoveryExpiresAt().isAfter(
                        LocalDateTime.now());
    }

    @Transactional
    public boolean resetPassword(
            String token,
            String password,
            String confirmation) {
        if (!validTokenFormat(token)
                || !validPassword(password)
                || !password.equals(confirmation)) {
            return false;
        }
        Student student = studentRepository
                .findByPasswordRecoveryTokenHash(hashToken(token))
                .orElse(null);
        if (student == null
                || student.getPasswordRecoveryExpiresAt() == null
                || !student.getPasswordRecoveryExpiresAt().isAfter(
                        LocalDateTime.now())) {
            return false;
        }

        student.setPassword(passwordEncoder.encode(password));
        student.clearPasswordRecovery();
        studentRepository.saveAndFlush(student);
        return true;
    }

    public boolean validPassword(String password) {
        return password != null
                && password.length() >= PASSWORD_MIN_LENGTH
                && password.length() <= PASSWORD_MAX_LENGTH
                && password.getBytes(StandardCharsets.UTF_8).length
                        <= PASSWORD_MAX_LENGTH;
    }

    private boolean validRequestId(String requestId) {
        if (requestId == null) {
            return false;
        }
        try {
            return UUID.fromString(requestId).toString().equals(requestId);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean validTokenFormat(String token) {
        return token != null && TOKEN_PATTERN.matcher(token).matches();
    }

    public static String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable.", exception);
        }
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record RecoveryEmail(
            String recipient,
            String token,
            LocalDateTime expiresAt) {
    }
}
