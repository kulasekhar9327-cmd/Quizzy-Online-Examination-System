package com.example.online.exam;

import com.example.online.exam.entity.ExamResult;
import com.example.online.exam.entity.SupportConversation;
import com.example.online.exam.repository.ExamResultRepository;
import com.example.online.exam.repository.ExamScheduleRepository;
import com.example.online.exam.repository.QuestionRepository;
import com.example.online.exam.repository.StudentRepository;
import com.example.online.exam.repository.SupportConversationRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Controller
public class AdminDashboardController {

    private final StudentRepository studentRepository;
    private final QuestionRepository questionRepository;
    private final ExamScheduleRepository examScheduleRepository;
    private final ExamResultRepository examResultRepository;
    private final SupportConversationRepository supportConversationRepository;

    public AdminDashboardController(
            StudentRepository studentRepository,
            QuestionRepository questionRepository,
            ExamScheduleRepository examScheduleRepository,
            ExamResultRepository examResultRepository,
            SupportConversationRepository supportConversationRepository) {
        this.studentRepository = studentRepository;
        this.questionRepository = questionRepository;
        this.examScheduleRepository = examScheduleRepository;
        this.examResultRepository = examResultRepository;
        this.supportConversationRepository = supportConversationRepository;
    }

    @GetMapping("/admin/dashboard")
    @Transactional(readOnly = true)
    public String showDashboard(HttpSession session, Model model) {
        if (session == null
                || !Boolean.TRUE.equals(session.getAttribute("quizzyAdmin"))) {
            return "redirect:/admin/login";
        }

        long totalAttempts = examResultRepository.countValidAttempts();
        Double averagePercentage = totalAttempts == 0
                ? 0.0
                : examResultRepository.averageValidPercentage();

        model.addAttribute("totalStudents", studentRepository.count());
        model.addAttribute("totalQuestions", questionRepository.count());
        model.addAttribute("totalSchedules", examScheduleRepository.count());
        model.addAttribute("totalAttempts", totalAttempts);
        model.addAttribute(
                "totalPublishedResults",
                examResultRepository.countByPublishedTrue());
        model.addAttribute("averagePercentage", averagePercentage);
        model.addAttribute(
                "subjectMetrics",
                subjectMetrics(examResultRepository.findSubjectPerformance()));
        model.addAttribute(
                "participationTrend",
                participationTrend(examResultRepository.findDailyParticipation()));
        model.addAttribute(
                "recentAttempts",
                recentAttempts(examResultRepository.findRecentValidAttempts(
                        PageRequest.of(0, 10))));
        model.addAttribute(
                "recentPublishedResults",
                recentPublishedResults(examResultRepository
                        .findTop10ByPublishedTrueAndPublishedAtIsNotNullOrderByPublishedAtDesc()));
        model.addAttribute(
                "recentSupportActivity",
                supportActivity(supportConversationRepository
                        .findTop5ByOrderByUpdatedAtDesc()));
        return "admin-dashboard";
    }

    private List<SubjectMetric> subjectMetrics(
            List<ExamResultRepository.SubjectPerformance> rows) {
        long maxAttempts = rows.stream()
                .mapToLong(row -> valueOrZero(row.getAttempts()))
                .max()
                .orElse(0);
        return rows.stream()
                .map(row -> new SubjectMetric(
                        displaySubject(row.getSubject()),
                        valueOrZero(row.getAttempts()),
                        valueOrZero(row.getAveragePercentage()),
                        barWidth(valueOrZero(row.getAttempts()), maxAttempts)))
                .toList();
    }

    private List<TrendMetric> participationTrend(
            List<ExamResultRepository.DailyParticipation> rows) {
        List<ExamResultRepository.DailyParticipation> chronological =
                new ArrayList<>(rows);
        Collections.reverse(chronological);
        long maxAttempts = chronological.stream()
                .mapToLong(row -> valueOrZero(row.getAttempts()))
                .max()
                .orElse(0);
        return chronological.stream()
                .map(row -> new TrendMetric(
                        row.getAttemptDate(),
                        valueOrZero(row.getAttempts()),
                        chartHeight(valueOrZero(row.getAttempts()), maxAttempts)))
                .toList();
    }

    private List<RecentAttempt> recentAttempts(List<ExamResult> results) {
        return results.stream()
                .map(result -> new RecentAttempt(
                        displaySubject(result.getSubject()),
                        result.getExamDate(),
                        result.getPercentage()))
                .toList();
    }

    private List<RecentPublishedResult> recentPublishedResults(
            List<ExamResult> results) {
        return results.stream()
                .map(result -> new RecentPublishedResult(
                        displaySubject(result.getSubject()),
                        result.getPublishedAt()))
                .toList();
    }

    private List<SupportActivity> supportActivity(
            List<SupportConversation> conversations) {
        return conversations.stream()
                .map(conversation -> new SupportActivity(
                        conversation.getStatus().name(),
                        conversation.getUpdatedAt()))
                .toList();
    }

    private int barWidth(long value, long maximum) {
        return maximum == 0
                ? 0
                : (int) Math.max(4, Math.round(100.0 * value / maximum));
    }

    private int chartHeight(long value, long maximum) {
        return maximum == 0
                ? 0
                : (int) Math.max(6, Math.round(120.0 * value / maximum));
    }

    private String displaySubject(String subject) {
        return subject == null || subject.isBlank()
                ? "Unspecified"
                : subject.trim();
    }

    private long valueOrZero(Long value) {
        return value == null ? 0 : value;
    }

    private double valueOrZero(Double value) {
        return value == null ? 0 : value;
    }

    public record SubjectMetric(
            String subject,
            long attempts,
            double averagePercentage,
            int attemptsBarWidth) {
    }

    public record TrendMetric(
            LocalDate date,
            long attempts,
            int barHeight) {
    }

    public record RecentAttempt(
            String subject,
            LocalDateTime examDate,
            int percentage) {
    }

    public record RecentPublishedResult(
            String subject,
            LocalDateTime publishedAt) {
    }

    public record SupportActivity(
            String status,
            LocalDateTime updatedAt) {
    }
}
