package com.example.online.exam.repository;

import com.example.online.exam.entity.StudentNotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface StudentNotificationPreferenceRepository
        extends JpaRepository<StudentNotificationPreference, Long> {

    List<StudentNotificationPreference> findByStudentIdIn(
            Collection<Long> studentIds);
}
