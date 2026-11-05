package com.example.online.exam;

import com.example.online.exam.entity.SupportCategory;
import com.example.online.exam.entity.SupportStatus;
import com.example.online.exam.service.ExamAttemptSession;
import com.example.online.exam.service.SupportService;
import com.example.online.exam.service.SupportService.ConversationDetail;
import com.example.online.exam.service.SupportService.ConversationSummary;
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
import java.util.Optional;

@Controller
public class SupportController {

    private static final int MAX_SUBJECT_LENGTH = 150;
    private static final int MAX_MESSAGE_LENGTH = 4000;

    private final SupportService supportService;

    public SupportController(SupportService supportService) {
        this.supportService = supportService;
    }

    @GetMapping("/support")
    public String showStudentSupport(
            HttpSession session,
            Model model) {
        Long studentId = studentId(session);
        if (studentId == null) {
            return "redirect:/login";
        }
        model.addAttribute(
                "conversations", supportService.studentConversations(studentId));
        return "support-list";
    }

    @GetMapping("/support/new")
    public String showNewConversation(
            HttpSession session,
            Model model) {
        if (studentId(session) == null) {
            return "redirect:/login";
        }
        model.addAttribute("categories", SupportCategory.values());
        return "support-new";
    }

    @PostMapping("/support")
    public String createConversation(
            HttpSession session,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) String message,
            RedirectAttributes redirectAttributes) {
        Long studentId = studentId(session);
        if (studentId == null) {
            return "redirect:/login";
        }

        SupportCategory parsedCategory = parseCategory(category);
        String normalizedSubject = subject == null ? "" : subject.trim();
        String normalizedMessage = message == null ? "" : message.trim();
        if (parsedCategory == null
                || normalizedSubject.isEmpty()
                || normalizedSubject.length() > MAX_SUBJECT_LENGTH
                || !validMessage(normalizedMessage)) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    "Choose a valid category, enter a subject of up to "
                            + MAX_SUBJECT_LENGTH
                            + " characters, and enter a message of 1 to "
                            + MAX_MESSAGE_LENGTH + " characters.");
            return "redirect:/support/new";
        }

        try {
            Long conversationId = supportService.createConversation(
                    studentId, normalizedSubject, parsedCategory, normalizedMessage);
            return "redirect:/support/" + conversationId;
        } catch (IllegalStateException exception) {
            session.invalidate();
            return "redirect:/login";
        }
    }

    @GetMapping("/support/{conversationId}")
    public String showStudentConversation(
            @PathVariable Long conversationId,
            HttpSession session,
            Model model) {
        Long studentId = studentId(session);
        if (studentId == null) {
            return "redirect:/login";
        }
        ConversationDetail detail = supportService
                .studentConversation(conversationId, studentId)
                .orElseThrow(SupportController::conversationNotFound);
        addStudentDetailModel(model, detail);
        return "support-detail";
    }

    @PostMapping("/support/{conversationId}/messages")
    public String sendStudentMessage(
            @PathVariable Long conversationId,
            HttpSession session,
            @RequestParam(required = false) String message,
            RedirectAttributes redirectAttributes) {
        Long studentId = studentId(session);
        if (studentId == null) {
            return "redirect:/login";
        }
        String normalizedMessage = message == null ? "" : message.trim();
        if (!validMessage(normalizedMessage)) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    "Enter a message of 1 to " + MAX_MESSAGE_LENGTH
                            + " characters.");
            return "redirect:/support/" + conversationId;
        }
        Optional<Boolean> sent = supportService.sendStudentMessage(
                conversationId, studentId, normalizedMessage);
        if (sent.isEmpty()) {
            throw conversationNotFound();
        }
        if (!sent.get()) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    "This conversation is resolved or closed. Contact support "
                            + "to request that it be reopened.");
        }
        return "redirect:/support/" + conversationId;
    }

    @GetMapping("/admin/support")
    public String showAdminSupport(
            HttpSession session,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String category,
            Model model) {
        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }

        SupportStatus selectedStatus = parseStatus(status);
        SupportCategory selectedCategory = parseCategory(category);
        if ((!status.isBlank() && selectedStatus == null)
                || (!category.isBlank() && selectedCategory == null)) {
            model.addAttribute(
                    "error",
                    "The selected support filter is not valid.");
        }
        List<ConversationSummary> conversations =
                supportService.adminConversations(
                        selectedStatus, selectedCategory);
        model.addAttribute("conversations", conversations);
        model.addAttribute("statuses", SupportStatus.values());
        model.addAttribute("categories", SupportCategory.values());
        model.addAttribute("selectedStatus", selectedStatus);
        model.addAttribute("selectedCategory", selectedCategory);
        return "admin-support";
    }

    @GetMapping("/admin/support/{conversationId}")
    public String showAdminConversation(
            @PathVariable Long conversationId,
            HttpSession session,
            Model model) {
        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }
        ConversationDetail detail = supportService
                .adminConversation(conversationId)
                .orElseThrow(SupportController::conversationNotFound);
        model.addAttribute("conversation", detail.conversation());
        model.addAttribute("messages", detail.messages());
        model.addAttribute("statuses", SupportStatus.values());
        return "admin-support-detail";
    }

    @PostMapping("/admin/support/{conversationId}/messages")
    public String sendAdminMessage(
            @PathVariable Long conversationId,
            HttpSession session,
            @RequestParam(required = false) String message,
            RedirectAttributes redirectAttributes) {
        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }
        String normalizedMessage = message == null ? "" : message.trim();
        if (!validMessage(normalizedMessage)) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    "Enter a message of 1 to " + MAX_MESSAGE_LENGTH
                            + " characters.");
            return "redirect:/admin/support/" + conversationId;
        }
        Optional<Boolean> sent = supportService.sendAdminMessage(
                conversationId, normalizedMessage);
        if (sent.isEmpty()) {
            throw conversationNotFound();
        }
        if (!sent.get()) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    "Reopen this conversation before sending a reply.");
        }
        return "redirect:/admin/support/" + conversationId;
    }

    @PostMapping("/admin/support/{conversationId}/status")
    public String updateStatus(
            @PathVariable Long conversationId,
            HttpSession session,
            @RequestParam(required = false) String status,
            RedirectAttributes redirectAttributes) {
        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }
        SupportStatus parsedStatus = parseStatus(status);
        if (parsedStatus == null) {
            redirectAttributes.addFlashAttribute(
                    "error", "Choose a valid conversation status.");
            return "redirect:/admin/support/" + conversationId;
        }
        if (!supportService.changeStatus(conversationId, parsedStatus)) {
            throw conversationNotFound();
        }
        redirectAttributes.addFlashAttribute(
                "success", "Conversation status updated.");
        return "redirect:/admin/support/" + conversationId;
    }

    private void addStudentDetailModel(
            Model model,
            ConversationDetail detail) {
        model.addAttribute("conversation", detail.conversation());
        model.addAttribute("messages", detail.messages());
    }

    private Long studentId(HttpSession session) {
        return ExamAttemptSession.studentId(session);
    }

    private boolean isAdmin(HttpSession session) {
        return session != null
                && Boolean.TRUE.equals(session.getAttribute("quizzyAdmin"));
    }

    private boolean validMessage(String message) {
        return message != null
                && !message.isBlank()
                && message.length() <= MAX_MESSAGE_LENGTH;
    }

    private SupportCategory parseCategory(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return SupportCategory.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private SupportStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return SupportStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static ResponseStatusException conversationNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Conversation not found.");
    }
}
