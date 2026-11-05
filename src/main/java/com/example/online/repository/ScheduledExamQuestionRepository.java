
package com.example.online.exam.repository;

import com.example.online.exam.entity.ScheduledExamQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScheduledExamQuestionRepository
        extends JpaRepository<ScheduledExamQuestion, Long> {

    List<ScheduledExamQuestion>
        findByExamSchedule_IdOrderByIdAsc(Long scheduleId);
}
