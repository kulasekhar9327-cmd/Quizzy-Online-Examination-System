package com.example.online.exam.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "support_messages")
public class SupportMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private SupportConversation conversation;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false, length = 16)
    private SupportSender senderType;

    @Column(nullable = false, length = 4000)
    private String content;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @Column(name = "read_by_student", nullable = false)
    private boolean readByStudent;

    @Column(name = "read_by_admin", nullable = false)
    private boolean readByAdmin;

    protected SupportMessage() {
    }

    public SupportMessage(
            SupportConversation conversation,
            SupportSender senderType,
            String content) {
        this.conversation = conversation;
        this.senderType = senderType;
        this.content = content;
        this.readByStudent = senderType == SupportSender.STUDENT;
        this.readByAdmin = senderType == SupportSender.ADMIN;
    }

    @PrePersist
    void setSentAt() {
        if (sentAt == null) {
            sentAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public SupportConversation getConversation() {
        return conversation;
    }

    public SupportSender getSenderType() {
        return senderType;
    }

    public String getContent() {
        return content;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public boolean isReadByStudent() {
        return readByStudent;
    }

    public void markReadByStudent() {
        readByStudent = true;
    }

    public boolean isReadByAdmin() {
        return readByAdmin;
    }

    public void markReadByAdmin() {
        readByAdmin = true;
    }
}
