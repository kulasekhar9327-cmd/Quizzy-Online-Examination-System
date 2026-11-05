package com.example.online.exam;

import com.example.online.exam.entity.StudentNotificationPreference;
import com.example.online.exam.service.ExamAttemptSession;
import com.example.online.exam.service.NotificationPreferenceService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class StudentNotificationPreferencesController {

    private final NotificationPreferenceService preferenceService;

    public StudentNotificationPreferencesController(
            NotificationPreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    @GetMapping("/notification-preferences")
    public String showPreferences(HttpSession session, Model model) {
        Long studentId = ExamAttemptSession.studentId(session);
        if (studentId == null) {
            return "redirect:/login";
        }
        model.addAttribute(
                "preferences",
                preferenceService.getOrDefault(studentId));
        return "notification-preferences";
    }

    @PostMapping("/notification-preferences")
    public String savePreferences(
            HttpSession session,
            @RequestParam(required = false, defaultValue = "false")
            boolean examEmailEnabled,
            @RequestParam(required = false, defaultValue = "false")
            boolean resultEmailEnabled,
            @RequestParam(required = false, defaultValue = "false")
            boolean supportEmailEnabled,
            @RequestParam(required = false, defaultValue = "false")
            boolean announcementEmailEnabled,
            @RequestParam(required = false, defaultValue = "false")
            boolean inAppEnabled,
            RedirectAttributes redirectAttributes) {
        Long studentId = ExamAttemptSession.studentId(session);
        if (studentId == null) {
            return "redirect:/login";
        }
        preferenceService.save(
                studentId,
                examEmailEnabled,
                resultEmailEnabled,
                supportEmailEnabled,
                announcementEmailEnabled,
                inAppEnabled);
        redirectAttributes.addFlashAttribute(
                "success", "Your notification preferences were saved.");
        return "redirect:/notification-preferences";
    }
}
