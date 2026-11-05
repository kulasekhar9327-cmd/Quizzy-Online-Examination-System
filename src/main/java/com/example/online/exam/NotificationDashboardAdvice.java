package com.example.online.exam;

import com.example.online.exam.service.ExamAttemptSession;
import com.example.online.exam.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.ui.Model;

@ControllerAdvice
public class NotificationDashboardAdvice {

    private final NotificationService notificationService;

    public NotificationDashboardAdvice(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @ModelAttribute
    public void addDashboardNotificationCount(
            HttpServletRequest request,
            Model model) {
        if (!"/dashboard".equals(request.getServletPath())) {
            return;
        }
        HttpSession session = request.getSession(false);
        Long studentId = ExamAttemptSession.studentId(session);
        if (studentId != null) {
            model.addAttribute(
                    "unreadNotificationCount",
                    notificationService.unreadCount(studentId));
        }
    }
}
