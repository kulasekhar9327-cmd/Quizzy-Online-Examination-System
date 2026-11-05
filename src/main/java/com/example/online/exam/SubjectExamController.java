
package com.example.online.exam;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.online.exam.entity.ExamResult;
import com.example.online.exam.entity.ExamMonitoringAttempt;
import com.example.online.exam.entity.Question;
import com.example.online.exam.repository.ExamResultRepository;
import com.example.online.exam.repository.QuestionRepository;
import com.example.online.exam.service.ExamAttemptSession;
import com.example.online.exam.service.ExamMonitoringService;
import org.springframework.transaction.annotation.Transactional;

@Controller
public class SubjectExamController {

    private static final String ACTIVE_QUESTIONS = "activeSubjectQuestions";
    private static final String ACTIVE_SUBJECT = "activeSubject";
    private static final String START_TIME = "activeSubjectStartTime";
    private static final String DURATION = "activeSubjectDurationSeconds";
    private static final String ATTEMPT_SLOT = "subject";
    private static final long SUBMISSION_GRACE_SECONDS = 30L;

    private final QuestionRepository questionRepository;
    private final ExamResultRepository examResultRepository;
    private final ExamMonitoringService monitoringService;

    public SubjectExamController(
            QuestionRepository questionRepository,
            ExamResultRepository examResultRepository,
            ExamMonitoringService monitoringService) {
        this.questionRepository = questionRepository;
        this.examResultRepository = examResultRepository;
        this.monitoringService = monitoringService;
    }

    private String normalizeSubject(String subject) {
        if (subject == null) {
            return null;
        }

        String normalized = subject.trim().toLowerCase(Locale.ROOT);

        return switch (normalized) {
            case "python", "sql", "aptitude", "mock-tests" -> normalized;
            default -> null;
        };
    }

    private String subjectTitle(String subject) {
        return switch (subject) {
            case "python" -> "Python";
            case "sql" -> "SQL";
            case "aptitude" -> "Aptitude";
            case "mock-tests" -> "Mock Tests";
            default -> "Exam";
        };
    }

    private void addInstructionsModel(
            Model model, String subject, Integer selectedDuration) {

        model.addAttribute("subject", subject);
        model.addAttribute("examTitle", subjectTitle(subject));
        model.addAttribute("startExamAction", "/exams/start");

        if (selectedDuration != null) {
            model.addAttribute("selectedDurationMinutes", selectedDuration);
        }
    }

