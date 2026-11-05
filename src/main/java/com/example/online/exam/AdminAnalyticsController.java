
package com.example.online.exam;

import com.example.online.exam.entity.ExamResult;
import com.example.online.exam.entity.Student;
import com.example.online.exam.repository.ExamResultRepository;
import com.example.online.exam.repository.StudentRepository;

import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
public class AdminAnalyticsController {

    private static final int PASS_MARK = 40;

    private final ExamResultRepository examResultRepository;
    private final StudentRepository studentRepository;

    public AdminAnalyticsController(
            ExamResultRepository examResultRepository,
            StudentRepository studentRepository) {
        this.examResultRepository = examResultRepository;
        this.studentRepository = studentRepository;
    }

    // =====================================================
    // 1. EXISTING ADMIN ANALYTICS DASHBOARD
    // =====================================================

    @GetMapping("/admin/analytics")
    public String showAnalytics(HttpSession session, Model model) {

        if (session == null
                || !Boolean.TRUE.equals(
                        session.getAttribute("quizzyAdmin"))) {
            return "redirect:/admin/login";
        }

        List<ExamResult> results =
                examResultRepository.findAllByOrderByExamDateDesc();

        List<Student> students = studentRepository.findAll();

        long totalStudents = students.size();
        long totalAttempts = results.size();

        long passedAttempts = 0;
        long failedAttempts = 0;
        double totalPercentage = 0.0;

        Map<Long, Student> studentsById = new LinkedHashMap<>();
        Map<Long, StudentStats> studentMap = new LinkedHashMap<>();

        for (Student student : students) {
            studentsById.put(student.getId(), student);

            studentMap.put(
                    student.getId(),
                    new StudentStats(
                            student.getId(),
                            student.getFullName(),
                            student.getEmail()
                    )
            );
        }

        Map<String, SubjectStats> subjectMap =
                new LinkedHashMap<>();

        List<StudentExamHistory> studentExamHistory =
                new ArrayList<>();

        for (ExamResult result : results) {

            int percentage = result.getPercentage();
            totalPercentage += percentage;

            if (percentage >= PASS_MARK) {
                passedAttempts++;
            } else {
                failedAttempts++;
            }

            String subject = normalizeSubject(result.getSubject());

            SubjectStats subjectStats =
                    subjectMap.computeIfAbsent(
                            subject, SubjectStats::new);

            subjectStats.addAttempt(percentage, PASS_MARK);

            Long studentId = result.getStudentId();
            Student student = studentsById.get(studentId);

            String studentName = student == null
                    ? "Unknown Student"
                    : student.getFullName();

            String studentEmail = student == null
                    ? ""
                    : student.getEmail();

            StudentStats stats = studentMap.get(studentId);

            if (stats != null) {
                stats.addAttempt(percentage, PASS_MARK);
            }

            studentExamHistory.add(
                    new StudentExamHistory(
                            studentId,
                            studentName,
                            studentEmail,
                            subject,
                            result.getScore(),
                            result.getTotalQuestions(),
                            percentage,
                            result.getExamDate()
                    )
            );
        }

        double averagePercentage = totalAttempts == 0
                ? 0.0
                : totalPercentage / totalAttempts;

        model.addAttribute("totalStudents", totalStudents);
        model.addAttribute("totalAttempts", totalAttempts);
        model.addAttribute("averagePercentage", averagePercentage);
        model.addAttribute("passedAttempts", passedAttempts);
        model.addAttribute("failedAttempts", failedAttempts);
        model.addAttribute("passMark", PASS_MARK);

        model.addAttribute(
                "subjectStats",
                new ArrayList<>(subjectMap.values()));

        model.addAttribute(
                "studentStats",
                new ArrayList<>(studentMap.values()));

        model.addAttribute(
                "studentExamHistory",
                studentExamHistory);

        return "admin-analytics";
    }

    // =====================================================
    // 2. DETAILED REPORT FOR ONE STUDENT
    // =====================================================

