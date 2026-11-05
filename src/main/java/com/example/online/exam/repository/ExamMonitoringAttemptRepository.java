package com.example.online.exam.repository;

import com.example.online.exam.entity.ExamMonitoringAttempt;
import com.example.online.exam.entity.ExamMonitoringAttempt.Status;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExamMonitoringAttemptRepository
        extends JpaRepository<ExamMonitoringAttempt, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select attempt
            from ExamMonitoringAttempt attempt
            where attempt.attemptId = :attemptId
            """)
    Optional<ExamMonitoringAttempt> findByAttemptIdForUpdate(
            @Param("attemptId") String attemptId);

    List<ExamMonitoringAttempt>
            findByStatusAndDisqualifiedAtIsNotNullOrderByDisqualifiedAtDesc(
                    Status status);
}
