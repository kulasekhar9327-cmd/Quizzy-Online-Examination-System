package com.example.online.exam;

import com.example.online.exam.entity.ExamResult;
import com.example.online.exam.entity.ExamMonitoringAttempt;
import com.example.online.exam.entity.Question;
import com.example.online.exam.repository.ExamResultRepository;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubjectExamControllerTest {

    @Mock
    private QuestionRepository questionRepository;

    @Mock
    private ExamResultRepository examResultRepository;

    @Mock
    private ExamMonitoringService monitoringService;

    @InjectMocks
    private SubjectExamController controller;

    @Test
    void startingSubjectExamRequiresBankAndCreatesSessionAttempt() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("studentId", 12L);
        ExtendedModelMap model = new ExtendedModelMap();

        when(questionRepository.findBySubjectIgnoreCaseOrderByIdAsc("python"))
                .thenReturn(questionBank(14));
        assertEquals(
                "exam-instructions",
                controller.startSubjectExam(
                        "Python", 30, session, model));
        verify(examResultRepository, never()).save(
                org.mockito.ArgumentMatchers.any());

        when(questionRepository.findBySubjectIgnoreCaseOrderByIdAsc("python"))
                .thenReturn(questionBank(15));
        assertEquals(
                "redirect:/exams/active/python",
                controller.startSubjectExam(
                        "Python", 30, session, model));
        assertEquals("python", session.getAttribute("activeSubject"));
        assertEquals(1800L, session.getAttribute("activeSubjectDurationSeconds"));
        org.junit.jupiter.api.Assertions.assertTrue(
                ExamAttemptSession.isUuid(
                        ExamAttemptSession.getForSlot(
                                session, "subject").attemptId()));
    }

    @Test
    void supportedSubjectExamModesStillStart() {
        for (String subject : List.of(
                "python", "sql", "aptitude", "mock-tests")) {
            MockHttpSession session = new MockHttpSession();
            session.setAttribute("studentId", 12L);
            when(questionRepository
                    .findBySubjectIgnoreCaseOrderByIdAsc(subject))
                    .thenReturn(questionBank(15));

            assertEquals(
                    "redirect:/exams/active/" + subject,
                    controller.startSubjectExam(
                                    subject,
                                    30,
                                    session,
                                    new ExtendedModelMap()));
            assertEquals(
                    subject,
                    ExamAttemptSession.getForSlot(
                                    session,
                                    "subject").examIdentifier());
        }
    }

    @Test
    void subjectSubmissionScoresAndConsumesAttemptOnce() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("studentId", 12L);
        session.setAttribute("activeSubject", "sql");
        session.setAttribute(
                "activeSubjectQuestions",
                List.of(
                        new QuizController.Question("First", new String[]{"A"}, 0),
                        new QuizController.Question("Second", new String[]{"B"}, 1)
                ));
        session.setAttribute(
                "activeSubjectStartTime",
                LocalDateTime.now().minusSeconds(10));
        session.setAttribute("activeSubjectDurationSeconds", 300L);
        String attemptId = ExamAttemptSession.start(
                session,
                "subject",
                12L,
                "sql",
                "sql",
                LocalDateTime.now().minusSeconds(10),
                300L);
        ExamAttemptSession.ActiveAttempt attempt =
                ExamAttemptSession.get(session, attemptId);
        when(monitoringService.lockAttemptForSubmission(
                attemptId,
                12L))
                .thenReturn(Optional.of(new ExamMonitoringAttempt(
                        attemptId,
                        12L,
                        "sql",
                        "sql",
                        attempt.startedAt(),
                        attempt.deadline())));

        assertEquals(
                "result",
                controller.submitSubjectExam(
                        Map.of("q0", "0", "q1", "invalid"),
                        "sql",
                        attemptId,
                        session,
                        new ExtendedModelMap()));

        ArgumentCaptor<ExamResult> resultCaptor =
                ArgumentCaptor.forClass(ExamResult.class);
        verify(examResultRepository).save(resultCaptor.capture());
        assertEquals("sql", resultCaptor.getValue().getSubject());
        assertEquals(1, resultCaptor.getValue().getScore());
        assertEquals(50, resultCaptor.getValue().getPercentage());
        assertNull(session.getAttribute("activeSubjectQuestions"));

        assertEquals(
                "redirect:/dashboard",
                controller.submitSubjectExam(
                        Map.of(),
                        "sql",
                        attemptId,
                        session,
                        new ExtendedModelMap()));
        verify(examResultRepository).save(resultCaptor.getValue());
    }

    private List<Question> questionBank(int size) {
        List<Question> questions = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Question question = new Question();
            question.setSubject("python");
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
}
