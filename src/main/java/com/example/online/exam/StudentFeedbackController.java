package com.example.online.exam;

import com.example.online.exam.entity.ExamFeedback;
import com.example.online.exam.entity.ExamResult;
import com.example.online.exam.entity.Student;
import com.example.online.exam.repository.ExamFeedbackRepository;
import com.example.online.exam.repository.ExamResultRepository;
import com.example.online.exam.repository.StudentRepository;
import com.example.online.exam.service.ExamAttemptSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Controller
public class StudentFeedbackController {

    private static final int MAX_COMMENT_LENGTH = 1000;

    private final ExamFeedbackRepository feedbackRepository;
    private final ExamResultRepository resultRepository;
    private final StudentRepository studentRepository;

    public StudentFeedbackController(
            ExamFeedbackRepository feedbackRepository,
            ExamResultRepository resultRepository,
            StudentRepository studentRepository) {
        this.feedbackRepository = feedbackRepository;
        this.resultRepository = resultRepository;
        this.studentRepository = studentRepository;
    }

    @GetMapping("/feedback")
    @Transactional(readOnly = true)
    public String showStudentFeedback(HttpSession session, Model model) {
        Long studentId = ExamAttemptSession.studentId(session);
        if (studentId == null) {
            return "redirect:/login";
        }

        List<FeedbackAttemptView> attempts = new ArrayList<>();
        for (ExamResult result :
                resultRepository.findByStudentIdOrderByExamDateDesc(studentId)) {
            Optional<ExamFeedback> feedback =
                    feedbackRepository.findByExamResult_IdAndStudent_Id(
                            result.getId(), studentId);
            attempts.add(new FeedbackAttemptView(
                    result.getId(),
                    result.getSubject(),
                    result.getExamDate(),
                    feedback.orElse(null)));
        }
        model.addAttribute("attempts", attempts);
        return "student-feedback";
    }

