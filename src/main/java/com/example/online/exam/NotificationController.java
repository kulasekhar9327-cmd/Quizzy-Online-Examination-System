package com.example.online.exam;

import com.example.online.exam.repository.StudentRepository;
import com.example.online.exam.service.ExamAttemptSession;
import com.example.online.exam.service.NotificationService;
import com.example.online.exam.service.NotificationService.AnnouncementDeliverySummary;
import com.example.online.exam.service.NotificationService.NotificationPage;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

@Controller
public class NotificationController {

    private static final int MAX_ANNOUNCEMENT_TITLE = 120;
    private static final int MAX_ANNOUNCEMENT_MESSAGE = 1000;

    private final NotificationService notificationService;
    private final StudentRepository studentRepository;

    public NotificationController(
            NotificationService notificationService,
            StudentRepository studentRepository) {
        this.notificationService = notificationService;
        this.studentRepository = studentRepository;
    }

    @GetMapping("/notifications")
    public String showNotifications(
            HttpSession session,
            @RequestParam(defaultValue = "0") int page,
            Model model) {
        Long studentId = studentId(session);
        if (studentId == null) {
            return "redirect:/login";
        }
        NotificationPage notifications =
                notificationService.studentNotifications(studentId, page);
        model.addAttribute("notifications", notifications.notifications());
        model.addAttribute("unreadCount", notifications.unreadCount());
        model.addAttribute("currentPage", notifications.currentPage());
        model.addAttribute("totalPages", notifications.totalPages());
        return "notifications";
    }

    @PostMapping("/notifications/{notificationId}/read")
    public String markRead(
            @PathVariable Long notificationId,
            HttpSession session) {
        Long studentId = studentId(session);
        if (studentId == null) {
            return "redirect:/login";
        }
        requireOwned(notificationId, studentId, true);
        return "redirect:/notifications";
    }

    @PostMapping("/notifications/{notificationId}/unread")
    public String markUnread(
            @PathVariable Long notificationId,
            HttpSession session) {
        Long studentId = studentId(session);
        if (studentId == null) {
            return "redirect:/login";
        }
        requireOwned(notificationId, studentId, false);
        return "redirect:/notifications";
    }

    @PostMapping("/notifications/read-all")
    public String markAllRead(HttpSession session) {
        Long studentId = studentId(session);
        if (studentId == null) {
            return "redirect:/login";
        }
        notificationService.markAllRead(studentId);
        return "redirect:/notifications";
    }

    @GetMapping("/admin/announcements")
    public String showAnnouncements(HttpSession session, Model model) {
        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }
        loadAnnouncementPage(model, UUID.randomUUID().toString());
        return "admin-announcements";
    }

    @PostMapping("/admin/announcements")
    public String createAnnouncement(
            HttpSession session,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String message,
            @RequestParam(defaultValue = "all") String recipient,
            @RequestParam(required = false) List<String> recipientIds,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }

        String normalizedTitle = title == null ? "" : title.trim();
        String normalizedMessage = message == null ? "" : message.trim();
        Long recipientId = "selected".equalsIgnoreCase(recipient)
                ? null
                : parseRecipient(recipient);
        List<Long> selectedStudentIds = "selected".equalsIgnoreCase(recipient)
                ? parseRecipientIds(recipientIds)
                : recipientId == null ? null : List.of(recipientId);
        if (!validText(normalizedTitle, MAX_ANNOUNCEMENT_TITLE)
                || !validText(normalizedMessage, MAX_ANNOUNCEMENT_MESSAGE)
                || INVALID_RECIPIENT.equals(recipientId)
                || ("selected".equalsIgnoreCase(recipient)
                        && selectedStudentIds == null)) {
            model.addAttribute(
                    "error",
                    "Enter a title of 1 to " + MAX_ANNOUNCEMENT_TITLE
                            + " characters, a message of 1 to "
                            + MAX_ANNOUNCEMENT_MESSAGE
                            + " characters, and select valid recipients.");
            loadAnnouncementPage(model, requestId);
            return "admin-announcements";
        }

        try {
            int createdCount = notificationService.createAnnouncementForRecipients(
                    requestId,
                    normalizedTitle,
                    normalizedMessage,
                    selectedStudentIds);
            AnnouncementDeliverySummary deliverySummary =
                    notificationService.sendPendingAnnouncementEmails(requestId);
            redirectAttributes.addFlashAttribute(
                    "success",
                    createdCount + " in-app notification(s) recorded. Email "
                            + "status — sent: " + deliverySummary.sent()
                            + ", failed: " + deliverySummary.failed()
                            + ", pending: " + deliverySummary.pending() + ".");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    "The announcement or selected recipient is not valid.");
        }
        return "redirect:/admin/announcements";
    }

    private static final Long INVALID_RECIPIENT = -1L;

    private Long parseRecipient(String recipient) {
        if (recipient == null || recipient.isBlank()
                || "all".equalsIgnoreCase(recipient)) {
            return null;
        }
        try {
            long id = Long.parseLong(recipient);
            return id > 0 ? id : INVALID_RECIPIENT;
        } catch (NumberFormatException exception) {
            return INVALID_RECIPIENT;
        }
    }

    private List<Long> parseRecipientIds(List<String> recipientIds) {
        if (recipientIds == null || recipientIds.isEmpty()) {
            return null;
        }
        try {
            List<Long> parsed = recipientIds.stream()
                    .map(Long::parseLong)
                    .toList();
            if (parsed.stream().anyMatch(id -> id <= 0)
                    || parsed.stream().distinct().count() != parsed.size()) {
                return null;
            }
            return parsed;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void loadAnnouncementPage(Model model, String requestId) {
        model.addAttribute(
                "students", studentRepository.findAllStudentOptions());
        model.addAttribute(
                "announcementHistory",
                notificationService.announcementHistory());
        model.addAttribute(
                "deliveryHistory",
                notificationService.announcementDeliveryHistory());
        model.addAttribute(
                "requestId",
                isUuid(requestId) ? requestId : UUID.randomUUID().toString());
    }

    private void requireOwned(
            Long notificationId,
            Long studentId,
            boolean read) {
        if (!notificationService.markRead(notificationId, studentId, read)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Notification not found.");
        }
    }

    private Long studentId(HttpSession session) {
        return ExamAttemptSession.studentId(session);
    }

    private boolean isAdmin(HttpSession session) {
        return session != null
                && Boolean.TRUE.equals(session.getAttribute("quizzyAdmin"));
    }

    private boolean validText(String value, int maxLength) {
        return value != null
                && !value.isBlank()
                && value.length() <= maxLength;
    }

    private boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

}
