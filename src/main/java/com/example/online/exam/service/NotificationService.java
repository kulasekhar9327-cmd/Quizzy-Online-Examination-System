package com.example.online.exam.service;

import com.example.online.exam.entity.AppNotification;
import com.example.online.exam.entity.AnnouncementDelivery;
import com.example.online.exam.entity.AnnouncementDeliveryStatus;
import com.example.online.exam.entity.AnnouncementRequest;
import com.example.online.exam.entity.NotificationType;
import com.example.online.exam.entity.StudentNotificationPreference;
import com.example.online.exam.repository.AnnouncementDeliveryRepository;
import com.example.online.exam.repository.AnnouncementRequestRepository;
import com.example.online.exam.repository.AppNotificationRepository;
import com.example.online.exam.repository.StudentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class NotificationService {

    public static final String STUDENT_RECIPIENT = "STUDENT";
    public static final int PAGE_SIZE = 50;
    private static final int MAX_TITLE_LENGTH = 120;
    private static final int MAX_MESSAGE_LENGTH = 1000;

    private final AppNotificationRepository notificationRepository;
    private final StudentRepository studentRepository;
    private final AnnouncementRequestRepository announcementRequestRepository;
    private final AnnouncementDeliveryRepository announcementDeliveryRepository;
    private final EmailService emailService;
    private final NotificationPreferenceService preferenceService;
    private final TransactionTemplate announcementTransaction;

    public NotificationService(
            AppNotificationRepository notificationRepository,
            StudentRepository studentRepository,
            AnnouncementRequestRepository announcementRequestRepository,
            AnnouncementDeliveryRepository announcementDeliveryRepository,
            EmailService emailService,
            NotificationPreferenceService preferenceService,
            PlatformTransactionManager transactionManager) {
        this.notificationRepository = notificationRepository;
        this.studentRepository = studentRepository;
        this.announcementRequestRepository = announcementRequestRepository;
        this.announcementDeliveryRepository = announcementDeliveryRepository;
        this.emailService = emailService;
        this.preferenceService = preferenceService;
        this.announcementTransaction = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public NotificationPage studentNotifications(Long studentId, int pageNumber) {
        int safePage = Math.max(pageNumber, 0);
        Page<AppNotification> page = notificationRepository
                .findByRecipientTypeAndRecipientIdOrderByCreatedAtDesc(
                        STUDENT_RECIPIENT,
                        studentId,
                        PageRequest.of(
                                safePage,
                                PAGE_SIZE,
                                Sort.by(Sort.Direction.DESC, "createdAt")));
        List<NotificationView> notifications = page.getContent().stream()
                .map(notification -> new NotificationView(
                        notification,
                        safeInternalUrl(notification.getTargetUrl())
                                .orElse(null)))
                .toList();
        return new NotificationPage(
                notifications,
                notificationRepository
                        .countByRecipientTypeAndRecipientIdAndReadAtIsNull(
                                STUDENT_RECIPIENT, studentId),
                page.getNumber(),
                page.getTotalPages());
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long studentId) {
        return notificationRepository
                .countByRecipientTypeAndRecipientIdAndReadAtIsNull(
                        STUDENT_RECIPIENT, studentId);
    }

    @Transactional
    public boolean markRead(Long notificationId, Long studentId, boolean read) {
        Optional<AppNotification> result =
                notificationRepository
                        .findByIdAndRecipientTypeAndRecipientId(
                                notificationId,
                                STUDENT_RECIPIENT,
                                studentId);
        if (result.isEmpty()) {
            return false;
        }
        if (read) {
            result.get().markRead();
        } else {
            result.get().markUnread();
        }
        notificationRepository.save(result.get());
        return true;
    }

    @Transactional
    public int markAllRead(Long studentId) {
        return notificationRepository.markAllRead(
                STUDENT_RECIPIENT, studentId);
    }

    @Transactional
    public boolean createStudentNotification(
            Long studentId,
            String eventKey,
            String title,
            String message,
            NotificationType type,
            String targetUrl,
            Long referenceId) {
        if (studentId == null
                || !validText(title, MAX_TITLE_LENGTH)
                || !validText(message, MAX_MESSAGE_LENGTH)
                || type == null
                || !isSafeInternalUrl(targetUrl)
                || eventKey == null
                || eventKey.isBlank()
                || eventKey.length() > 160) {
            throw new IllegalArgumentException(
                    "Invalid notification details.");
        }
        if (!preferenceService.inAppEnabled(studentId, type)) {
            return false;
        }
        if (notificationRepository
                .findByRecipientTypeAndRecipientIdAndEventKey(
                        STUDENT_RECIPIENT, studentId, eventKey)
                .isPresent()) {
            return false;
        }
        notificationRepository.save(new AppNotification(
                studentId,
                eventKey,
                title.trim(),
                message.trim(),
                type,
                targetUrl,
                referenceId));
        return true;
    }

    public int createAnnouncement(
            String requestId,
            String title,
            String message,
            Long selectedStudentId) {
        return createAnnouncementForRecipients(
                requestId,
                title,
                message,
                selectedStudentId == null ? null : List.of(selectedStudentId));
    }

    public int createAnnouncementForRecipients(
            String requestId,
            String title,
            String message,
            List<Long> selectedStudentIds) {
        if (!validUuid(requestId)
                || !validText(title, MAX_TITLE_LENGTH)
                || !validText(message, MAX_MESSAGE_LENGTH)) {
            throw new IllegalArgumentException(
                    "Enter a valid request ID, title and message.");
        }
        String normalizedRequestId = UUID.fromString(requestId).toString();
        String normalizedTitle = title.trim();
        String normalizedMessage = message.trim();
        List<Long> recipients;
        if (selectedStudentIds == null) {
            recipients = studentRepository.findAllStudentIds();
        } else {
            if (selectedStudentIds.isEmpty()
                    || selectedStudentIds.stream().anyMatch(
                            id -> id == null || id <= 0)
                    || selectedStudentIds.stream().distinct().count()
                            != selectedStudentIds.size()) {
                throw new IllegalArgumentException(
                        "Select one or more valid recipients.");
            }
            recipients = studentRepository.findStudentIdsByIdIn(
                    selectedStudentIds);
            if (recipients.size() != selectedStudentIds.size()) {
                throw new IllegalArgumentException(
                        "One or more selected students are unavailable.");
            }
        }
        List<Long> sortedRecipients = recipients.stream()
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        String fingerprint = announcementFingerprint(
                normalizedTitle, normalizedMessage, sortedRecipients);

        try {
            Integer count = announcementTransaction.execute(status ->
                    createAnnouncementTransactionally(
                            normalizedRequestId,
                            normalizedTitle,
                            normalizedMessage,
                            sortedRecipients,
                            fingerprint));
            return count == null ? 0 : count;
        } catch (DataIntegrityViolationException exception) {
            Optional<AnnouncementRequest> committedRequest =
                    announcementRequestRepository.findById(normalizedRequestId);
            if (committedRequest.isEmpty()) {
                throw exception;
            }
            return resolveRetry(committedRequest.get(), fingerprint);
        }
    }

    public AnnouncementDeliverySummary sendPendingAnnouncementEmails(
            String requestId) {
        if (!validUuid(requestId)) {
            throw new IllegalArgumentException("Invalid announcement request.");
        }
        String normalizedRequestId = UUID.fromString(requestId).toString();
        List<AnnouncementDelivery> deliveries =
                announcementDeliveryRepository
                        .findByAnnouncementRequestRequestIdOrderByStudentIdAsc(
                                normalizedRequestId);
        List<Long> pendingStudentIds = deliveries.stream()
                .filter(delivery -> delivery.getStatus()
                        == AnnouncementDeliveryStatus.PENDING)
                .map(AnnouncementDelivery::getStudentId)
                .toList();
        Map<Long, String> emailByStudentId = pendingStudentIds.isEmpty()
                ? Map.of()
                : studentRepository.findEmailOptionsByIdIn(pendingStudentIds)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        StudentRepository.StudentEmailOption::getId,
                        StudentRepository.StudentEmailOption::getEmail));
        Map<Long, AppNotification> notificationByStudentId =
                notificationRepository.findByRecipientTypeAndEventKey(
                                STUDENT_RECIPIENT,
                                "announcement:" + normalizedRequestId)
                        .stream()
                        .collect(java.util.stream.Collectors.toMap(
                                AppNotification::getRecipientId,
                                notification -> notification));
        Map<Long, StudentNotificationPreference> preferencesByStudentId =
                preferenceService.loadForStudents(pendingStudentIds);

        for (AnnouncementDelivery delivery : deliveries) {
            if (delivery.getStatus() != AnnouncementDeliveryStatus.PENDING) {
                continue;
            }
            if (!preferenceService.emailEnabled(
                    preferencesByStudentId.get(delivery.getStudentId()),
                    NotificationType.ADMIN_ANNOUNCEMENT)) {
                announcementTransaction.execute(status ->
                        announcementDeliveryRepository.suppressPending(
                                delivery.getId(),
                                AnnouncementDeliveryStatus.PENDING,
                                AnnouncementDeliveryStatus.SUPPRESSED,
                                "Email notifications disabled by student."));
                continue;
            }
            Integer claimed = announcementTransaction.execute(status ->
                    announcementDeliveryRepository.claimPending(
                            delivery.getId(),
                            AnnouncementDeliveryStatus.PENDING,
                            AnnouncementDeliveryStatus.SENDING));
            if (claimed == null || claimed != 1) {
                continue;
            }

            String email = emailByStudentId.get(delivery.getStudentId());
            AppNotification announcement =
                    notificationByStudentId.get(delivery.getStudentId());
            String title = delivery.getTitle() != null
                    ? delivery.getTitle()
                    : announcement == null ? null : announcement.getTitle();
            String message = delivery.getMessage() != null
                    ? delivery.getMessage()
                    : announcement == null ? null : announcement.getMessage();
            if (email == null || email.isBlank()
                    || title == null || message == null) {
                markEmailFailed(
                        delivery.getId(),
                        "Recipient or announcement details are unavailable.");
                continue;
            }

            try {
                emailService.send(new EmailMessage(
                        email,
                        "Quizzy announcement: " + title,
                        title + "\n\n"
                                + message
                                + "\n\nQuizzy"));
                announcementTransaction.execute(status ->
                        announcementDeliveryRepository.markSent(
                                delivery.getId(),
                                AnnouncementDeliveryStatus.SENDING,
                                AnnouncementDeliveryStatus.SENT));
            } catch (EmailDeliveryException exception) {
                markEmailFailed(
                        delivery.getId(),
                        "Email service reported delivery failure.");
            }
        }

        List<AnnouncementDelivery> current =
                announcementDeliveryRepository
                        .findByAnnouncementRequestRequestIdOrderByStudentIdAsc(
                                normalizedRequestId);
        long sent = current.stream()
                .filter(delivery -> delivery.getStatus()
                        == AnnouncementDeliveryStatus.SENT)
                .count();
        long failed = current.stream()
                .filter(delivery -> delivery.getStatus()
                        == AnnouncementDeliveryStatus.FAILED)
                .count();
        long pending = current.stream()
                .filter(delivery -> delivery.getStatus()
                        == AnnouncementDeliveryStatus.PENDING
                        || delivery.getStatus()
                        == AnnouncementDeliveryStatus.SENDING)
                .count();
        return new AnnouncementDeliverySummary(
                normalizedRequestId, current.size(), sent, failed, pending);
    }

    private void markEmailFailed(Long deliveryId, String reason) {
        announcementTransaction.execute(status ->
                announcementDeliveryRepository.markFailed(
                        deliveryId,
                        AnnouncementDeliveryStatus.SENDING,
                        AnnouncementDeliveryStatus.FAILED,
                        reason));
    }

    private int createAnnouncementTransactionally(
            String requestId,
            String title,
            String message,
            List<Long> recipients,
            String fingerprint) {
        Optional<AnnouncementRequest> existing =
                announcementRequestRepository.findById(requestId);
        if (existing.isPresent()) {
            return resolveRetry(existing.get(), fingerprint);
        }

        String eventKey = "announcement:" + requestId;
        List<AppNotification> existingNotifications =
                notificationRepository.findByRecipientTypeAndEventKey(
                        STUDENT_RECIPIENT, eventKey);
        if (!existingNotifications.isEmpty()) {
            boolean sameAnnouncement = existingNotifications.stream()
                    .allMatch(notification ->
                            notification.getTitle().equals(title)
                                    && notification.getMessage().equals(message));
            List<Long> existingRecipients = existingNotifications.stream()
                    .map(AppNotification::getRecipientId)
                    .distinct()
                    .sorted()
                    .toList();
            if (!sameAnnouncement || !existingRecipients.equals(recipients)) {
                throw new IllegalArgumentException(
                        "This request ID was already used for another announcement.");
            }
            int count = existingNotifications.size();
            AnnouncementRequest request =
                    announcementRequestRepository.saveAndFlush(
                            new AnnouncementRequest(
                                    requestId, fingerprint, count, title, message));
            savePendingDeliveries(
                            request, existingRecipients, title, message);
            return count;
        }

        int createdCount = recipients.size();
        AnnouncementRequest request =
                announcementRequestRepository.saveAndFlush(
                        new AnnouncementRequest(
                                requestId,
                                fingerprint,
                                createdCount,
                                title,
                                message));
        List<AppNotification> notifications = new ArrayList<>(createdCount);
        List<AnnouncementDelivery> deliveries = new ArrayList<>(createdCount);
        Map<Long, StudentNotificationPreference> preferencesByStudentId =
                preferenceService.loadForStudents(recipients);
        for (Long recipientId : recipients) {
            if (preferenceService.inAppEnabled(
                            preferencesByStudentId.get(recipientId),
                            NotificationType.ADMIN_ANNOUNCEMENT)) {
                        notifications.add(new AppNotification(
                                recipientId,
                                eventKey,
                                title,
                                message,
                                NotificationType.ADMIN_ANNOUNCEMENT,
                                "/notifications",
                                null));
            }
            deliveries.add(new AnnouncementDelivery(
                            request, recipientId, title, message));
        }
        notificationRepository.saveAll(notifications);
        announcementDeliveryRepository.saveAll(deliveries);
        return createdCount;
    }

    private void savePendingDeliveries(
            AnnouncementRequest request,
            List<Long> recipients,
            String title,
            String message) {
        List<AnnouncementDelivery> deliveries = recipients.stream()
                .map(recipientId ->
                        new AnnouncementDelivery(
                                request, recipientId, title, message))
                .toList();
        announcementDeliveryRepository.saveAll(deliveries);
    }

    private int resolveRetry(
            AnnouncementRequest existing,
            String fingerprint) {
        if (!existing.getRequestFingerprint().equals(fingerprint)) {
            throw new IllegalArgumentException(
                    "This request ID was already used for another announcement.");
        }
        return existing.getCreatedCount();
    }

    private String announcementFingerprint(
            String title,
            String message,
            List<Long> recipients) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, title);
            updateDigest(digest, message);
            for (Long recipient : recipients) {
                updateDigest(digest, recipient.toString());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable.", exception);
        }
    }

    private void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(bytes.length)
                .array());
        digest.update(bytes);
    }

    @Transactional(readOnly = true)
    public List<AnnouncementHistory> announcementHistory() {
        List<AnnouncementRequest> requests =
                announcementRequestRepository.findTop100ByOrderByCreatedAtDesc();
        if (requests.isEmpty()) {
            return List.of();
        }
        List<String> eventKeys = requests.stream()
                .map(request -> "announcement:" + request.getRequestId())
                .toList();
        Map<String, AppNotification> notificationByEvent =
                notificationRepository.findByRecipientTypeAndEventKeyIn(
                                STUDENT_RECIPIENT, eventKeys)
                        .stream()
                        .collect(java.util.stream.Collectors.toMap(
                                AppNotification::getEventKey,
                                notification -> notification,
                                (first, ignored) -> first));
        return requests.stream()
                .map(request -> {
                    AppNotification legacyNotification =
                            notificationByEvent.get(
                                    "announcement:" + request.getRequestId());
                    String title = request.getTitle() != null
                            ? request.getTitle()
                            : legacyNotification == null
                                    ? "Announcement"
                                    : legacyNotification.getTitle();
                    String message = request.getMessage() != null
                            ? request.getMessage()
                            : legacyNotification == null
                                    ? "Announcement details are unavailable."
                                    : legacyNotification.getMessage();
                    return new AnnouncementHistory(
                            title,
                            message,
                            request.getCreatedAt(),
                            request.getCreatedCount());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AnnouncementDeliveryHistory> announcementDeliveryHistory() {
        List<AnnouncementRequest> requests =
                announcementRequestRepository.findTop20ByOrderByCreatedAtDesc();
        if (requests.isEmpty()) {
            return List.of();
        }
        List<String> requestIds = requests.stream()
                .map(AnnouncementRequest::getRequestId)
                .toList();
        Map<String, List<AnnouncementDelivery>> deliveriesByRequest =
                announcementDeliveryRepository
                        .findByAnnouncementRequestRequestIdInOrderByStudentIdAsc(
                                requestIds)
                        .stream()
                        .collect(java.util.stream.Collectors.groupingBy(
                                AnnouncementDelivery::getRequestId));
        return requests.stream()
                .filter(request -> deliveriesByRequest.containsKey(
                        request.getRequestId()))
                .map(request -> new AnnouncementDeliveryHistory(
                        request.getRequestId(),
                        request.getCreatedAt(),
                        deliveriesByRequest.get(request.getRequestId())
                                .stream()
                                .map(delivery ->
                                        new RecipientDeliveryHistory(
                                                delivery.getStudentId(),
                                                delivery.getStatus(),
                                                delivery.getCreatedAt(),
                                                delivery.getLastAttemptAt(),
                                                delivery.getSentAt(),
                                                delivery.getFailureReason()))
                                .toList()))
                .toList();
    }

    @Transactional
    public void notifySupportReply(
            Long studentId,
            Long conversationId,
            Long messageId) {
        createStudentNotification(
                studentId,
                "support-message:" + messageId,
                "New support reply",
                "An administrator replied to your support conversation.",
                NotificationType.SUPPORT_MESSAGE,
                "/support/" + conversationId,
                messageId);
    }

    public Optional<String> safeInternalUrl(String targetUrl) {
        return isSafeInternalUrl(targetUrl)
                ? Optional.of(targetUrl)
                : Optional.empty();
    }

    private boolean isSafeInternalUrl(String targetUrl) {
        if (targetUrl == null || targetUrl.isBlank()
                || targetUrl.length() > 500
                || !targetUrl.startsWith("/")
                || targetUrl.startsWith("//")
                || targetUrl.startsWith("/\\")
                || targetUrl.contains("\\")
                || targetUrl.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        try {
            URI uri = URI.create(targetUrl);
            return uri.getScheme() == null
                    && uri.getRawAuthority() == null
                    && uri.getPath() != null
                    && uri.getPath().startsWith("/");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean validText(String value, int maxLength) {
        return value != null
                && !value.isBlank()
                && value.trim().length() <= maxLength;
    }

    private boolean validUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(
                    value.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public record NotificationPage(
            List<NotificationView> notifications,
            long unreadCount,
            int currentPage,
            int totalPages) {
    }

    public record NotificationView(
            AppNotification notification,
            String safeTargetUrl) {
    }

    public record AnnouncementHistory(
            String title,
            String message,
            LocalDateTime createdAt,
            long recipientCount) {
    }

    public record AnnouncementDeliverySummary(
            String requestId,
            int total,
            long sent,
            long failed,
            long pending) {
    }

    public record AnnouncementDeliveryHistory(
            String requestId,
            LocalDateTime createdAt,
            List<RecipientDeliveryHistory> deliveries) {
    }

    public record RecipientDeliveryHistory(
            Long studentId,
            AnnouncementDeliveryStatus status,
            LocalDateTime createdAt,
            LocalDateTime lastAttemptAt,
            LocalDateTime sentAt,
            String failureReason) {
    }
}
