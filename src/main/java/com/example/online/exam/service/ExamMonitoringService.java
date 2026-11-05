package com.example.online.exam.service;

import com.example.online.exam.entity.ExamMonitoringViolation;
import com.example.online.exam.entity.ExamMonitoringAttempt.Status;
import com.example.online.exam.entity.ExamMonitoringAttempt;
import com.example.online.exam.repository.ExamMonitoringAttemptRepository;
import com.example.online.exam.repository.ExamMonitoringViolationRepository;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ExamMonitoringService {

    public static final int MAX_WARNINGS = 5;

    private final ExamMonitoringViolationRepository violationRepository;
    private final ExamMonitoringAttemptRepository attemptRepository;

    public ExamMonitoringService(
            ExamMonitoringViolationRepository violationRepository,
            ExamMonitoringAttemptRepository attemptRepository) {
        this.violationRepository = violationRepository;
        this.attemptRepository = attemptRepository;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void registerAttempt(ExamAttemptSession.ActiveAttempt attempt) {
        if (attempt == null) {
            return;
        }
        Optional<ExamMonitoringAttempt> existing =
                attemptRepository.findById(attempt.attemptId());
        if (existing.isPresent()) {
            verifyRegisteredAttempt(existing.get(), attempt);
            return;
        }
        try {
            attemptRepository.saveAndFlush(new ExamMonitoringAttempt(
                    attempt.attemptId(),
                    attempt.studentId(),
                    attempt.examIdentifier(),
                    attempt.examSubject(),
                    attempt.startedAt(),
                    attempt.deadline()));
        } catch (DataIntegrityViolationException exception) {
            ExamMonitoringAttempt concurrent =
                    attemptRepository.findById(attempt.attemptId())
                            .orElseThrow(() -> exception);
            verifyRegisteredAttempt(concurrent, attempt);
        }
    }

    @Transactional
    public Optional<ExamMonitoringAttempt> lockAttemptForSubmission(
            String attemptId,
            Long studentId) {
        if (attemptId == null || studentId == null) {
            return Optional.empty();
        }
        return attemptRepository.findByAttemptIdForUpdate(attemptId)
                .filter(attempt -> attempt.getStudentId().equals(studentId));
    }

    @Transactional(readOnly = true)
    public Optional<ExamMonitoringAttempt> getAttempt(
            String attemptId,
            Long studentId) {
        if (attemptId == null || studentId == null) {
            return Optional.empty();
        }
        return attemptRepository.findById(attemptId)
                .filter(attempt -> attempt.getStudentId().equals(studentId));
    }

    @Transactional
    public void finishAttempt(String attemptId, Status status) {
        if (attemptId == null) {
            return;
        }
        attemptRepository.findByAttemptIdForUpdate(attemptId)
                .ifPresent(attempt -> attempt.finish(status));
    }

    @Transactional
    public RecordResult recordViolation(
            Long studentId,
            ExamAttemptSession.ActiveAttempt attempt,
            String clientEventId,
            String violationType) {
        Optional<ExamMonitoringAttempt> attemptRecord =
                attemptRepository.findByAttemptIdForUpdate(
                        attempt.attemptId());
        if (attemptRecord.isEmpty()) {
            return new RecordResult(
                    Outcome.ATTEMPT_NOT_FOUND,
                    null,
                    0,
                    0,
                    null);
        }
        ExamMonitoringAttempt persistedAttempt = attemptRecord.get();
        if (!persistedAttempt.getStudentId().equals(studentId)
                || !persistedAttempt.getAttemptId()
                        .equals(attempt.attemptId())
                || !persistedAttempt.getExamIdentifier()
                        .equals(attempt.examIdentifier())) {
            return new RecordResult(
                    Outcome.ATTEMPT_NOT_FOUND,
                    null,
                    0,
                    0,
                    persistedAttempt.getStatus());
        }

        Optional<ExamMonitoringViolation> existing =
                violationRepository.findByClientEventId(clientEventId);

        if (existing.isPresent()) {
            ExamMonitoringViolation prior = existing.get();
            if (matches(prior, studentId, attempt, violationType)) {
                return new RecordResult(
                        Outcome.DUPLICATE,
                        prior,
                        prior.getWarningNumber(),
                        warningCount(studentId, attempt.attemptId()),
                        persistedAttempt.getStatus());
            }
            return new RecordResult(
                    Outcome.CONFLICT,
                    null,
                    0,
                    warningCount(studentId, attempt.attemptId()),
                    persistedAttempt.getStatus());
        }

        if (persistedAttempt.getStatus() == Status.DISQUALIFIED) {
            return new RecordResult(
                    Outcome.DISQUALIFIED,
                    null,
                    MAX_WARNINGS,
                    MAX_WARNINGS,
                    Status.DISQUALIFIED);
        }
        if (persistedAttempt.getStatus() != Status.ACTIVE) {
            return new RecordResult(
                    Outcome.ATTEMPT_ENDED,
                    null,
                    persistedAttempt.getWarningCount(),
                    persistedAttempt.getWarningCount(),
                    persistedAttempt.getStatus());
        }
        if (!LocalDateTime.now().isBefore(persistedAttempt.getDeadline())) {
            persistedAttempt.finish(Status.EXPIRED);
            return new RecordResult(
                    Outcome.ATTEMPT_ENDED,
                    null,
                    persistedAttempt.getWarningCount(),
                    persistedAttempt.getWarningCount(),
                    Status.EXPIRED);
        }

        int currentCount = warningCount(studentId, attempt.attemptId());
        if (currentCount >= MAX_WARNINGS) {
            persistedAttempt.disqualify(Instant.now());
            return new RecordResult(
                    Outcome.DISQUALIFIED,
                    null,
                    MAX_WARNINGS,
                    MAX_WARNINGS,
                    Status.DISQUALIFIED);
        }

        int warningNumber = currentCount + 1;
        Instant occurredAt = Instant.now();
        ExamMonitoringViolation violation = new ExamMonitoringViolation(
                studentId,
                attempt.examIdentifier(),
                attempt.examSubject(),
                attempt.attemptId(),
                violationType,
                warningNumber,
                occurredAt,
                clientEventId);
        violationRepository.saveAndFlush(violation);
        persistedAttempt.acceptWarning(warningNumber);

        return new RecordResult(
                Outcome.ACCEPTED,
                violation,
                warningNumber,
                warningNumber,
                persistedAttempt.getStatus());
    }

    @Transactional(readOnly = true)
    public List<ExamMonitoringViolation> getViolations(
            Long studentId,
            String attemptId) {
        return violationRepository
                .findByStudentIdAndAttemptIdOrderByOccurredAtAscIdAsc(
                        studentId,
                        attemptId);
    }

    @Transactional(readOnly = true)
    public int warningCount(Long studentId, String attemptId) {
        return (int) Math.min(
                MAX_WARNINGS,
                violationRepository.countByStudentIdAndAttemptId(
                        studentId,
                        attemptId));
    }

    @Transactional(readOnly = true)
    public Optional<ExamMonitoringViolation> findByClientEventId(
            String clientEventId) {
        return violationRepository.findByClientEventId(clientEventId);
    }

    @Transactional(readOnly = true)
    public List<ExamMonitoringAttempt> getDisqualifiedAttempts() {
        return attemptRepository
                .findByStatusAndDisqualifiedAtIsNotNullOrderByDisqualifiedAtDesc(
                        Status.DISQUALIFIED);
    }

    private boolean matches(
            ExamMonitoringViolation prior,
            Long studentId,
            ExamAttemptSession.ActiveAttempt attempt,
            String violationType) {
        return prior.getStudentId().equals(studentId)
                && prior.getAttemptId().equals(attempt.attemptId())
                && prior.getViolationType().equals(violationType);
    }

    private void verifyRegisteredAttempt(
            ExamMonitoringAttempt persisted,
            ExamAttemptSession.ActiveAttempt attempt) {
        if (!persisted.getStudentId().equals(attempt.studentId())
                || !persisted.getExamIdentifier()
                        .equals(attempt.examIdentifier())) {
            throw new IllegalStateException(
                    "Attempt identifier is already registered to another owner.");
        }
    }

    public enum Outcome {
        ACCEPTED,
        DUPLICATE,
        CONFLICT,
        DISQUALIFIED,
        ATTEMPT_ENDED,
        ATTEMPT_NOT_FOUND
    }

    public record RecordResult(
            Outcome outcome,
            ExamMonitoringViolation violation,
            int warningNumber,
            int warningCount,
            Status attemptStatus) {
    }
}
