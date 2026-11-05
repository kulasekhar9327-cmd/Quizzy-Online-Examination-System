
package com.example.online.exam;

import com.example.online.exam.entity.ExamSchedule;
import com.example.online.exam.repository.ExamScheduleRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

@Controller
public class AdminController {

    private final ExamScheduleRepository examScheduleRepository;

    @Value("${quizzy.admin.username}")
    private String adminUsername;

    @Value("${quizzy.admin.password:}")
    private String adminPassword;

    public AdminController(
            ExamScheduleRepository examScheduleRepository) {
        this.examScheduleRepository = examScheduleRepository;
    }

    @GetMapping("/admin/login")
    public String showAdminLogin() {
        return "admin-login";
    }

    @PostMapping("/admin/login")
    public String adminLogin(
            @RequestParam String username,
            @RequestParam String password,
            HttpServletRequest request,
            Model model) {

        if (adminPassword == null || adminPassword.isBlank()) {
            model.addAttribute(
                    "error",
                    "Admin password is not configured. Contact the administrator.");
            return "admin-login";
        }

        if (!adminUsername.equals(username.trim())
                || !adminPassword.equals(password)) {
            model.addAttribute("error", "Invalid admin username or password.");
            return "admin-login";
        }

        HttpSession oldSession = request.getSession(false);
        if (oldSession != null) {
            oldSession.invalidate();
        }

        HttpSession session = request.getSession(true);
        session.setAttribute("quizzyAdmin", true);

        return "redirect:/admin/dashboard";
    }

    @GetMapping("/admin/schedules")
    public String showSchedules(HttpSession session, Model model) {
        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }

        loadSchedules(model);
        return "admin-schedules";
    }

    @PostMapping("/admin/schedules")
    public String createSchedule(
            HttpSession session,
            @RequestParam String examName,
            @RequestParam String subject,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate examDate,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
            LocalTime examTime,
            @RequestParam(defaultValue = "") String description,
            @RequestParam(defaultValue = "30") Integer durationMinutes,
            @RequestParam(defaultValue = "25") Integer totalMarks,
            Model model) {

        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }

        if (!validDetails(examName, subject, examDate, examTime)
                || !examDate.isAfter(LocalDate.now())
                || !validExamSettings(durationMinutes, totalMarks)) {

            model.addAttribute(
                    "error",
                    "Enter valid details, choose a future date, set duration to 1-300 minutes and total marks to 1-1000.");

            loadSchedules(model);
            return "admin-schedules";
        }

        ExamSchedule schedule = new ExamSchedule(
                examName.trim(),
                subject.trim(),
                examDate,
                examTime,
                description.trim());

        schedule.setDurationMinutes(durationMinutes);
        schedule.setTotalMarks(totalMarks);

        examScheduleRepository.save(schedule);

        return "redirect:/admin/schedules?success";
    }

    @GetMapping("/admin/schedules/{id}/edit")
    public String showEditForm(
            @PathVariable Long id,
            HttpSession session,
            Model model) {

        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }

        Optional<ExamSchedule> result =
                examScheduleRepository.findById(id);

        if (result.isEmpty()) {
            return "redirect:/admin/schedules?notfound";
        }

        loadSchedules(model);
        model.addAttribute("editSchedule", result.get());

        return "admin-schedules";
    }

    @PostMapping("/admin/schedules/{id}/edit")
    public String updateSchedule(
            @PathVariable Long id,
            HttpSession session,
            @RequestParam String examName,
            @RequestParam String subject,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate examDate,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
            LocalTime examTime,
            @RequestParam(defaultValue = "") String description,
            @RequestParam(defaultValue = "30") Integer durationMinutes,
            @RequestParam(defaultValue = "25") Integer totalMarks,
            Model model) {

        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }

        Optional<ExamSchedule> result =
                examScheduleRepository.findById(id);

        if (result.isEmpty()) {
            return "redirect:/admin/schedules?notfound";
        }

        if (!validDetails(examName, subject, examDate, examTime)
                || !examDate.isAfter(LocalDate.now())
                || !validExamSettings(durationMinutes, totalMarks)) {

            model.addAttribute(
                    "error",
                    "Enter valid details, choose a future date, set duration to 1-300 minutes and total marks to 1-1000.");

            loadSchedules(model);
            model.addAttribute("editSchedule", result.get());

            return "admin-schedules";
        }

        ExamSchedule schedule = result.get();

        schedule.setExamName(examName.trim());
        schedule.setSubject(subject.trim());
        schedule.setExamDate(examDate);
        schedule.setExamTime(examTime);
        schedule.setDescription(description.trim());
        schedule.setDurationMinutes(durationMinutes);
        schedule.setTotalMarks(totalMarks);

        examScheduleRepository.save(schedule);

        return "redirect:/admin/schedules?updated";
    }

    @PostMapping("/admin/schedules/{id}/delete")
    public String deleteSchedule(
            @PathVariable Long id,
            HttpSession session) {

        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }

        if (!examScheduleRepository.existsById(id)) {
            return "redirect:/admin/schedules?notfound";
        }

        examScheduleRepository.deleteById(id);

        return "redirect:/admin/schedules?deleted";
    }

    @PostMapping("/admin/logout")
    public String adminLogout(HttpSession session) {
        if (session != null) {
            session.invalidate();
        }

        return "redirect:/admin/login";
    }

    private boolean validDetails(
            String examName,
            String subject,
            LocalDate examDate,
            LocalTime examTime) {

        return examName != null && !examName.isBlank()
                && subject != null && !subject.isBlank()
                && examDate != null
                && examTime != null;
    }

    private boolean validExamSettings(
            Integer durationMinutes,
            Integer totalMarks) {

        return durationMinutes != null
                && durationMinutes >= 1
                && durationMinutes <= 300
                && totalMarks != null
                && totalMarks >= 1
                && totalMarks <= 1000;
    }

    private void loadSchedules(Model model) {
        model.addAttribute(
                "schedules",
                examScheduleRepository
                        .findByExamDateGreaterThanEqualOrderByExamDateAscExamTimeAsc(
                                LocalDate.now()));
    }

    private boolean isAdmin(HttpSession session) {
        return session != null
                && Boolean.TRUE.equals(session.getAttribute("quizzyAdmin"));
    }
}
