package com.example.online.exam.service;

import com.example.online.exam.entity.Student;
import com.example.online.exam.entity.SupportCategory;
import com.example.online.exam.entity.SupportConversation;
import com.example.online.exam.entity.SupportMessage;
import com.example.online.exam.entity.SupportSender;
import com.example.online.exam.entity.SupportStatus;
import com.example.online.exam.repository.StudentRepository;
import com.example.online.exam.repository.SupportConversationRepository;
import com.example.online.exam.repository.SupportMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SupportService {

    private final SupportConversationRepository conversationRepository;
    private final SupportMessageRepository messageRepository;
    private final StudentRepository studentRepository;
    private final NotificationService notificationService;

    public SupportService(
            SupportConversationRepository conversationRepository,
            SupportMessageRepository messageRepository,
            StudentRepository studentRepository,
            NotificationService notificationService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.studentRepository = studentRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public Long createConversation(
            Long studentId,
            String subject,
            SupportCategory category,
            String firstMessage) {
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new IllegalStateException(
                        "The signed-in student account is unavailable."));
        SupportConversation conversation = conversationRepository.save(
                new SupportConversation(student, subject, category));
        addMessage(conversation, SupportSender.STUDENT, firstMessage);
        return conversation.getId();
    }

    @Transactional(readOnly = true)
    public List<ConversationSummary> studentConversations(Long studentId) {
        return summaries(
                conversationRepository.findByStudent_IdOrderByUpdatedAtDesc(
                        studentId),
                SupportSender.ADMIN);
    }

    @Transactional
    public Optional<ConversationDetail> studentConversation(
            Long conversationId,
            Long studentId) {
        Optional<SupportConversation> result =
                conversationRepository.findByIdAndStudent_Id(
                        conversationId, studentId);
        if (result.isEmpty()) {
            return Optional.empty();
        }
        List<SupportMessage> messages =
                messageRepository.findByConversation_IdOrderBySentAtAsc(
                        conversationId);
        messages.stream()
                .filter(message -> message.getSenderType() == SupportSender.ADMIN)
                .filter(message -> !message.isReadByStudent())
                .forEach(SupportMessage::markReadByStudent);
        messageRepository.saveAll(messages);
        return Optional.of(detail(result.get(), messages, SupportSender.ADMIN));
    }

    @Transactional
    public Optional<Boolean> sendStudentMessage(
            Long conversationId,
            Long studentId,
            String content) {
        Optional<SupportConversation> result =
                conversationRepository.findByIdAndStudent_Id(
                        conversationId, studentId);
        if (result.isEmpty()) {
            return Optional.empty();
        }
        SupportConversation conversation = result.get();
        if (isTerminal(conversation.getStatus())) {
            return Optional.of(false);
        }
        if (conversation.getStatus() == SupportStatus.WAITING_FOR_STUDENT) {
            conversation.setStatus(SupportStatus.IN_PROGRESS);
        }
        addMessage(conversation, SupportSender.STUDENT, content);
        conversationRepository.save(conversation);
        return Optional.of(true);
    }

    @Transactional(readOnly = true)
    public List<ConversationSummary> adminConversations(
            SupportStatus status,
            SupportCategory category) {
        return summaries(conversationRepository.findForAdmin(status, category),
                SupportSender.STUDENT);
    }

    @Transactional
    public Optional<ConversationDetail> adminConversation(Long conversationId) {
        Optional<SupportConversation> result =
                conversationRepository.findById(conversationId);
        if (result.isEmpty()) {
            return Optional.empty();
        }
        List<SupportMessage> messages =
                messageRepository.findByConversation_IdOrderBySentAtAsc(
                        conversationId);
        messages.stream()
                .filter(message -> message.getSenderType() == SupportSender.STUDENT)
                .filter(message -> !message.isReadByAdmin())
                .forEach(SupportMessage::markReadByAdmin);
        messageRepository.saveAll(messages);
        return Optional.of(detail(result.get(), messages, SupportSender.STUDENT));
    }

    @Transactional
    public Optional<Boolean> sendAdminMessage(
            Long conversationId,
            String content) {
        Optional<SupportConversation> result =
                conversationRepository.findById(conversationId);
        if (result.isEmpty()) {
            return Optional.empty();
        }
        SupportConversation conversation = result.get();
        if (isTerminal(conversation.getStatus())) {
            return Optional.of(false);
        }
        conversation.setStatus(SupportStatus.WAITING_FOR_STUDENT);
        SupportMessage message =
                addMessage(conversation, SupportSender.ADMIN, content);
        conversationRepository.save(conversation);
        notificationService.notifySupportReply(
                conversation.getStudent().getId(),
                conversation.getId(),
                message.getId());
        return Optional.of(true);
    }

    @Transactional
    public boolean changeStatus(Long conversationId, SupportStatus status) {
        Optional<SupportConversation> result =
                conversationRepository.findById(conversationId);
        if (result.isEmpty()) {
            return false;
        }
        result.get().setStatus(status);
        conversationRepository.save(result.get());
        return true;
    }

    private SupportMessage addMessage(
            SupportConversation conversation,
            SupportSender sender,
            String content) {
        SupportMessage message = messageRepository.save(
                new SupportMessage(conversation, sender, content));
        conversation.recordMessage(message.getSentAt());
        return message;
    }

    private List<ConversationSummary> summaries(
            List<SupportConversation> conversations,
            SupportSender unreadSender) {
        if (conversations.isEmpty()) {
            return List.of();
        }
        List<Long> ids = conversations.stream()
                .map(SupportConversation::getId)
                .toList();
        Map<Long, List<SupportMessage>> messagesByConversation =
                groupMessages(messageRepository
                        .findByConversation_IdInOrderBySentAtAsc(ids));
        List<ConversationSummary> summaries = new ArrayList<>();
        for (SupportConversation conversation : conversations) {
            List<SupportMessage> messages = messagesByConversation
                    .getOrDefault(conversation.getId(), List.of());
            SupportSender reader = otherParty(unreadSender);
            boolean unread = messages.stream()
                    .anyMatch(message -> message.getSenderType() == unreadSender
                            && !isReadFor(message, reader));
            summaries.add(summary(conversation, unread));
        }
        return summaries;
    }

    private Map<Long, List<SupportMessage>> groupMessages(
            List<SupportMessage> messages) {
        Map<Long, List<SupportMessage>> grouped = new HashMap<>();
        for (SupportMessage message : messages) {
            grouped.computeIfAbsent(
                    message.getConversation().getId(),
                    ignored -> new ArrayList<>()).add(message);
        }
        return grouped;
    }

    private ConversationDetail detail(
            SupportConversation conversation,
            List<SupportMessage> messages,
            SupportSender unreadSender) {
        SupportSender reader = otherParty(unreadSender);
        boolean unread = messages.stream()
                .anyMatch(message -> message.getSenderType() == unreadSender
                        && !isReadFor(message, reader));
        return new ConversationDetail(
                summary(conversation, unread),
                messages.stream().map(this::messageView).toList());
    }

    private ConversationSummary summary(
            SupportConversation conversation,
            boolean unread) {
        return new ConversationSummary(
                conversation.getId(),
                conversation.getStudent().getFullName(),
                conversation.getStudent().getEmail(),
                conversation.getSubject(),
                conversation.getCategory(),
                conversation.getStatus(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt(),
                conversation.getLastMessageAt(),
                unread);
    }

    private MessageView messageView(SupportMessage message) {
        return new MessageView(
                message.getSenderType(),
                message.getContent(),
                message.getSentAt(),
                message.isReadByStudent(),
                message.isReadByAdmin());
    }

    private boolean isReadFor(
            SupportMessage message,
            SupportSender reader) {
        return reader == SupportSender.STUDENT
                ? message.isReadByStudent()
                : message.isReadByAdmin();
    }

    private SupportSender otherParty(SupportSender sender) {
        return sender == SupportSender.STUDENT
                ? SupportSender.ADMIN
                : SupportSender.STUDENT;
    }

    private boolean isTerminal(SupportStatus status) {
        return status == SupportStatus.RESOLVED
                || status == SupportStatus.CLOSED;
    }

    public record ConversationSummary(
            Long id,
            String studentName,
            String studentEmail,
            String subject,
            SupportCategory category,
            SupportStatus status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime lastMessageAt,
            boolean hasUnreadMessages) {
    }

    public record MessageView(
            SupportSender sender,
            String content,
            LocalDateTime sentAt,
            boolean readByStudent,
            boolean readByAdmin) {
    }

    public record ConversationDetail(
            ConversationSummary conversation,
            List<MessageView> messages) {
    }
}
