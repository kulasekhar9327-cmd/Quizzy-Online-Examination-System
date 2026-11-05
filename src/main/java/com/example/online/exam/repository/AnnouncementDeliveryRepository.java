package com.example.online.exam.repository;

import com.example.online.exam.entity.AnnouncementDelivery;
import com.example.online.exam.entity.AnnouncementDeliveryStatus;
import com.example.online.exam.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AnnouncementDeliveryRepository
        extends JpaRepository<AnnouncementDelivery, Long> {

    List<AnnouncementDelivery>
            findByAnnouncementRequestRequestIdOrderByStudentIdAsc(
                    String requestId);

    List<AnnouncementDelivery>
            findByAnnouncementRequestRequestIdInOrderByStudentIdAsc(
                    List<String> requestIds);

    @Modifying
    @Query("""
            update AnnouncementDelivery delivery
            set delivery.status = :sending,
                delivery.lastAttemptAt = current_timestamp,
                delivery.failureReason = null
            where delivery.id = :id
              and delivery.status = :pending
            """)
    int claimPending(
            @Param("id") Long id,
            @Param("pending") AnnouncementDeliveryStatus pending,
            @Param("sending") AnnouncementDeliveryStatus sending);

    @Modifying
    @Query("""
            update AnnouncementDelivery delivery
            set delivery.status = :sent,
                delivery.sentAt = current_timestamp,
                delivery.failureReason = null
            where delivery.id = :id
              and delivery.status = :sending
            """)
    int markSent(
            @Param("id") Long id,
            @Param("sending") AnnouncementDeliveryStatus sending,
            @Param("sent") AnnouncementDeliveryStatus sent);

    @Modifying
    @Query("""
            update AnnouncementDelivery delivery
            set delivery.status = :failed,
                delivery.failureReason = :failureReason
            where delivery.id = :id
              and delivery.status = :sending
            """)
    int markFailed(
            @Param("id") Long id,
            @Param("sending") AnnouncementDeliveryStatus sending,
            @Param("failed") AnnouncementDeliveryStatus failed,
            @Param("failureReason") String failureReason);

    @Modifying
    @Query("""
            update AnnouncementDelivery delivery
            set delivery.status = :suppressed,
                delivery.failureReason = :reason
            where delivery.id = :id
              and delivery.status = :pending
            """)
    int suppressPending(
            @Param("id") Long id,
            @Param("pending") AnnouncementDeliveryStatus pending,
            @Param("suppressed") AnnouncementDeliveryStatus suppressed,
            @Param("reason") String reason);

    long countByAnnouncementRequestRequestIdAndStatus(
            String requestId, AnnouncementDeliveryStatus status);

    @Query("""
            select delivery.id as deliveryId,
                   student.id as studentId,
                   student.fullName as recipientName,
                   student.email as recipientEmail,
                   request.title as announcementTitle,
                   delivery.status as status,
                   delivery.createdAt as createdAt,
                   delivery.lastAttemptAt as lastAttemptAt,
                   delivery.sentAt as sentAt,
                   delivery.failureReason as failureReason
            from AnnouncementDelivery delivery
            join delivery.announcementRequest request
            join Student student on student.id = delivery.studentId
            where (:status is null or delivery.status = :status)
            order by delivery.createdAt desc
            """)
    Page<DeliveryTrackingRow> findDeliveryTracking(
            @Param("status") AnnouncementDeliveryStatus status,
            Pageable pageable);

    interface DeliveryTrackingRow {
        Long getDeliveryId();

        Long getStudentId();

        String getRecipientName();

        String getRecipientEmail();

        String getAnnouncementTitle();

        AnnouncementDeliveryStatus getStatus();

        java.time.LocalDateTime getCreatedAt();

        java.time.LocalDateTime getLastAttemptAt();

        java.time.LocalDateTime getSentAt();

        String getFailureReason();
    }
}
