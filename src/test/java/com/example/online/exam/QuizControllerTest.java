package com.example.online.exam;

import com.example.online.exam.entity.ExamResult;
import com.example.online.exam.entity.ExamMonitoringAttempt;
import com.example.online.exam.entity.Question;
import com.example.online.exam.repository.ExamResultRepository;
import com.example.online.exam.repository.ExamScheduleRepository;
import com.example.online.exam.repository.QuestionRepository;
import com.example.online.exam.service.ExamAttemptSession;
import com.example.online.exam.service.ExamMonitoringService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ExtendedModelMap;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuizControllerTest {

    @Mock
    private ExamResultRepository examResultRepository;

    @Mock
    private ExamScheduleRepository examScheduleRepository;

    @Mock
    private QuestionRepository questionRepository;

    @Mock
    private ExamMonitoringService monitoringService;

    @InjectMocks
    private QuizController controller;

    @Test
    void startExamRequiresEnoughQuestionsAndStoresAttemptInSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("studentId", 7L);
        ExtendedModelMap model = new ExtendedModelMap();

        when(questionRepository.findBySubjectIgnoreCaseOrderByIdAsc("java"))
                .thenReturn(questionBank(14));

        assertEquals(
                "exam-instructions",
                controller.startExam(30, session, model));
        assertTrue(model.containsAttribute("errorMessage"));
        assertNull(session.getAttribute("activeJavaQuestions"));

        when(questionRepository.findBySubjectIgnoreCaseOrderByIdAsc("java"))
                .thenReturn(questionBank(15));

        assertEquals("redirect:/", controller.startExam(30, session, model));
        assertEquals(1800L, session.getAttribute("examDurationSeconds"));
        assertEquals(15, ((List<?>) session.getAttribute(
                "activeJavaQuestions")).size());
        assertTrue(session.getAttribute("examStartTime") instanceof LocalDateTime);
        assertTrue(ExamAttemptSession.isUuid(
                ExamAttemptSession.getForSlot(session, "java").attemptId()));
    }

    @Test
    void submitScoresDisplayedQuestionsOnceAndClearsAttempt() {
        MockHttpSession session = activeAttempt(
                List.of(
                        new QuizController.Question("First", new String[]{"A"}, 0),
                        new QuizController.Question("Second", new String[]{"B"}, 1)
                ),
                LocalDateTime.now().minusSeconds(10),
                300L);
        ExtendedModelMap model = new ExtendedModelMap();

        String attemptId =
                ExamAttemptSession.getForSlot(session, "java").attemptId();
        stubActiveAttempt(session, attemptId, 7L, "java");
        assertEquals(
                "result",
                controller.submitQuiz(
                        Map.of("q0", "0", "q1", "not-a-number"),
                        attemptId,
                        session,
                        model));

        ArgumentCaptor<ExamResult> resultCaptor =
                ArgumentCaptor.forClass(ExamResult.class);
        verify(examResultRepository).save(resultCaptor.capture());
        ExamResult result = resultCaptor.getValue();
        assertEquals(1, result.getScore());
        assertEquals(2, result.getTotalQuestions());
        assertEquals(50, result.getPercentage());
        assertFalse(result.isPublished());
        assertNull(session.getAttribute("activeJavaQuestions"));
        assertTrue(Boolean.TRUE.equals(model.getAttribute("resultPending")));

        assertEquals(
                "redirect:/exam-instructions",
                controller.submitQuiz(Map.of(), attemptId, session, model));
        verify(examResultRepository).save(resultCaptor.getValue());
    }

    @Test
    void expiredAttemptIsNotSaved() {
        MockHttpSession session = activeAttempt(
                List.of(new QuizController.Question(
                        "Question", new String[]{"A"}, 0)),
                LocalDateTime.now().minusSeconds(400),
                300L);
        String attemptId =
                ExamAttemptSession.getForSlot(session, "java").attemptId();
        stubActiveAttempt(session, attemptId, 7L, "java");

        assertEquals(
                "exam-instructions",
                controller.submitQuiz(
                        Map.of("q0", "0"),
                        attemptId,
                        session,
                        new ExtendedModelMap()));

        verify(examResultRepository, never()).save(org.mockito.ArgumentMatchers.any());
        assertNull(session.getAttribute("activeJavaQuestions"));
    }

    @Test
    void dashboardCountsAllAttemptsButAveragesOnlyPublishedResults() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("studentId", 7L);
        ExamResult published = result(90);
        published.setPublished(true);
        ExamResult pending = result(30);
        when(examResultRepository.findByStudentIdOrderByExamDateDesc(7L))
                .thenReturn(List.of(pending, published));
        when(examScheduleRepository
                .findByExamDateGreaterThanEqualOrderByExamDateAscExamTimeAsc(
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        ExtendedModelMap model = new ExtendedModelMap();
        assertEquals("dashboard", controller.showDashboard(session, model));
        assertEquals(2, model.getAttribute("completedExams"));
        assertEquals(1L, model.getAttribute("pendingResultsCount"));
        assertEquals(90L, model.getAttribute("averagePercentage"));
    }

    private MockHttpSession activeAttempt(
            List<QuizController.Question> questions,
            LocalDateTime startTime,
            long durationSeconds) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("studentId", 7L);
        session.setAttribute("activeJavaQuestions", questions);
        session.setAttribute("examStartTime", startTime);
        session.setAttribute("examDurationSeconds", durationSeconds);
        ExamAttemptSession.start(
                session,
                "java",
                7L,
                "java",
                "java",
                startTime,
                durationSeconds);
        return session;
    }

    private void stubActiveAttempt(
            MockHttpSession session,
            String attemptId,
            Long studentId,
            String examIdentifier) {
        ExamAttemptSession.ActiveAttempt attempt =
                ExamAttemptSession.get(session, attemptId);
        when(monitoringService.lockAttemptForSubmission(
                attemptId,
                studentId))
                .thenReturn(Optional.of(new ExamMonitoringAttempt(
                        attemptId,
                        studentId,
                        examIdentifier,
                        examIdentifier,
                        attempt.startedAt(),
                        attempt.deadline())));
    }

    private List<Question> questionBank(int size) {
        List<Question> questions = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Question question = new Question();
            question.setSubject("java");
            question.setQuestionText("Question " + i);
            question.setOptionA("A");
            question.setOptionB("B");
            question.setOptionC("C");
            question.setOptionD("D");
            question.setCorrectAnswer("A");
            questions.add(question);
        }
        return questions;
    }

    private ExamResult result(int percentage) {
        return new ExamResult(
                7L, "java", percentage / 10, 10, percentage,
                LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now(),
                60L);
    }
}
