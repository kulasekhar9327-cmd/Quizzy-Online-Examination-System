package com.example.online.exam;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.example.online.exam.entity.ExamMonitoringViolation;
import com.example.online.exam.entity.ExamMonitoringAttempt;
import com.example.online.exam.entity.Student;
import com.example.online.exam.repository.StudentRepository;
import com.example.online.exam.service.ExamAttemptSession;
import com.example.online.exam.service.ExamAttemptSession.ActiveAttempt;
import com.example.online.exam.service.ExamMonitoringService;
import com.example.online.exam.service.ExamMonitoringService.Outcome;
import com.example.online.exam.service.ExamMonitoringService.RecordResult;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/exam-monitoring/violations")
public class ExamMonitoringController {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ExamMonitoringController.class);

    private static final List<String> VALID_VIOLATION_TYPES = List.of(
            "TAB_SWITCH",
            "FOCUS_LOSS",
            "FULLSCREEN_EXIT",
            "COPY",
            "CUT",
            "PASTE");

    private final ExamMonitoringService monitoringService;
    private final StudentRepository studentRepository;

    public ExamMonitoringController(
            ExamMonitoringService monitoringService,
            StudentRepository studentRepository) {
        this.monitoringService = monitoringService;
        this.studentRepository = studentRepository;
    }

    @PostMapping
    public ResponseEntity<?> recordViolation(
            @RequestBody ViolationRequest request,
            @RequestHeader(
                    value = "X-Requested-With",
                    required = false) String requestedWith,
            HttpSession session) {
        synchronized (session) {
            Long studentId = authenticatedStudentId(session);
            if (studentId == null) {
                return error(
                        HttpStatus.UNAUTHORIZED,
                        "Authentication required.");
            }

            if (!"XMLHttpRequest".equals(requestedWith)) {
                return error(
                        HttpStatus.FORBIDDEN,
                        "Request is not permitted.");
            }

            if (request == null
                    || !ExamAttemptSession.isUuid(request.attemptId())
                    || !ExamAttemptSession.isUuid(request.clientEventId())
                    || request.violationType() == null
                    || !VALID_VIOLATION_TYPES.contains(
                            request.violationType())) {
                return error(
                        HttpStatus.BAD_REQUEST,
                        "Invalid violation request.");
            }

            ActiveAttempt attempt =
                    ExamAttemptSession.get(session, request.attemptId());
            if (attempt == null) {
                return error(
                        HttpStatus.NOT_FOUND,
                        "Exam attempt was not found.");
            }
            if (!attempt.studentId().equals(studentId)) {
                return error(
                        HttpStatus.NOT_FOUND,
                        "Exam attempt was not found.");
            }
            if (attempt.status()
                    != ExamAttemptSession.AttemptStatus.ACTIVE) {
                if (attempt.status()
                        == ExamAttemptSession.AttemptStatus.DISQUALIFIED) {
                    return ResponseEntity.status(HttpStatus.GONE)
                            .body(new AttemptStatusResponse(
                                    ExamMonitoringService.MAX_WARNINGS,
                                    "DISQUALIFIED",
                                    true,
                                    ExamMonitoringAttempt
                                            .DISQUALIFICATION_REASON));
                }
                return error(
                        HttpStatus.GONE,
                        "Exam attempt is no longer active.");
            }
            LocalDateTime now = LocalDateTime.now();
            if (!now.isBefore(attempt.deadline())) {
                ExamAttemptSession.expire(session, attempt.attemptId());
                monitoringService.finishAttempt(
                        attempt.attemptId(),
                        ExamMonitoringAttempt.Status.EXPIRED);
                return error(
                        HttpStatus.GONE,
                        "Exam attempt has expired.");
            }

            try {
                RecordResult result = monitoringService.recordViolation(
                        studentId,
                        attempt,
                        request.clientEventId(),
                        request.violationType());
                if (result.attemptStatus()
                        == ExamMonitoringAttempt.Status.DISQUALIFIED) {
                    ExamAttemptSession.finish(
                            session,
                            attempt.slot(),
                            attempt.attemptId(),
                            ExamAttemptSession.AttemptStatus.DISQUALIFIED);
                } else if (result.attemptStatus()
                        == ExamMonitoringAttempt.Status.EXPIRED) {
                    ExamAttemptSession.finish(
                            session,
                            attempt.slot(),
                            attempt.attemptId(),
                            ExamAttemptSession.AttemptStatus.EXPIRED);
                }
                return responseFor(result);
            } catch (DataIntegrityViolationException exception) {
                Optional<ExamMonitoringViolation> duplicate =
                        monitoringService.findByClientEventId(
                                request.clientEventId());
                if (duplicate.isPresent()) {
                    ExamMonitoringViolation existing = duplicate.get();
                    if (existing.getStudentId().equals(studentId)
                            && existing.getAttemptId()
                                    .equals(attempt.attemptId())
                            && existing.getViolationType()
                                    .equals(request.violationType())) {
                        return ResponseEntity.ok(
                                response(
                                        existing,
                                        true,
                                        monitoringService.warningCount(
                                                studentId,
                                                attempt.attemptId()),
                                        monitoringService.getAttempt(
                                                attempt.attemptId(),
                                                studentId).orElseThrow()));
                    }
                    return error(
                            HttpStatus.CONFLICT,
                            "Event identifier has already been used.");
                }

                LOGGER.error(
                        "Unable to persist exam monitoring event.");
                return error(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Monitoring event could not be saved.");
            }
        }
    }

    @GetMapping("/{attemptId}")
    public ResponseEntity<?> getViolations(
            @PathVariable String attemptId,
            HttpSession session) {
        Long studentId = authenticatedStudentId(session);
        if (studentId == null) {
            return error(
                    HttpStatus.UNAUTHORIZED,
                    "Authentication required.");
        }
        if (!ExamAttemptSession.isUuid(attemptId)) {
            return error(
                    HttpStatus.BAD_REQUEST,
                    "Invalid attempt identifier.");
        }

        List<ExamMonitoringViolation> records = monitoringService
                .getViolations(studentId, attemptId);
        ExamMonitoringAttempt attempt = monitoringService
                .getAttempt(attemptId, studentId)
                .orElse(null);
        int warningCount = Math.min(
                ExamMonitoringService.MAX_WARNINGS,
                records.size());
        List<ViolationResponse> violations = records
                .stream()
                .map(violation -> response(
                        violation,
                        false,
                        warningCount,
                        attempt))
                .toList();
        return ResponseEntity.ok(new AttemptHistoryResponse(
                attemptId,
                warningCount,
                attempt == null ? null : attempt.getStatus().name(),
                attempt != null
                        && attempt.getStatus()
                                == ExamMonitoringAttempt.Status.DISQUALIFIED,
                attempt == null
                        ? null
                        : attempt.getDisqualificationReason(),
                violations));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleMalformedRequest() {
        return error(
                HttpStatus.BAD_REQUEST,
                "Invalid request body.");
    }

    private ResponseEntity<?> responseFor(RecordResult result) {
        return switch (result.outcome()) {
            case ACCEPTED -> ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(response(
                            result.violation(),
                            false,
                            result.warningCount(),
                            result.attemptStatus()));
            case DUPLICATE -> ResponseEntity.ok(response(
                    result.violation(),
                    true,
                    result.warningCount(),
                    result.attemptStatus()));
            case CONFLICT -> ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(new WarningCountResponse(
                            result.warningCount(),
                            "Event identifier has already been used.",
                            result.attemptStatus() == null
                                    ? null
                                    : result.attemptStatus().name(),
                            false,
                            null));
            case DISQUALIFIED -> ResponseEntity
                    .status(HttpStatus.GONE)
                    .body(new WarningCountResponse(
                            result.warningCount(),
                            ExamMonitoringAttempt
                                    .DISQUALIFICATION_REASON,
                            "DISQUALIFIED",
                            true,
                            ExamMonitoringAttempt
                                    .DISQUALIFICATION_REASON));
            case ATTEMPT_ENDED -> ResponseEntity
                    .status(HttpStatus.GONE)
                    .body(new WarningCountResponse(
                            result.warningCount(),
                            "Exam attempt is no longer active.",
                            result.attemptStatus() == null
                                    ? null
                                    : result.attemptStatus().name(),
                            false,
                            null));
            case ATTEMPT_NOT_FOUND -> error(
                    HttpStatus.NOT_FOUND,
                    "Exam attempt was not found.");
        };
    }

    private ViolationResponse response(
            ExamMonitoringViolation violation,
            boolean duplicate,
            int warningCount,
            ExamMonitoringAttempt.Status attemptStatus) {
        return new ViolationResponse(
                violation.getId(),
                violation.getAttemptId(),
                violation.getViolationType(),
                violation.getWarningNumber(),
                warningCount,
                violation.getOccurredAt(),
                duplicate,
                attemptStatus == ExamMonitoringAttempt.Status.DISQUALIFIED,
                attemptStatus == null ? null : attemptStatus.name(),
                attemptStatus == ExamMonitoringAttempt.Status.DISQUALIFIED
                        ? ExamMonitoringAttempt
                                .DISQUALIFICATION_REASON
                        : null);
    }

    private ViolationResponse response(
            ExamMonitoringViolation violation,
            boolean duplicate,
            int warningCount,
            ExamMonitoringAttempt attemptStatus) {
        return response(
                violation,
                duplicate,
                warningCount,
                attemptStatus == null
                        ? null
                        : attemptStatus.getStatus());
    }

    private Long authenticatedStudentId(HttpSession session) {
        Object value = session.getAttribute("studentId");
        if (value == null) {
            return null;
        }

        try {
            long studentId = Long.parseLong(value.toString());
            if (studentId <= 0) {
                return null;
            }
            Optional<Student> student = studentRepository.findById(studentId);
            return student.map(Student::getId).orElse(null);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private ResponseEntity<Map<String, String>> error(
            HttpStatus status,
            String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ViolationRequest(
            String attemptId,
            String clientEventId,
            String violationType) {
    }

    public record ViolationResponse(
            Long id,
            String attemptId,
            String violationType,
            int warningNumber,
            int warningCount,
            java.time.Instant occurredAt,
            boolean duplicate,
            boolean disqualified,
            String attemptStatus,
            String disqualificationReason) {
    }

    public record AttemptHistoryResponse(
            String attemptId,
            int warningCount,
            String attemptStatus,
            boolean disqualified,
            String disqualificationReason,
            List<ViolationResponse> violations) {
    }

    public record WarningCountResponse(
            int warningCount,
            String message,
            String attemptStatus,
            boolean disqualified,
            String disqualificationReason) {
    }

    public record AttemptStatusResponse(
            int warningCount,
            String attemptStatus,
            boolean disqualified,
            String disqualificationReason) {
    }
}
