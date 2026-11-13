
package com.example.online.exam;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.online.exam.entity.ExamResult;
import com.example.online.exam.entity.ExamMonitoringAttempt;
import com.example.online.exam.entity.ExamSchedule;
import com.example.online.exam.repository.ExamResultRepository;
import com.example.online.exam.repository.ExamScheduleRepository;
import com.example.online.exam.repository.QuestionRepository;
import com.example.online.exam.service.ExamAttemptSession;
import com.example.online.exam.service.ExamMonitoringService;
import org.springframework.transaction.annotation.Transactional;

@Controller
public class QuizController {

    private static final String ACTIVE_QUESTIONS = "activeJavaQuestions";
    private static final String EXAM_START_TIME = "examStartTime";
    private static final String EXAM_DURATION_SECONDS = "examDurationSeconds";
    private static final String ATTEMPT_SLOT = "java";

    // Allow a short network delay when the browser auto-submits.
    private static final long SUBMISSION_GRACE_SECONDS = 30L;

    private final ExamResultRepository examResultRepository;
    private final ExamScheduleRepository examScheduleRepository;
    private final QuestionRepository questionRepository;
    private final ExamMonitoringService monitoringService;

    public QuizController(
            ExamResultRepository examResultRepository,
            ExamScheduleRepository examScheduleRepository,
            QuestionRepository questionRepository,
            ExamMonitoringService monitoringService) {

        this.examResultRepository = examResultRepository;
        this.examScheduleRepository = examScheduleRepository;
        this.questionRepository = questionRepository;
        this.monitoringService = monitoringService;
    }

