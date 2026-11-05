
package com.example.online.exam.repository;

import com.example.online.exam.entity.ExamResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ExamResultRepository
        extends JpaRepository<ExamResult, Long> {

    List<ExamResult> findByStudentIdOrderByExamDateDesc(
            Long studentId);

    List<ExamResult> findAllByOrderByExamDateDesc();

    long countByPublishedTrue();

    List<ExamResult>
            findTop10ByPublishedTrueAndPublishedAtIsNotNullOrderByPublishedAtDesc();

    @Query("""
            select count(result)
            from ExamResult result
            where result.totalQuestions > 0
              and result.score >= 0
              and result.score <= result.totalQuestions
              and result.percentage between 0 and 100
            """)
    long countValidAttempts();

    @Query("""
            select result
            from ExamResult result
            where result.totalQuestions > 0
              and result.score >= 0
              and result.score <= result.totalQuestions
              and result.percentage between 0 and 100
            order by result.examDate desc
            """)
    List<ExamResult> findRecentValidAttempts(Pageable pageable);

    @Query("""
            select coalesce(avg(result.percentage), 0)
            from ExamResult result
            where result.totalQuestions > 0
              and result.score >= 0
              and result.score <= result.totalQuestions
              and result.percentage between 0 and 100
            """)
    Double averageValidPercentage();

    @Query("""
            select coalesce(result.subject, 'Unspecified') as subject,
                   count(result.id) as attempts,
                   avg(result.percentage) as averagePercentage
            from ExamResult result
            where result.totalQuestions > 0
              and result.score >= 0
              and result.score <= result.totalQuestions
              and result.percentage between 0 and 100
            group by result.subject
            order by count(result.id) desc, result.subject asc
            """)
    List<SubjectPerformance> findSubjectPerformance();

    @Query(value = """
            select cast(exam_date as date) as attemptDate, count(*) as attempts
            from exam_results
            where total_questions > 0
              and score >= 0
              and score <= total_questions
              and percentage between 0 and 100
            group by cast(exam_date as date)
            order by attemptDate desc
            limit 14
            """, nativeQuery = true)
    List<DailyParticipation> findDailyParticipation();

    Optional<ExamResult> findByIdAndStudentId(Long id, Long studentId);

    interface SubjectPerformance {
        String getSubject();

        Long getAttempts();

        Double getAveragePercentage();
    }

    interface DailyParticipation {
        LocalDate getAttemptDate();

        Long getAttempts();
    }
}
