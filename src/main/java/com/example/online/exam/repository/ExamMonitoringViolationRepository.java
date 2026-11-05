package com.example.online.exam.repository;

import com.example.online.exam.entity.ExamMonitoringViolation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExamMonitoringViolationRepository
        extends JpaRepository<ExamMonitoringViolation, Long> {

    Optional<ExamMonitoringViolation> findByClientEventId(String clientEventId);

    long countByStudentIdAndAttemptId(Long studentId, String attemptId);

    List<ExamMonitoringViolation>
            findByStudentIdAndAttemptIdOrderByOccurredAtAscIdAsc(
                    Long studentId,
                    String attemptId);
}
