
package com.example.online.exam.service;

import jakarta.servlet.http.HttpSession;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ExamAttemptSession {

    private static final String ATTEMPTS = "examMonitoringAttempts";
    private static final String ACTIVE_SLOTS = "examMonitoringActiveSlots";
    private static final int MAX_TERMINAL_ATTEMPTS = 40;

    private ExamAttemptSession() {
    }

    public static String start(
            HttpSession session,
            String slot,
            Long studentId,
            String examIdentifier,
            String examSubject,
            LocalDateTime startedAt,
            long durationSeconds) {
        if (slot == null || slot.isBlank()
                || studentId == null || studentId <= 0
                || examIdentifier == null || examIdentifier.isBlank()
                || startedAt == null || durationSeconds <= 0) {
            throw new IllegalArgumentException("Invalid exam attempt state.");
        }

        synchronized (session) {
            Map<String, ActiveAttempt> attempts = attempts(session, true);
            Map<String, String> activeSlots = activeSlots(session, true);
            String previousId = activeSlots.get(slot);
            updateStatus(attempts, previousId, AttemptStatus.CANCELLED);

            String attemptId = UUID.randomUUID().toString();
            ActiveAttempt attempt = new ActiveAttempt(
                    attemptId,
                    slot,
                    studentId,
                    examIdentifier,
                    examSubject,
                    startedAt,
                    startedAt.plusSeconds(durationSeconds),
                    AttemptStatus.ACTIVE);
            attempts.put(attemptId, attempt);
            activeSlots.put(slot, attemptId);
            trimTerminalAttempts(attempts, activeSlots);
            return attemptId;
        }
    }

    public static String ensure(
            HttpSession session,
            String slot,
            Long studentId,
            String examIdentifier,
            String examSubject,
            LocalDateTime startedAt,
            long durationSeconds) {
        synchronized (session) {
            Map<String, ActiveAttempt> attempts = attempts(session, true);
            Map<String, String> activeSlots = activeSlots(session, true);
            String attemptId = activeSlots.get(slot);

            if (attemptId != null) {
                ActiveAttempt attempt = attempts.get(attemptId);
                if (matches(
                        attempt,
                        slot,
                        studentId,
                        examIdentifier,
                        examSubject)) {
                    return attemptId;
                }
                return null;
            }

            return start(
                    session,
                    slot,
                    studentId,
                    examIdentifier,
                    examSubject,
                    startedAt,
                    durationSeconds);
        }
    }

    public static ActiveAttempt get(
            HttpSession session,
            String attemptId) {
        if (!isUuid(attemptId)) {
            return null;
        }
        synchronized (session) {
            return attempts(session, false).get(attemptId);
        }
    }

    public static ActiveAttempt getForSlot(
            HttpSession session,
            String slot) {
        synchronized (session) {
            String attemptId = activeSlots(session, false).get(slot);
            return attemptId == null
                    ? null
                    : attempts(session, false).get(attemptId);
        }
    }

    public static void expire(
            HttpSession session,
            String attemptId) {
        synchronized (session) {
            updateStatus(
                    attempts(session, false),
                    attemptId,
                    AttemptStatus.EXPIRED);
        }
    }

    public static void finish(
            HttpSession session,
            String slot,
            String attemptId,
            AttemptStatus status) {
        if (status == AttemptStatus.ACTIVE) {
            throw new IllegalArgumentException(
                    "A completed attempt needs a terminal status.");
        }
        synchronized (session) {
            Map<String, ActiveAttempt> attempts = attempts(session, true);
            updateStatus(attempts, attemptId, status);
            Map<String, String> activeSlots = activeSlots(session, true);
            if (status != AttemptStatus.DISQUALIFIED
                    && attemptId != null
                    && attemptId.equals(activeSlots.get(slot))) {
                activeSlots.remove(slot);
            }
            trimTerminalAttempts(attempts, activeSlots);
        }
    }

    public static void finish(
            HttpSession session,
            String slot,
            AttemptStatus status) {
        synchronized (session) {
            Map<String, String> activeSlots = activeSlots(session, false);
            String attemptId = activeSlots.get(slot);
            finish(session, slot, attemptId, status);
        }
    }

    public static Long studentId(HttpSession session) {
        // A session may not exist for an anonymous request.
        if (session == null) {
            return null;
        }

        Object value = session.getAttribute("studentId");
        if (value == null) {
            return null;
        }
        try {
            long studentId = Long.parseLong(value.toString());
            return studentId > 0 ? studentId : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public static boolean isAcceptingMonitoring(
            ActiveAttempt attempt,
            Long studentId,
            LocalDateTime now) {
        return attempt != null
                && attempt.status() == AttemptStatus.ACTIVE
                && attempt.studentId().equals(studentId)
                && now.isBefore(attempt.deadline());
    }

    public static boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return false;
        }
    }

    private static boolean matches(
            ActiveAttempt attempt,
            String slot,
            Long studentId,
            String examIdentifier,
            String examSubject) {
        return attempt != null
                && attempt.status() == AttemptStatus.ACTIVE
                && attempt.slot().equals(slot)
                && attempt.studentId().equals(studentId)
                && attempt.examIdentifier().equals(examIdentifier)
                && java.util.Objects.equals(
                        attempt.examSubject(), examSubject);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ActiveAttempt> attempts(
            HttpSession session,
            boolean create) {
        Object value = session.getAttribute(ATTEMPTS);
        if (value == null && create) {
            Map<String, ActiveAttempt> attempts = new LinkedHashMap<>();
            session.setAttribute(ATTEMPTS, attempts);
            return attempts;
        }
        if (value instanceof Map<?, ?>) {
            return (Map<String, ActiveAttempt>) value;
        }
        if (value == null) {
            return Map.of();
        }
        throw new IllegalStateException("Invalid session attempt registry.");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> activeSlots(
            HttpSession session,
            boolean create) {
        Object value = session.getAttribute(ACTIVE_SLOTS);
        if (value == null && create) {
            Map<String, String> activeSlots = new LinkedHashMap<>();
            session.setAttribute(ACTIVE_SLOTS, activeSlots);
            return activeSlots;
        }
        if (value instanceof Map<?, ?>) {
            return (Map<String, String>) value;
        }
        if (value == null) {
            return Map.of();
        }
        throw new IllegalStateException("Invalid session attempt slots.");
    }

    private static void updateStatus(
            Map<String, ActiveAttempt> attempts,
            String attemptId,
            AttemptStatus status) {
        if (attemptId == null) {
            return;
        }
        ActiveAttempt attempt = attempts.get(attemptId);
        if (attempt != null && attempt.status() == AttemptStatus.ACTIVE) {
            attempts.put(attemptId, attempt.withStatus(status));
        }
    }

    private static void trimTerminalAttempts(
            Map<String, ActiveAttempt> attempts,
            Map<String, String> activeSlots) {
        long terminalCount = attempts.values().stream()
                .filter(attempt -> attempt.status() != AttemptStatus.ACTIVE)
                .count();
        if (terminalCount <= MAX_TERMINAL_ATTEMPTS) {
            return;
        }

        Iterator<Map.Entry<String, ActiveAttempt>> iterator =
                attempts.entrySet().iterator();
        while (iterator.hasNext() && terminalCount > MAX_TERMINAL_ATTEMPTS) {
            Map.Entry<String, ActiveAttempt> entry = iterator.next();
            if (entry.getValue().status() != AttemptStatus.ACTIVE) {
                activeSlots.values().removeIf(entry.getKey()::equals);
                iterator.remove();
                terminalCount--;
            }
        }
    }

    public enum AttemptStatus {
        ACTIVE,
        SUBMITTED,
        CANCELLED,
        EXPIRED,
        DISQUALIFIED
    }

    public record ActiveAttempt(
            String attemptId,
            String slot,
            Long studentId,
            String examIdentifier,
            String examSubject,
            LocalDateTime startedAt,
            LocalDateTime deadline,
            AttemptStatus status) implements Serializable {

        private static final long serialVersionUID = 1L;

        ActiveAttempt withStatus(AttemptStatus newStatus) {
            return new ActiveAttempt(
                    attemptId,
                    slot,
                    studentId,
                    examIdentifier,
                    examSubject,
                    startedAt,
                    deadline,
                    newStatus);
        }
    }
}