    @GetMapping("/admin/analytics/student/{studentId}")
    public String showStudentPerformance(
            @PathVariable Long studentId,
            HttpSession session,
            Model model) {

        // Protect the detailed report
        if (session == null
                || !Boolean.TRUE.equals(
                        session.getAttribute("quizzyAdmin"))) {
            return "redirect:/admin/login";
        }

        // Find the requested student
        Student student = studentRepository.findById(studentId)
                .orElse(null);

        if (student == null) {
            return "redirect:/admin/analytics";
        }

        // Get only this student's exam attempts
        List<ExamResult> examHistory =
                examResultRepository
                        .findByStudentIdOrderByExamDateDesc(studentId);

        long passed = 0;
        long failed = 0;
        long timedAttempts = 0;

        double totalPercentage = 0.0;
        double highestPercentage = 0.0;
        double totalSecondsPerQuestion = 0.0;

        Set<String> subjects = new HashSet<>();

        Map<String, StudentSubjectStats> subjectMap =
                new LinkedHashMap<>();

        for (ExamResult exam : examHistory) {

            int percentage = exam.getPercentage();

            totalPercentage += percentage;
            highestPercentage =
                    Math.max(highestPercentage, percentage);

            if (percentage >= PASS_MARK) {
                passed++;
            } else {
                failed++;
            }

            String subject = normalizeSubject(exam.getSubject());
            subjects.add(subject);

            StudentSubjectStats stats =
                    subjectMap.computeIfAbsent(
                            subject, StudentSubjectStats::new);

            stats.addAttempt(percentage, PASS_MARK);

            Long duration = exam.getDurationSeconds();

            // Old exam records may not have timing data
            if (duration != null
                    && duration >= 0
                    && exam.getTotalQuestions() > 0) {

                totalSecondsPerQuestion +=
                        (double) duration / exam.getTotalQuestions();

                timedAttempts++;
            }
        }

        double averagePercentage = examHistory.isEmpty()
                ? 0.0
                : totalPercentage / examHistory.size();

        double averageSecondsPerQuestion = timedAttempts == 0
                ? 0.0
                : totalSecondsPerQuestion / timedAttempts;

        // Data used by admin-student-performance.html
        model.addAttribute("student", student);
        model.addAttribute("examHistory", examHistory);
        model.addAttribute("totalAttempts", examHistory.size());
        model.addAttribute("totalSubjects", subjects.size());
        model.addAttribute("averagePercentage", averagePercentage);
        model.addAttribute("highestPercentage", highestPercentage);
        model.addAttribute("passedAttempts", passed);
        model.addAttribute("failedAttempts", failed);
        model.addAttribute("passMark", PASS_MARK);

        model.addAttribute(
                "subjectStats",
                new ArrayList<>(subjectMap.values()));

        model.addAttribute("timedAttempts", timedAttempts);

        model.addAttribute(
                "averageSecondsPerQuestion",
                averageSecondsPerQuestion);

        return "admin-student-performance";
    }

    // =====================================================
    // 3. HELPER METHOD
    // =====================================================

    private String normalizeSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return "Unspecified";
        }

        return subject.trim();
    }

    // =====================================================
    // 4. OVERALL INDIVIDUAL STUDENT STATISTICS
    // =====================================================

    public static class StudentStats {

        private final Long studentId;
        private final String studentName;
        private final String email;

        private long attempts;
        private long passed;
        private long failed;
        private double totalPercentage;

        public StudentStats(
                Long studentId,
                String studentName,
                String email) {
            this.studentId = studentId;
            this.studentName = studentName;
            this.email = email;
        }

        public void addAttempt(int percentage, int passMark) {
            attempts++;
            totalPercentage += percentage;

            if (percentage >= passMark) {
                passed++;
            } else {
                failed++;
            }
        }

        public Long getStudentId() {
            return studentId;
        }

        public String getStudentName() {
            return studentName;
        }

        public String getEmail() {
            return email;
        }

        public long getAttempts() {
            return attempts;
        }

        public long getPassed() {
            return passed;
        }

        public long getFailed() {
            return failed;
        }

        public double getAveragePercentage() {
            return attempts == 0
                    ? 0.0
                    : totalPercentage / attempts;
        }
    }

    // =====================================================
    // 5. OVERALL SUBJECT STATISTICS
    // =====================================================

    public static class SubjectStats {

        private final String subject;
        private long attempts;
        private long passed;
        private long failed;
        private double totalPercentage;

        public SubjectStats(String subject) {
            this.subject = subject;
        }

        public void addAttempt(int percentage, int passMark) {
            attempts++;
            totalPercentage += percentage;

            if (percentage >= passMark) {
                passed++;
            } else {
                failed++;
            }
        }

        public String getSubject() {
            return subject;
        }

        public long getAttempts() {
            return attempts;
        }

        public long getPassed() {
            return passed;
        }

        public long getFailed() {
            return failed;
        }

        public double getAveragePercentage() {
            return attempts == 0
                    ? 0.0
                    : totalPercentage / attempts;
        }
    }

    // =====================================================
    // 6. SUBJECT STATISTICS FOR ONE STUDENT
    // =====================================================

    public static class StudentSubjectStats {

        private final String subject;
        private long attempts;
        private long passed;
        private long failed;
        private double totalPercentage;

        public StudentSubjectStats(String subject) {
            this.subject = subject;
        }

        public void addAttempt(int percentage, int passMark) {
            attempts++;
            totalPercentage += percentage;

            if (percentage >= passMark) {
                passed++;
            } else {
                failed++;
            }
        }

        public String getSubject() {
            return subject;
        }

        public long getAttempts() {
            return attempts;
        }

        public long getPassed() {
            return passed;
        }

        public long getFailed() {
            return failed;
        }

        public double getAveragePercentage() {
            return attempts == 0
                    ? 0.0
                    : totalPercentage / attempts;
        }
    }

    // =====================================================
    // 7. INDIVIDUAL EXAM HISTORY ROW
    // =====================================================

    public static class StudentExamHistory {

        private final Long studentId;
        private final String studentName;
        private final String email;
        private final String subject;
        private final int score;
        private final int totalQuestions;
        private final int percentage;
        private final java.time.LocalDateTime examDate;

        public StudentExamHistory(
                Long studentId,
                String studentName,
                String email,
                String subject,
                int score,
                int totalQuestions,
                int percentage,
                java.time.LocalDateTime examDate) {

            this.studentId = studentId;
            this.studentName = studentName;
            this.email = email;
            this.subject = subject;
            this.score = score;
            this.totalQuestions = totalQuestions;
            this.percentage = percentage;
            this.examDate = examDate;
        }

        public Long getStudentId() {
            return studentId;
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

        public java.time.LocalDateTime getExamDate() {
            return examDate;
        }
    }
}
