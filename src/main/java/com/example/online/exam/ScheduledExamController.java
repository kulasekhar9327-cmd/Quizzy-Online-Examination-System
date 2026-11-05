
package com.example.online.exam;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.online.exam.entity.ExamResult;
import com.example.online.exam.entity.ExamMonitoringAttempt;
import com.example.online.exam.entity.ExamSchedule;
import com.example.online.exam.entity.ScheduledExamQuestion;
import com.example.online.exam.repository.ExamResultRepository;
import com.example.online.exam.repository.ExamScheduleRepository;
import com.example.online.exam.repository.ScheduledExamQuestionRepository;
import com.example.online.exam.service.ExamAttemptSession;
import com.example.online.exam.service.ExamMonitoringService;

@Controller
public class ScheduledExamController {

    private static final String ACTIVE_QUESTIONS = "activeScheduledQuestions";
    private static final String ACTIVE_EXAM_ID = "activeScheduledExamId";
    private static final String ACTIVE_EXAM_SUBJECT = "activeScheduledExamSubject";
    private static final String EXAM_START_TIME = "scheduledExamStartTime";
    private static final String EXAM_DURATION_SECONDS = "scheduledExamDurationSeconds";
    private static final String ATTEMPT_SLOT = "scheduled";

    private static final long SUBMISSION_GRACE_SECONDS = 30L;

    // TEMPORARY LOCAL TEST MODE.
    // Keep false to enforce the scheduled date/time.
    private static final boolean TEST_MODE = false;

    private final ExamResultRepository examResultRepository;
    private final ExamScheduleRepository examScheduleRepository;
    private final ScheduledExamQuestionRepository scheduledQuestionRepository;
    private final ExamMonitoringService monitoringService;

    public ScheduledExamController(
            ExamResultRepository examResultRepository,
            ExamScheduleRepository examScheduleRepository,
            ScheduledExamQuestionRepository scheduledQuestionRepository,
            ExamMonitoringService monitoringService) {

        this.examResultRepository = examResultRepository;
        this.examScheduleRepository = examScheduleRepository;
        this.scheduledQuestionRepository = scheduledQuestionRepository;
        this.monitoringService = monitoringService;
    }

