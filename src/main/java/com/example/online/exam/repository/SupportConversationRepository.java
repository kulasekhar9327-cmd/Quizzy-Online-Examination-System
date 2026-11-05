package com.example.online.exam.repository;

import com.example.online.exam.entity.SupportCategory;
import com.example.online.exam.entity.SupportConversation;
import com.example.online.exam.entity.SupportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SupportConversationRepository
        extends JpaRepository<SupportConversation, Long> {

    List<SupportConversation> findByStudent_IdOrderByUpdatedAtDesc(Long studentId);

    List<SupportConversation> findTop5ByOrderByUpdatedAtDesc();

    Optional<SupportConversation> findByIdAndStudent_Id(
            Long id, Long studentId);

    @Query("""
            select conversation
            from SupportConversation conversation
            where (:status is null or conversation.status = :status)
              and (:category is null or conversation.category = :category)
            order by conversation.updatedAt desc
            """)
    List<SupportConversation> findForAdmin(
            @Param("status") SupportStatus status,
            @Param("category") SupportCategory category);
}
