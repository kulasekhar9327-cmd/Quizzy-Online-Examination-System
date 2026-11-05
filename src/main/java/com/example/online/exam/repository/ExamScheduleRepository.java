
package com.example.online.exam.repository;

import com.example.online.exam.entity.ExamSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface ExamScheduleRepository extends JpaRepository<ExamSchedule, Long> {

    List<ExamSchedule> findByExamDateGreaterThanEqualOrderByExamDateAscExamTimeAsc(
            LocalDate date
    );
}
