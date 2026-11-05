
package com.example.online.exam.repository;

import com.example.online.exam.entity.Student;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.util.Collection;

public interface StudentRepository extends JpaRepository<Student, Long> {

    Optional<Student> findByEmail(String email);

    boolean existsByEmailAndIdNot(String email, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select student from Student student "
            + "where lower(student.email) = lower(:email)")
    Optional<Student> findByEmailForRecovery(@Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Student> findByPasswordRecoveryTokenHash(String tokenHash);

    @Query("select student.id from Student student order by student.id")
    List<Long> findAllStudentIds();

    @Query("select student.id from Student student where student.id in :ids")
    List<Long> findStudentIdsByIdIn(@Param("ids") Collection<Long> ids);

    @Query("""
            select student.id as id, student.email as email
            from Student student
            where student.id in :ids
            """)
    List<StudentEmailOption> findEmailOptionsByIdIn(
            @Param("ids") Collection<Long> ids);

    @Query("""
            select student.id as id, student.fullName as fullName
            from Student student
            order by student.fullName
            """)
    List<StudentOption> findAllStudentOptions();

    interface StudentOption {
        Long getId();

        String getFullName();
    }

    interface StudentEmailOption {
        Long getId();

        String getEmail();
    }
}