    @PostMapping("/feedback/{resultId}")
    public String submitFeedback(
            @PathVariable Long resultId,
            @RequestParam(required = false) Integer overallRating,
            @RequestParam(required = false) Integer difficultyRating,
            @RequestParam(required = false) Integer qualityRating,
            @RequestParam(required = false) Integer experienceRating,
            @RequestParam(defaultValue = "") String comment,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        Long studentId = ExamAttemptSession.studentId(session);
        if (studentId == null) {
            return "redirect:/login";
        }

        if (!validRating(overallRating)
                || !validRating(difficultyRating)
                || !validRating(qualityRating)
                || !validRating(experienceRating)
                || comment == null
                || comment.length() > MAX_COMMENT_LENGTH) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Provide all ratings from 1 to 5 and keep comments under "
                            + MAX_COMMENT_LENGTH + " characters.");
            return "redirect:/feedback";
        }

        ExamResult result = resultRepository
                .findByIdAndStudentId(resultId, studentId)
                .orElse(null);
        if (result == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "That completed examination is not available for feedback.");
            return "redirect:/feedback";
        }

        if (feedbackRepository.existsByExamResult_Id(resultId)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Feedback has already been submitted for this examination.");
            return "redirect:/feedback";
        }

        Student student = studentRepository.findById(studentId).orElse(null);
        if (student == null) {
            session.invalidate();
            return "redirect:/login";
        }

        String normalizedComment = comment.trim();
        ExamFeedback feedback = new ExamFeedback(
                result,
                student,
                overallRating,
                difficultyRating,
                qualityRating,
                experienceRating,
                normalizedComment.isEmpty() ? null : normalizedComment);
        try {
            feedbackRepository.saveAndFlush(feedback);
        } catch (DataIntegrityViolationException exception) {
            if (!feedbackRepository.existsByExamResult_Id(resultId)) {
                throw exception;
            }
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Feedback has already been submitted for this examination.");
            return "redirect:/feedback";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage", "Your feedback has been submitted.");
        return "redirect:/feedback";
    }

    @GetMapping("/admin/feedback")
    @Transactional(readOnly = true)
    public String showAdminFeedback(
            HttpSession session,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            Model model) {
        if (!isAdmin(session)) {
            return "redirect:/admin/login";
        }

        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            model.addAttribute(
                    "errorMessage", "The end date must not be before the start date.");
            fromDate = null;
            toDate = null;
        }

        String normalizedSearch = search.trim().toLowerCase(Locale.ROOT);
        List<FeedbackView> feedbackViews = new ArrayList<>();
        for (ExamFeedback feedback :
                feedbackRepository.findAllByOrderBySubmittedAtDesc()) {
            String subject = feedback.getExamResult().getSubject();
            LocalDate submittedDate = feedback.getSubmittedAt() == null
                    ? null
                    : feedback.getSubmittedAt().toLocalDate();
            boolean matchesSearch = normalizedSearch.isEmpty()
                    || subject.toLowerCase(Locale.ROOT).contains(normalizedSearch)
                    || feedback.getStudent().getFullName()
                            .toLowerCase(Locale.ROOT).contains(normalizedSearch);
            boolean matchesDate = (fromDate == null
                            || (submittedDate != null
                                    && !submittedDate.isBefore(fromDate)))
                    && (toDate == null
                            || (submittedDate != null
                                    && !submittedDate.isAfter(toDate)));
            if (matchesSearch && matchesDate) {
                feedbackViews.add(new FeedbackView(feedback));
            }
        }

        double averageRating = feedbackViews.stream()
                .mapToInt(FeedbackView::getOverallRating)
                .average()
                .orElse(0.0);
        double averageDifficulty = feedbackViews.stream()
                .mapToInt(FeedbackView::getDifficultyRating)
                .average()
                .orElse(0.0);
        double averageQuality = feedbackViews.stream()
                .mapToInt(FeedbackView::getQualityRating)
                .average()
                .orElse(0.0);
        double averageExperience = feedbackViews.stream()
                .mapToInt(FeedbackView::getExperienceRating)
                .average()
                .orElse(0.0);
        long lowRatingCount = feedbackViews.stream()
                .filter(feedback -> feedback.getOverallRating() <= 2)
                .count();

        model.addAttribute("feedback", feedbackViews);
        model.addAttribute("feedbackCount", feedbackViews.size());
        model.addAttribute("averageRating", averageRating);
        model.addAttribute("averageDifficulty", averageDifficulty);
        model.addAttribute("averageQuality", averageQuality);
        model.addAttribute("averageExperience", averageExperience);
        model.addAttribute("lowRatingCount", lowRatingCount);
        model.addAttribute("search", search);
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);
        return "admin-feedback";
    }

    private boolean validRating(Integer rating) {
        return rating != null && rating >= 1 && rating <= 5;
    }

    private boolean isAdmin(HttpSession session) {
        return session != null
                && Boolean.TRUE.equals(session.getAttribute("quizzyAdmin"));
    }

    public static class FeedbackAttemptView {
        private final Long resultId;
        private final String subject;
        private final LocalDateTime examDate;
        private final ExamFeedback feedback;

        FeedbackAttemptView(
                Long resultId,
                String subject,
                LocalDateTime examDate,
                ExamFeedback feedback) {
            this.resultId = resultId;
            this.subject = subject;
            this.examDate = examDate;
            this.feedback = feedback;
        }

        public Long getResultId() {
            return resultId;
        }

        public String getSubject() {
            return subject;
        }

        public LocalDateTime getExamDate() {
            return examDate;
        }

        public boolean hasFeedback() {
            return feedback != null;
        }

        public ExamFeedback getFeedback() {
            return feedback;
        }
    }

    public static class FeedbackView {
        private final String studentName;
        private final String subject;
        private final LocalDateTime examDate;
        private final LocalDateTime submittedAt;
        private final int overallRating;
        private final int difficultyRating;
        private final int qualityRating;
        private final int experienceRating;
        private final String comment;

        FeedbackView(ExamFeedback feedback) {
            this.studentName = feedback.getStudent().getFullName();
            this.subject = feedback.getExamResult().getSubject();
            this.examDate = feedback.getExamResult().getExamDate();
            this.submittedAt = feedback.getSubmittedAt();
            this.overallRating = feedback.getOverallRating();
            this.difficultyRating = feedback.getDifficultyRating();
            this.qualityRating = feedback.getQualityRating();
            this.experienceRating = feedback.getExperienceRating();
            this.comment = feedback.getComment();
        }

        public String getStudentName() {
            return studentName;
        }

        public String getSubject() {
            return subject;
        }

        public LocalDateTime getExamDate() {
            return examDate;
        }

        public LocalDateTime getSubmittedAt() {
            return submittedAt;
        }

        public int getOverallRating() {
            return overallRating;
        }

        public int getDifficultyRating() {
            return difficultyRating;
        }

        public int getQualityRating() {
            return qualityRating;
        }

        public int getExperienceRating() {
            return experienceRating;
        }

        public String getComment() {
            return comment;
        }
    }
}