    private List<QuizController.Question> loadQuestions(String subject) {
        List<Question> savedQuestions =
                questionRepository.findBySubjectIgnoreCaseOrderByIdAsc(subject);

        List<QuizController.Question> validQuestions = new ArrayList<>();

        for (Question q : savedQuestions) {
            if (q.getQuestionText() == null
                    || q.getOptionA() == null
                    || q.getOptionB() == null
                    || q.getOptionC() == null
                    || q.getOptionD() == null
                    || q.getCorrectAnswer() == null) {
                continue;
            }

            int correctIndex = switch (
                    q.getCorrectAnswer().trim().toUpperCase(Locale.ROOT)) {
                case "A" -> 0;
                case "B" -> 1;
                case "C" -> 2;
                case "D" -> 3;
                default -> -1;
            };

            if (correctIndex == -1) {
                continue;
            }

            validQuestions.add(new QuizController.Question(
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

        return validQuestions;
    }

    private void clearSubjectExam(
            HttpSession session,
            ExamAttemptSession.AttemptStatus status) {
        ExamAttemptSession.ActiveAttempt attempt =
                ExamAttemptSession.getForSlot(session, ATTEMPT_SLOT);
        session.removeAttribute(ACTIVE_QUESTIONS);
        session.removeAttribute(ACTIVE_SUBJECT);
        session.removeAttribute(START_TIME);
        session.removeAttribute(DURATION);
        ExamAttemptSession.finish(session, ATTEMPT_SLOT, status);
        if (attempt != null) {
            monitoringService.finishAttempt(
                    attempt.attemptId(),
                    ExamMonitoringAttempt.Status.valueOf(status.name()));
        }
    }

    // Dashboard cards open instructions for the selected subject.
    @GetMapping("/exams/{subject}")
    public String showSubjectInstructions(
            @PathVariable String subject,
            HttpSession session,
            Model model) {

        if (ExamAttemptSession.studentId(session) == null) {
            return "redirect:/login";
        }

        String normalized = normalizeSubject(subject);

        if (normalized == null) {
            return "redirect:/dashboard";
        }

        addInstructionsModel(model, normalized, null);
        return "exam-instructions";
    }

    // Start a non-Java subject exam.
    @PostMapping("/exams/start")
    public String startSubjectExam(
            @RequestParam("subject") String subject,
            @RequestParam("durationMinutes") int durationMinutes,
            HttpSession session,
            Model model) {

        if (ExamAttemptSession.studentId(session) == null) {
            return "redirect:/login";
        }

        String normalized = normalizeSubject(subject);

        if (normalized == null) {
            return "redirect:/dashboard";
        }

        if (durationMinutes != 30
                && durationMinutes != 60
                && durationMinutes != 90
                && durationMinutes != 120
                && durationMinutes != 150) {

            addInstructionsModel(model, normalized, null);
            model.addAttribute(
                    "errorMessage",
                    "Please select a valid exam duration."
            );
            return "exam-instructions";
        }

        int requiredQuestions = durationMinutes / 2;
        List<QuizController.Question> bank = loadQuestions(normalized);

        if (bank.size() < requiredQuestions) {
            addInstructionsModel(model, normalized, durationMinutes);
            model.addAttribute(
                    "errorMessage",
                    "This exam needs " + requiredQuestions
                            + " valid questions, but only " + bank.size()
                            + " are available."
            );
            return "exam-instructions";
        }

        Collections.shuffle(bank);

        List<QuizController.Question> selected = new ArrayList<>(
                bank.subList(0, requiredQuestions)
        );

        clearSubjectExam(
                session,
                ExamAttemptSession.AttemptStatus.CANCELLED);

        LocalDateTime startTime = LocalDateTime.now();
        long durationSeconds = durationMinutes * 60L;
        session.setAttribute(ACTIVE_QUESTIONS, selected);
        session.setAttribute(ACTIVE_SUBJECT, normalized);
        session.setAttribute(START_TIME, startTime);
        session.setAttribute(DURATION, durationSeconds);
        String attemptId = ExamAttemptSession.start(
                session,
                ATTEMPT_SLOT,
                ExamAttemptSession.studentId(session),
                normalized,
                normalized,
                startTime,
                durationSeconds);
        monitoringService.registerAttempt(
                ExamAttemptSession.get(session, attemptId));

        return "redirect:/exams/active/" + normalized;
    }

    // Display the selected questions and remaining time.
    @GetMapping("/exams/active/{subject}")
    public String showSubjectExam(
            @PathVariable String subject,
            HttpSession session,
            Model model) {

        if (ExamAttemptSession.studentId(session) == null) {
            return "redirect:/login";
        }

        String normalized = normalizeSubject(subject);

        if (normalized == null
                || !normalized.equals(session.getAttribute(ACTIVE_SUBJECT))) {
            return "redirect:/dashboard";
        }

        Object questionsObject = session.getAttribute(ACTIVE_QUESTIONS);
        Object startObject = session.getAttribute(START_TIME);
        Object durationObject = session.getAttribute(DURATION);

        if (!(questionsObject instanceof List<?>)
                || !(startObject instanceof LocalDateTime)
                || !(durationObject instanceof Long)) {
            return "redirect:/exams/" + normalized;
        }

        @SuppressWarnings("unchecked")
        List<QuizController.Question> questions =
                (List<QuizController.Question>) questionsObject;

        LocalDateTime startTime = (LocalDateTime) startObject;
        long durationSeconds = (Long) durationObject;

        long elapsed = Math.max(
                0L,
                Duration.between(startTime, LocalDateTime.now()).getSeconds()
        );

        int remainingSeconds = (int) Math.max(0L, durationSeconds - elapsed);

        model.addAttribute("questions", questions);
        model.addAttribute("subject", normalized);
        model.addAttribute("examTitle", subjectTitle(normalized));
        model.addAttribute("duration", remainingSeconds);
        model.addAttribute("durationMinutes", durationSeconds / 60L);
        String attemptId = ExamAttemptSession.ensure(
                session,
                ATTEMPT_SLOT,
                ExamAttemptSession.studentId(session),
                normalized,
                normalized,
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

        return "exam";
    }

    // Score and save a non-Java exam.
    @PostMapping("/exams/submit")
    @Transactional
    public String submitSubjectExam(
            @RequestParam Map<String, String> answers,
            @RequestParam("subject") String subject,
            @RequestParam("attemptId") String attemptId,
            HttpSession session,
            Model model) {

        synchronized (session) {
            if (ExamAttemptSession.studentId(session) == null) {
                return "redirect:/login";
            }

            String normalized = normalizeSubject(subject);

            if (normalized == null
                    || !normalized.equals(session.getAttribute(ACTIVE_SUBJECT))) {
                return "redirect:/dashboard";
            }

            Long studentId;

            try {
                studentId = Long.valueOf(
                        session.getAttribute("studentId").toString()
                );
            } catch (NumberFormatException e) {
                session.invalidate();
                return "redirect:/login";
            }

            Object questionsObject = session.getAttribute(ACTIVE_QUESTIONS);
            Object startObject = session.getAttribute(START_TIME);
            Object durationObject = session.getAttribute(DURATION);

            if (!(questionsObject instanceof List<?>)
                    || !(startObject instanceof LocalDateTime)
                    || !(durationObject instanceof Long)) {
                return "redirect:/exams/" + normalized;
            }

            ExamAttemptSession.ActiveAttempt sessionAttempt =
                    ExamAttemptSession.get(session, attemptId);
            if (sessionAttempt == null
                    || !sessionAttempt.studentId().equals(studentId)
                    || !ATTEMPT_SLOT.equals(sessionAttempt.slot())
                    || !normalized.equals(sessionAttempt.examIdentifier())) {
                return "redirect:/dashboard";
            }
            ExamMonitoringAttempt persistedAttempt = monitoringService
                    .lockAttemptForSubmission(attemptId, studentId)
                    .orElse(null);
            if (persistedAttempt == null
                    || !normalized.equals(persistedAttempt.getExamIdentifier())) {
                return "redirect:/dashboard";
            }
            if (persistedAttempt.getStatus()
                            == ExamMonitoringAttempt.Status.DISQUALIFIED
                    || sessionAttempt.status()
                            == ExamAttemptSession.AttemptStatus.DISQUALIFIED) {
                clearSubjectExam(
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
            List<QuizController.Question> questions =
                    (List<QuizController.Question>) questionsObject;

            LocalDateTime startTime = (LocalDateTime) startObject;
            LocalDateTime completionTime = LocalDateTime.now();
            long durationSeconds = (Long) durationObject;

            long elapsed = Duration.between(
                    startTime, completionTime
            ).getSeconds();

            if (elapsed < 0
                    || elapsed > durationSeconds + SUBMISSION_GRACE_SECONDS) {
                clearSubjectExam(
                        session,
                        ExamAttemptSession.AttemptStatus.EXPIRED);
                addInstructionsModel(model, normalized, null);
                model.addAttribute(
                        "errorMessage",
                        "Your exam time has expired. Please start a new attempt."
                );
                return "exam-instructions";
            }

            if (questions.isEmpty()) {
                clearSubjectExam(
                        session,
                        ExamAttemptSession.AttemptStatus.CANCELLED);
                return "redirect:/exams/" + normalized;
            }

            int score = 0;

            for (int i = 0; i < questions.size(); i++) {
                String answer = answers.get("q" + i);

                if (answer == null) {
                    continue;
                }

                try {
                    int selectedIndex = Integer.parseInt(answer);

                    if (selectedIndex == questions.get(i).getCorrectIndex()) {
                        score++;
                    }
                } catch (NumberFormatException ignored) {
                    // Ignore invalid answer values.
                }
            }

            int total = questions.size();
            int percentage = (score * 100) / total;

            examResultRepository.save(new ExamResult(
                    studentId,
                    normalized,
                    score,
                    total,
                    percentage,
                    startTime,
                    completionTime,
                    Math.max(0L, elapsed)
            ));

            clearSubjectExam(
                    session,
                    ExamAttemptSession.AttemptStatus.SUBMITTED);

model.addAttribute(
        "message",
        "Your exam has been submitted successfully. "
                + "Your result will be available after the administrator publishes it."
);

return "result";
        }
    }
}