    @PostMapping("/scheduled-exams/{id}/start")
    public String startScheduledExam(
            @PathVariable Long id,
            HttpSession session,
            Model model) {

        if (ExamAttemptSession.studentId(session) == null) {
            return "redirect:/login";
        }

        ExamSchedule exam = examScheduleRepository.findById(id).orElse(null);

        if (exam == null
                || exam.getExamDate() == null
                || exam.getExamTime() == null) {
            return "redirect:/dashboard";
        }

        LocalDateTime scheduledStart =
                LocalDateTime.of(exam.getExamDate(), exam.getExamTime());

        int durationMinutes = exam.getDurationMinutes() == null
                ? 30
                : exam.getDurationMinutes();

        if (durationMinutes <= 0) {
            durationMinutes = 30;
        }

        LocalDateTime scheduledEnd =
                scheduledStart.plusMinutes(durationMinutes);

        LocalDateTime now = LocalDateTime.now();

        // Students may start only within the scheduled exam window.
        if (!TEST_MODE
                && (now.isBefore(scheduledStart)
                || !now.isBefore(scheduledEnd))) {

            model.addAttribute(
                    "errorMessage",
                    "This exam is not currently available. Please check its scheduled date and time."
            );

            return "scheduled-exam-unavailable";
        }

        List<ScheduledExamQuestion> savedQuestions =
                scheduledQuestionRepository
                        .findByExamSchedule_IdOrderByIdAsc(id);

        List<ExamQuestion> questions = new ArrayList<>();

        for (ScheduledExamQuestion q : savedQuestions) {

            if (q.getQuestionText() == null
                    || q.getOptionA() == null
                    || q.getOptionB() == null
                    || q.getOptionC() == null
                    || q.getOptionD() == null
                    || q.getCorrectAnswer() == null) {
                continue;
            }

            int correctIndex;

            switch (q.getCorrectAnswer().trim().toUpperCase()) {
                case "A":
                    correctIndex = 0;
                    break;
                case "B":
                    correctIndex = 1;
                    break;
                case "C":
                    correctIndex = 2;
                    break;
                case "D":
                    correctIndex = 3;
                    break;
                default:
                    continue;
            }

            questions.add(new ExamQuestion(
                    q.getQuestionText(),
                    new String[] {
                        q.getOptionA(),
                        q.getOptionB(),
                        q.getOptionC(),
                        q.getOptionD()
                    },
                    correctIndex
            ));
        }

        if (questions.isEmpty()) {
            model.addAttribute(
                    "errorMessage",
                    "No valid questions are available for this scheduled exam. Please contact the administrator."
            );

            return "scheduled-exam-unavailable";
        }

        // Calculate the actual time remaining until the scheduled end.
        long remainingSeconds = Duration.between(
                now,
                scheduledEnd
        ).getSeconds();

        // Test mode retains the configured duration for local testing.
        if (TEST_MODE) {
            remainingSeconds = durationMinutes * 60L;
        }

        if (remainingSeconds <= 0) {
            model.addAttribute(
                    "errorMessage",
                    "This exam has ended and is no longer available."
            );

            return "scheduled-exam-unavailable";
        }

        clearScheduledExam(
                session,
                ExamAttemptSession.AttemptStatus.CANCELLED);

        session.setAttribute(ACTIVE_QUESTIONS, questions);
        session.setAttribute(ACTIVE_EXAM_ID, id);
        session.setAttribute(ACTIVE_EXAM_SUBJECT, exam.getSubject());
        session.setAttribute(EXAM_START_TIME, now);
        session.setAttribute(EXAM_DURATION_SECONDS, remainingSeconds);
        String monitoringSubject = exam.getSubject() == null
                ? exam.getExamName()
                : exam.getSubject();
        String attemptId = ExamAttemptSession.start(
                session,
                ATTEMPT_SLOT,
                ExamAttemptSession.studentId(session),
                "scheduled-exam:" + id,
                monitoringSubject,
                now,
                remainingSeconds);
        monitoringService.registerAttempt(
                ExamAttemptSession.get(session, attemptId));

        return "redirect:/scheduled-exams/" + id;
    }

    @GetMapping("/scheduled-exams/{id}")
    public String showScheduledExam(
            @PathVariable Long id,
            HttpSession session,
            Model model) {

        if (ExamAttemptSession.studentId(session) == null) {
            return "redirect:/login";
        }

        Object activeId = session.getAttribute(ACTIVE_EXAM_ID);
        Object questionsObject = session.getAttribute(ACTIVE_QUESTIONS);
        Object startObject = session.getAttribute(EXAM_START_TIME);
        Object durationObject = session.getAttribute(EXAM_DURATION_SECONDS);

        if (!(activeId instanceof Long)
                || !activeId.equals(id)
                || !(questionsObject instanceof List<?>)
                || !(startObject instanceof LocalDateTime)
                || !(durationObject instanceof Long)) {
            return "redirect:/dashboard";
        }

        ExamSchedule exam = examScheduleRepository.findById(id).orElse(null);

        if (exam == null) {
            clearScheduledExam(
                    session,
                    ExamAttemptSession.AttemptStatus.CANCELLED);
            return "redirect:/dashboard";
        }

        @SuppressWarnings("unchecked")
        List<ExamQuestion> questions =
                (List<ExamQuestion>) questionsObject;

        LocalDateTime startTime = (LocalDateTime) startObject;
        long durationSeconds = (Long) durationObject;

        long elapsedSeconds = Math.max(
                0L,
                Duration.between(startTime, LocalDateTime.now()).getSeconds()
        );

        int remainingSeconds = (int) Math.max(
                0L,
                durationSeconds - elapsedSeconds
        );

        model.addAttribute("examName", exam.getExamName());
        model.addAttribute("examDescription", exam.getDescription());
        model.addAttribute("subject", exam.getSubject());
        model.addAttribute("examId", id);
        model.addAttribute("questions", questions);
        model.addAttribute("duration", remainingSeconds);
        model.addAttribute("durationMinutes", exam.getDurationMinutes());
        model.addAttribute("totalMarks", exam.getTotalMarks());
        String attemptId = ExamAttemptSession.ensure(
                session,
                ATTEMPT_SLOT,
                ExamAttemptSession.studentId(session),
                "scheduled-exam:" + id,
                exam.getSubject() == null
                        ? exam.getExamName()
                        : exam.getSubject(),
                startTime,
                durationSeconds);
        if (attemptId == null) {
            ExamAttemptSession.ActiveAttempt terminalAttempt =
                    ExamAttemptSession.getForSlot(session, ATTEMPT_SLOT);
            if (terminalAttempt != null
                    && terminalAttempt.status()
                            == ExamAttemptSession.AttemptStatus.DISQUALIFIED) {
                attemptId = terminalAttempt.attemptId();
            }
        } else {
            monitoringService.registerAttempt(
                    ExamAttemptSession.get(session, attemptId));
        }
        model.addAttribute("attemptId", attemptId);

        return "scheduled-exam";
    }