    // Load Java questions saved by the administrator.
    private List<Question> loadJavaQuestions() {

        List<com.example.online.exam.entity.Question> savedQuestions =
                questionRepository.findBySubjectIgnoreCaseOrderByIdAsc("java");

        List<Question> javaQuestions = new ArrayList<>();

        for (com.example.online.exam.entity.Question q : savedQuestions) {

            if (q.getCorrectAnswer() == null
                    || q.getQuestionText() == null
                    || q.getOptionA() == null
                    || q.getOptionB() == null
                    || q.getOptionC() == null
                    || q.getOptionD() == null) {
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

            javaQuestions.add(new Question(
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

        return javaQuestions;
    }

    // Clear the session data belonging to an unfinished Java exam.
    private void clearJavaExam(
            HttpSession session,
            ExamAttemptSession.AttemptStatus status) {
        ExamAttemptSession.ActiveAttempt attempt =
                ExamAttemptSession.getForSlot(session, ATTEMPT_SLOT);
        session.removeAttribute(ACTIVE_QUESTIONS);
        session.removeAttribute(EXAM_START_TIME);
        session.removeAttribute(EXAM_DURATION_SECONDS);
        ExamAttemptSession.finish(session, ATTEMPT_SLOT, status);
        if (attempt != null) {
            monitoringService.finishAttempt(
                    attempt.attemptId(),
                    ExamMonitoringAttempt.Status.valueOf(status.name()));
        }
    }

    // Show instructions before the exam starts.
    @GetMapping("/exam-instructions")
    public String showExamInstructions(HttpSession session) {

        if (ExamAttemptSession.studentId(session) == null) {
            return "redirect:/login";
        }

        return "exam-instructions";
    }

    // Validate the chosen duration, select random questions, and start the timer.
    @PostMapping("/start-exam")
    public String startExam(
            @RequestParam("durationMinutes") int durationMinutes,
            HttpSession session,
            Model model) {

        if (ExamAttemptSession.studentId(session) == null) {
            return "redirect:/login";
        }

        if (durationMinutes != 30
                && durationMinutes != 60
                && durationMinutes != 90
                && durationMinutes != 120
                && durationMinutes != 150) {

            model.addAttribute(
                    "errorMessage",
                    "Please select one of the available exam durations."
            );
            return "exam-instructions";
        }

        int requiredQuestions = durationMinutes / 2;

        List<Question> questionBank = loadJavaQuestions();

        if (questionBank.size() < requiredQuestions) {
            model.addAttribute(
                    "errorMessage",
                    "This exam requires " + requiredQuestions
                            + " valid Java questions, but only "
                            + questionBank.size()
                            + " are available. Please contact the administrator "
                            + "to add more Java questions."
            );
            model.addAttribute("selectedDurationMinutes", durationMinutes);
            return "exam-instructions";
        }

        // Select a random set of questions for this attempt.
        Collections.shuffle(questionBank);

        List<Question> selectedQuestions = new ArrayList<>(
                questionBank.subList(0, requiredQuestions)
        );

        // Reset any previous unfinished attempt before starting a new one.
        clearJavaExam(
                session,
                ExamAttemptSession.AttemptStatus.CANCELLED);

        long durationSeconds = durationMinutes * 60L;
        LocalDateTime startTime = LocalDateTime.now();

        session.setAttribute(ACTIVE_QUESTIONS, selectedQuestions);
        session.setAttribute(EXAM_DURATION_SECONDS, durationSeconds);
        session.setAttribute(EXAM_START_TIME, startTime);
        String attemptId = ExamAttemptSession.start(
                session,
                ATTEMPT_SLOT,
                ExamAttemptSession.studentId(session),
                "java",
                "java",
                startTime,
                durationSeconds);
        monitoringService.registerAttempt(
                ExamAttemptSession.get(session, attemptId));

        // The exam timer begins only when the student starts the exam.
        return "redirect:/";
    }

    // Show the public homepage to visitors and preserve any active Java exam.
    @GetMapping("/")
    public String showQuiz(HttpSession session, Model model) {

        if (ExamAttemptSession.studentId(session) == null) {
            LocalDateTime now = LocalDateTime.now();
            LocalDate today = now.toLocalDate();
            List<HomepageSchedule> homepageSchedules = new ArrayList<>();

            List<ExamSchedule> schedules = examScheduleRepository
                    .findByExamDateGreaterThanEqualOrderByExamDateAscExamTimeAsc(
                            today);
            for (ExamSchedule schedule : schedules) {
                String status = "Scheduled";
                if (schedule.getExamDate() != null
                        && schedule.getExamTime() != null) {
                    LocalDateTime startsAt = LocalDateTime.of(
                            schedule.getExamDate(),
                            schedule.getExamTime());
                    Integer durationMinutes = schedule.getDurationMinutes();

                    if (now.isBefore(startsAt)) {
                        status = "Scheduled";
                    } else if (durationMinutes != null
                            && durationMinutes > 0
                            && now.isBefore(startsAt.plusMinutes(
                                    durationMinutes))) {
                        status = "Within scheduled window";
                    } else {
                        status = "Scheduled window ended";
                    }
                }
                homepageSchedules.add(new HomepageSchedule(schedule, status));
            }

            model.addAttribute("homepageSchedules", homepageSchedules);
            return "home";
        }

        Object questionsObject = session.getAttribute(ACTIVE_QUESTIONS);
        Object startTimeObject = session.getAttribute(EXAM_START_TIME);
        Object durationObject = session.getAttribute(EXAM_DURATION_SECONDS);

        if (!(questionsObject instanceof List<?>)
                || !(startTimeObject instanceof LocalDateTime)
                || !(durationObject instanceof Long)) {
            return "redirect:/exam-instructions";
        }

        @SuppressWarnings("unchecked")
        List<Question> questions = (List<Question>) questionsObject;

        LocalDateTime startTime = (LocalDateTime) startTimeObject;
        long durationSeconds = (Long) durationObject;

        long elapsedSeconds = Math.max(
                0L,
                Duration.between(startTime, LocalDateTime.now()).getSeconds()
        );

        int remainingSeconds = (int) Math.max(
                0L,
                durationSeconds - elapsedSeconds
        );

        String attemptId = ExamAttemptSession.ensure(
                session,
                ATTEMPT_SLOT,
                ExamAttemptSession.studentId(session),
                "java",
                "java",
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
        model.addAttribute("questions", questions);
        model.addAttribute("duration", remainingSeconds);
        model.addAttribute("durationMinutes", durationSeconds / 60L);

        return "quiz";
    }

    private record HomepageSchedule(ExamSchedule exam, String status) {
    }

    // Display dashboard and exam history.
    @GetMapping("/dashboard")
    public String showDashboard(HttpSession session, Model model) {

        Object sessionStudentId = session.getAttribute("studentId");

        if (sessionStudentId == null) {
            return "redirect:/login";
        }

        Long studentId;

        try {
            studentId = Long.valueOf(sessionStudentId.toString());
        } catch (NumberFormatException e) {
            session.invalidate();
            return "redirect:/login";
        }

        model.addAttribute("studentName", session.getAttribute("studentName"));

        List<ExamResult> examHistory =
                examResultRepository.findByStudentIdOrderByExamDateDesc(studentId);

        // Keep every attempt in history, including unpublished attempts.
        model.addAttribute("examHistory", examHistory);
        model.addAttribute("completedExams", examHistory.size());

        // Only published results contribute to the student's average.
        double averagePercentage = examHistory.stream()
                .filter(ExamResult::isPublished)
                .mapToInt(ExamResult::getPercentage)
                .average()
                .orElse(0.0);

        long publishedResultsCount = examHistory.stream()
                .filter(ExamResult::isPublished)
                .count();

        model.addAttribute("averagePercentage", Math.round(averagePercentage));
        model.addAttribute("publishedResultsCount", publishedResultsCount);
        model.addAttribute(
                "pendingResultsCount",
                examHistory.size() - publishedResultsCount
        );

        List<ExamSchedule> upcomingExams =
                examScheduleRepository
                        .findByExamDateGreaterThanEqualOrderByExamDateAscExamTimeAsc(
                                LocalDate.now()
                        );

        model.addAttribute("upcomingExams", upcomingExams);

        return "dashboard";
    }

    // Score the exact questions displayed in this attempt and save the result.
    // The student is not shown the marks until an admin publishes the result.
    @PostMapping("/submit")
    @Transactional
    public String submitQuiz(
            @RequestParam Map<String, String> answers,
            @RequestParam("attemptId") String attemptId,
            HttpSession session,
            Model model) {

        synchronized (session) {

            Object sessionStudentId = session.getAttribute("studentId");

            if (sessionStudentId == null) {
                return "redirect:/login";
            }

            Long studentId;

            try {
                studentId = Long.valueOf(sessionStudentId.toString());
            } catch (NumberFormatException e) {
                session.invalidate();
                return "redirect:/login";
            }

            Object questionsObject = session.getAttribute(ACTIVE_QUESTIONS);
            Object startTimeObject = session.getAttribute(EXAM_START_TIME);
            Object durationObject = session.getAttribute(EXAM_DURATION_SECONDS);

            if (!(questionsObject instanceof List<?>)
                    || !(startTimeObject instanceof LocalDateTime)
                    || !(durationObject instanceof Long)) {
                return "redirect:/exam-instructions";
            }

            ExamAttemptSession.ActiveAttempt sessionAttempt =
                    ExamAttemptSession.get(session, attemptId);
            if (sessionAttempt == null
                    || !sessionAttempt.studentId().equals(studentId)
                    || !ATTEMPT_SLOT.equals(sessionAttempt.slot())
                    || !"java".equals(sessionAttempt.examIdentifier())) {
                return "redirect:/exam-instructions";
            }
            ExamMonitoringAttempt persistedAttempt = monitoringService
                    .lockAttemptForSubmission(attemptId, studentId)
                    .orElse(null);
            if (persistedAttempt == null
                    || !"java".equals(persistedAttempt.getExamIdentifier())) {
                return "redirect:/exam-instructions";
            }
            if (persistedAttempt.getStatus()
                            == ExamMonitoringAttempt.Status.DISQUALIFIED
                    || sessionAttempt.status()
                            == ExamAttemptSession.AttemptStatus.DISQUALIFIED) {
                clearJavaExam(
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
                return "redirect:/exam-instructions";
            }

            @SuppressWarnings("unchecked")
            List<Question> questions = (List<Question>) questionsObject;

            LocalDateTime startTime = (LocalDateTime) startTimeObject;
            long durationSeconds = (Long) durationObject;
            LocalDateTime completionTime = LocalDateTime.now();

            long elapsedSeconds = Duration.between(
                    startTime,
                    completionTime
            ).getSeconds();

            if (elapsedSeconds < 0
                    || elapsedSeconds > durationSeconds + SUBMISSION_GRACE_SECONDS) {

                clearJavaExam(
                        session,
                        ExamAttemptSession.AttemptStatus.EXPIRED);

                model.addAttribute(
                        "errorMessage",
                        "Your exam time has expired. Please start a new attempt."
                );

                return "exam-instructions";
            }

            if (questions.isEmpty()) {
                clearJavaExam(
                        session,
                        ExamAttemptSession.AttemptStatus.CANCELLED);

                model.addAttribute(
                        "errorMessage",
                        "No questions were found for this exam. Please start again."
                );

                return "exam-instructions";
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
                    } catch (NumberFormatException e) {
                        // Ignore invalid answer values.
                    }
                }
            }

            int total = questions.size();
            int percentage = (score * 100) / total;

            long actualDurationSeconds = Math.max(0L, elapsedSeconds);

            // New results default to unpublished in the ExamResult entity.
            ExamResult result = new ExamResult(
                    studentId,
                    "java",
                    score,
                    total,
                    percentage,
                    startTime,
                    completionTime,
                    actualDurationSeconds
            );

            examResultRepository.save(result);

            // Prevent the same attempt from being submitted again.
            clearJavaExam(
                    session,
                    ExamAttemptSession.AttemptStatus.SUBMITTED);

            // Do not send score or total to the student-facing result page.
            model.addAttribute("resultPending", true);
            model.addAttribute(
                    "message",
                    "Your exam has been submitted successfully. "
                            + "Your result will be available after the administrator publishes it."
            );

            return "result";
        }
    }

    // Question format used by quiz.html.
    public static class Question {

        private final String text;
        private final String[] options;
        private final int correctIndex;

        public Question(String text, String[] options, int correctIndex) {
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
