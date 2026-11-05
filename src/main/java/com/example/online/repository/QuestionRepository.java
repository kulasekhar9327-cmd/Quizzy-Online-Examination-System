
package com.example.online.exam.repository;

import com.example.online.exam.entity.Question;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuestionRepository
        extends JpaRepository<Question, Long> {

    List<Question> findBySubjectIgnoreCaseOrderByIdAsc(
            String subject);
}