    @PostMapping("/scheduled-exams/{id}/submit")
    @Transactional
    public String submitScheduledExam(
            @PathVariable Long id,
            @RequestParam Map<String, String> answers,
            @RequestParam("attemptId") String attemptId,
            HttpSession session,
            Model model) {

        synchronized (session) {

            Object studentObject = session.getAttribute("studentId");

            if (studentObject == null) {
                return "redirect:/login";
            }

            Long studentId;

            try {
                studentId = Long.valueOf(studentObject.toString());
            } catch (NumberFormatException e) {
                session.invalidate();
                return "redirect:/login";
            }

            Object activeId = session.getAttribute(ACTIVE_EXAM_ID);
            Object questionsObject = session.getAttribute(ACTIVE_QUESTIONS);
            Object startObject = session.getAttribute(EXAM_START_TIME);
            Object durationObject = session.getAttribute(EXAM_DURATION_SECONDS);

            if (!(activeId instanceof Long)
                    || !activeId.equals(id)
                    || !(questionsObject instanceof List<?>)
                    || !(startObject instanceof LocalDateTime)
                    || !(durationObject instanceof Long)) {
                return "redirect:/dashboard";
            }

            ExamAttemptSession.ActiveAttempt sessionAttempt =
                    ExamAttemptSession.get(session, attemptId);
            String examIdentifier = "scheduled-exam:" + id;
            if (sessionAttempt == null
                    || !sessionAttempt.studentId().equals(studentId)
                    || !ATTEMPT_SLOT.equals(sessionAttempt.slot())
                    || !examIdentifier.equals(sessionAttempt.examIdentifier())) {
                return "redirect:/dashboard";
            }
            ExamMonitoringAttempt persistedAttempt = monitoringService
                    .lockAttemptForSubmission(attemptId, studentId)
                    .orElse(null);
            if (persistedAttempt == null
                    || !examIdentifier.equals(
                            persistedAttempt.getExamIdentifier())) {
                return "redirect:/dashboard";
            }
            if (persistedAttempt.getStatus()
                            == ExamMonitoringAttempt.Status.DISQUALIFIED
                    || sessionAttempt.status()
                            == ExamAttemptSession.AttemptStatus.DISQUALIFIED) {
                clearScheduledExam(
                        session,
                        ExamAttemptSession.AttemptStatus.DISQUALIFIED);
                model.addAttribute(
                        "message",
                        "This attempt was disqualified after reaching the "
                                + "monitoring warning limit. Browser events "
                                + "are observations, not proof of misconduct.");
                return "exam-disqualified";
            }
            if (persistedAttempt.getStatus()
                    != ExamMonitoringAttempt.Status.ACTIVE
                    || sessionAttempt.status()
                            != ExamAttemptSession.AttemptStatus.ACTIVE) {
                return "redirect:/dashboard";
            }

            @SuppressWarnings("unchecked")
            List<ExamQuestion> questions =
                    (List<ExamQuestion>) questionsObject;

            LocalDateTime startTime = (LocalDateTime) startObject;
            long durationSeconds = (Long) durationObject;
            LocalDateTime completionTime = LocalDateTime.now();

            long elapsedSeconds = Duration.between(
                    startTime,
                    completionTime
            ).getSeconds();

            if (elapsedSeconds < 0
                    || elapsedSeconds > durationSeconds + SUBMISSION_GRACE_SECONDS) {

                clearScheduledExam(
                        session,
                        ExamAttemptSession.AttemptStatus.EXPIRED);

                model.addAttribute(
                        "errorMessage",
                        "Your exam time has expired. Please check with the administrator."
                );

                return "scheduled-exam-unavailable";
            }

            ExamSchedule exam = examScheduleRepository.findById(id).orElse(null);

            if (exam == null || questions.isEmpty()) {
                clearScheduledExam(
                        session,
                        ExamAttemptSession.AttemptStatus.CANCELLED);
                return "redirect:/dashboard";
            }

            int score = 0;

            for (int i = 0; i < questions.size(); i++) {

                String answer = answers.get("q" + i);

                if (answer != null) {
                    try {
                        int selected = Integer.parseInt(answer);

                        if (selected == questions.get(i).getCorrectIndex()) {
                            score++;
                        }
                    } catch (NumberFormatException ignored) {
                        // Invalid answer values do not earn marks.
                    }
                }
            }

            int total = questions.size();
            int percentage = (score * 100) / total;

            String subject = exam.getSubject() == null
                    || exam.getSubject().isBlank()
                    ? exam.getExamName()
                    : exam.getSubject();

            ExamResult result = new ExamResult(
                    studentId,
                    subject,
                    score,
                    total,
                    percentage,
                    startTime,
                    completionTime,
                    Math.max(0L, elapsedSeconds)
            );

            examResultRepository.save(result);

            clearScheduledExam(
                    session,
                    ExamAttemptSession.AttemptStatus.SUBMITTED);

            model.addAttribute(
                    "message",
                    "Your scheduled exam has been submitted successfully. "
                            + "Your result will be available after the administrator publishes it."
            );

            return "result";
        }
    }

