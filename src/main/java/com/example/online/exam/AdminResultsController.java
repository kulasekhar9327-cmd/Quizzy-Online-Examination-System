
package com.example.online.exam;

import com.example.online.exam.entity.ExamResult;
import com.example.online.exam.entity.Student;
import com.example.online.exam.repository.ExamResultRepository;
import com.example.online.exam.repository.StudentRepository;
import com.example.online.exam.service.NotificationService;
import com.example.online.exam.entity.NotificationType;

import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Controller
public class AdminResultsController {

    private final ExamResultRepository examResultRepository;
    private final StudentRepository studentRepository;
    private final NotificationService notificationService;

    public AdminResultsController(
            ExamResultRepository examResultRepository,
            StudentRepository studentRepository,
            NotificationService notificationService) {

        this.examResultRepository = examResultRepository;
        this.studentRepository = studentRepository;
        this.notificationService = notificationService;
    }

    @GetMapping("/admin/results")
    public String showResults(
            HttpSession session,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            Model model) {

        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }

        String normalizedStatus = status.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("all", "published", "pending").contains(normalizedStatus)
                || (fromDate != null && toDate != null
                        && fromDate.isAfter(toDate))) {
            model.addAttribute(
                    "errorMessage",
                    "Choose a valid publication status and date range.");
            normalizedStatus = "all";
            fromDate = null;
            toDate = null;
        }

        String normalizedSearch = search.trim().toLowerCase(Locale.ROOT);
        List<ExamResult> results =
                examResultRepository.findAllByOrderByExamDateDesc();

        List<StudentResultView> rows = new ArrayList<>();

        for (ExamResult result : results) {

            Student student = studentRepository
                    .findById(result.getStudentId())
                    .orElse(null);

            StudentResultView row = new StudentResultView(
                    result.getId(),
                    student != null
                            ? student.getFullName()
                            : "Unknown student",
                    student != null
                            ? student.getEmail()
                            : "—",
                    result.getSubject(),
                    result.getScore(),
                    result.getTotalQuestions(),
                    result.getPercentage(),
                    result.getExamDate(),
                    result.isPublished(),
                    result.getPublishedAt()
            );
            LocalDate examDate = result.getExamDate() == null
                    ? null
                    : result.getExamDate().toLocalDate();
            boolean matchesSearch = normalizedSearch.isEmpty()
                    || containsIgnoreCase(row.getStudentName(), normalizedSearch)
                    || containsIgnoreCase(row.getEmail(), normalizedSearch)
                    || containsIgnoreCase(row.getSubject(), normalizedSearch);
            boolean matchesStatus = normalizedStatus.equals("all")
                    || (normalizedStatus.equals("published")
                            == row.isPublished());
            boolean matchesDates = (fromDate == null
                            || (examDate != null && !examDate.isBefore(fromDate)))
                    && (toDate == null
                            || (examDate != null && !examDate.isAfter(toDate)));

            if (matchesSearch && matchesStatus && matchesDates) {
                rows.add(row);
            }
        }

        model.addAttribute("results", rows);
        model.addAttribute("totalResults", rows.size());
        model.addAttribute("search", search);
        model.addAttribute("status", normalizedStatus);
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);

        return "admin-results";
    }

    @PostMapping("/admin/results/{id}/publish")
    @Transactional
    public String publishResult(
            @PathVariable Long id,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }

        ExamResult result = examResultRepository.findById(id)
                .orElse(null);

        if (result == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage", "Result not found.");
            return "redirect:/admin/results";
        }

        if (result.isPublished()) {
            redirectAttributes.addFlashAttribute(
                    "successMessage", "This result is already published.");
            return "redirect:/admin/results";
        }

        result.publishAt(LocalDateTime.now());
        examResultRepository.save(result);
        createPublicationNotification(result);

        redirectAttributes.addFlashAttribute(
                "successMessage", "Result published successfully.");

        return "redirect:/admin/results";
    }

    @PostMapping("/admin/results/publish")
    @Transactional
    public String publishSelectedResults(
            @RequestParam(name = "resultIds", required = false)
            List<Long> resultIds,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }

        if (resultIds == null || resultIds.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage", "Select at least one result to publish.");
            return "redirect:/admin/results";
        }

        Set<Long> uniqueIds = new HashSet<>(resultIds);
        List<ExamResult> selectedResults =
                examResultRepository.findAllById(uniqueIds);
        if (selectedResults.size() != uniqueIds.size()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage", "One or more selected results were not found.");
            return "redirect:/admin/results";
        }

        LocalDateTime publishedAt = LocalDateTime.now();
        int publishedCount = 0;
        for (ExamResult result : selectedResults) {
            if (result.publishAt(publishedAt)) {
                publishedCount++;
                createPublicationNotification(result);
            }
        }

        examResultRepository.saveAll(selectedResults);
        redirectAttributes.addFlashAttribute(
                "successMessage",
                publishedCount == 0
                        ? "All selected results were already published."
                        : publishedCount + " result(s) published successfully.");
        return "redirect:/admin/results";
    }

    private void createPublicationNotification(ExamResult result) {
        notificationService.createStudentNotification(
                result.getStudentId(),
                "result-published:" + result.getId(),
                "Exam result published",
                "Your result for " + result.getSubject() + " is now available.",
                NotificationType.RESULT_PUBLISHED,
                "/dashboard#history",
                result.getId());
    }

    private boolean containsIgnoreCase(String value, String search) {
        return value != null
                && value.toLowerCase(Locale.ROOT).contains(search);
    }

    private boolean isAdmin(HttpSession session) {
        return session != null
                && Boolean.TRUE.equals(
                        session.getAttribute("quizzyAdmin"));
    }

    public static class StudentResultView {

        private final Long id;
        private final String studentName;
        private final String email;
        private final String subject;
        private final int score;
        private final int totalQuestions;
        private final int percentage;
        private final LocalDateTime examDate;
        private final boolean published;
        private final LocalDateTime publishedAt;

        public StudentResultView(
                Long id,
                String studentName,
                String email,
                String subject,
                int score,
                int totalQuestions,
                int percentage,
                LocalDateTime examDate,
                boolean published,
                LocalDateTime publishedAt) {

            this.id = id;
            this.studentName = studentName;
            this.email = email;
            this.subject = subject;
            this.score = score;
            this.totalQuestions = totalQuestions;
            this.percentage = percentage;
            this.examDate = examDate;
            this.published = published;
            this.publishedAt = publishedAt;
        }

        public Long getId() {
            return id;
        }

        public String getStudentName() {
            return studentName;
        }

        public String getEmail() {
            return email;
        }

        public String getSubject() {
            return subject;
        }

        public int getScore() {
            return score;
        }

        public int getTotalQuestions() {
            return totalQuestions;
        }

        public int getPercentage() {
            return percentage;
        }

        public LocalDateTime getExamDate() {
            return examDate;
        }

        public boolean isPublished() {
            return published;
        }

        public LocalDateTime getPublishedAt() {
            return publishedAt;
        }
    }
}
