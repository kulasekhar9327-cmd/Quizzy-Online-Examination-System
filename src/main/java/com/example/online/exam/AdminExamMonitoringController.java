package com.example.online.exam;

import com.example.online.exam.entity.ExamMonitoringAttempt;
import com.example.online.exam.service.ExamMonitoringService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/admin/api/exam-monitoring")
public class AdminExamMonitoringController {

    private final ExamMonitoringService monitoringService;

    public AdminExamMonitoringController(
            ExamMonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    @GetMapping("/disqualified")
    public ResponseEntity<?> getDisqualifiedAttempts(HttpSession session) {
        if (!Boolean.TRUE.equals(session.getAttribute("quizzyAdmin"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("Administrator access required."));
        }

        List<DisqualificationResponse> attempts = monitoringService
                .getDisqualifiedAttempts()
                .stream()
                .map(attempt -> new DisqualificationResponse(
                        attempt.getAttemptId(),
                        attempt.getStudentId(),
                        attempt.getExamIdentifier(),
                        attempt.getExamSubject(),
                        attempt.getDisqualifiedAt(),
                        attempt.getDisqualificationReason(),
                        attempt.getWarningCount(),
                        attempt.getStatus().name()))
                .toList();
        return ResponseEntity.ok(attempts);
    }

    public record DisqualificationResponse(
            String attemptId,
            Long studentId,
            String examIdentifier,
            String examSubject,
            Instant disqualifiedAt,
            String reason,
            int warningCount,
            String status) {
    }

    public record ErrorResponse(String error) {
    }
}