    private void clearScheduledExam(
            HttpSession session,
            ExamAttemptSession.AttemptStatus status) {
        ExamAttemptSession.ActiveAttempt attempt =
                ExamAttemptSession.getForSlot(session, ATTEMPT_SLOT);
        session.removeAttribute(ACTIVE_QUESTIONS);
        session.removeAttribute(ACTIVE_EXAM_ID);
        session.removeAttribute(ACTIVE_EXAM_SUBJECT);
        session.removeAttribute(EXAM_START_TIME);
        session.removeAttribute(EXAM_DURATION_SECONDS);
        ExamAttemptSession.finish(session, ATTEMPT_SLOT, status);
        if (attempt != null) {
            monitoringService.finishAttempt(
                    attempt.attemptId(),
                    ExamMonitoringAttempt.Status.valueOf(status.name()));
        }
    }

    public static class ExamQuestion implements Serializable {

        private static final long serialVersionUID = 1L;

        private final String text;
        private final String[] options;
        private final int correctIndex;

        public ExamQuestion(
                String text,
                String[] options,
                int correctIndex) {

            this.text = text;
            this.options = options;
            this.correctIndex = correctIndex;
        }

        public String getText() {
            return text;
        }

        public String[] getOptions() {
            return options;
        }

        public int getCorrectIndex() {
            return correctIndex;
        }
    }
}
