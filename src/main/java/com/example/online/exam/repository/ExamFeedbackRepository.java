package com.example.online.exam.repository;

import com.example.online.exam.entity.ExamFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExamFeedbackRepository
        extends JpaRepository<ExamFeedback, Long> {

    List<ExamFeedback> findByStudent_IdOrderBySubmittedAtDesc(Long studentId);

    List<ExamFeedback> findAllByOrderBySubmittedAtDesc();

    Optional<ExamFeedback> findByExamResult_IdAndStudent_Id(
            Long resultId, Long studentId);

    boolean existsByExamResult_Id(Long resultId);
}
