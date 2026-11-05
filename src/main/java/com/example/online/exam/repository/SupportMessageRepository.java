package com.example.online.exam.repository;

import com.example.online.exam.entity.SupportMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SupportMessageRepository
        extends JpaRepository<SupportMessage, Long> {

    List<SupportMessage> findByConversation_IdOrderBySentAtAsc(Long conversationId);

    List<SupportMessage> findByConversation_IdInOrderBySentAtAsc(
            Collection<Long> conversationIds);
}
