package com.example.online.exam;

import com.example.online.exam.entity.ExamResult;
import com.example.online.exam.entity.ExamMonitoringAttempt;
import com.example.online.exam.entity.ExamSchedule;
import com.example.online.exam.entity.ScheduledExamQuestion;
import com.example.online.exam.repository.ExamResultRepository;
import com.example.online.exam.repository.ExamScheduleRepository;
import com.example.online.exam.repository.ScheduledExamQuestionRepository;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledExamControllerTest {

    @Mock
    private ExamResultRepository examResultRepository;

    @Mock
    private ExamScheduleRepository examScheduleRepository;

    @Mock
    private ScheduledExamQuestionRepository scheduledQuestionRepository;

    @Mock
    private ExamMonitoringService monitoringService;

    @InjectMocks
    private ScheduledExamController controller;

    @Test
    void scheduledExamCannotStartBeforeItsTimeWindow() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("studentId", 18L);
        ExamSchedule exam = scheduledExam(
                LocalDate.now().plusDays(1), LocalTime.NOON);
        when(examScheduleRepository.findById(4L))
                .thenReturn(Optional.of(exam));

        assertEquals(
                "scheduled-exam-unavailable",
                controller.startScheduledExam(
                        4L, session, new ExtendedModelMap()));
        verify(scheduledQuestionRepository, never())
                .findByExamSchedule_IdOrderByIdAsc(4L);
    }

    @Test
    void scheduledSubmissionSavesScoreOnceAndClearsSessionAttempt() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("studentId", 18L);
        session.setAttribute("activeScheduledExamId", 4L);
        session.setAttribute(
                "activeScheduledQuestions",
                List.of(
                        new ScheduledExamController.ExamQuestion(
                                "First", new String[]{"A", "B"}, 1),
                        new ScheduledExamController.ExamQuestion(
                                "Second", new String[]{"A", "B"}, 0)
                ));
        session.setAttribute(
                "scheduledExamStartTime",
                LocalDateTime.now().minusSeconds(10));
        session.setAttribute("scheduledExamDurationSeconds", 300L);
        String attemptId = ExamAttemptSession.start(
                session,
                "scheduled",
                18L,
                "scheduled-exam:4",
                "sql",
                LocalDateTime.now().minusSeconds(10),
                300L);
        ExamAttemptSession.ActiveAttempt attempt =
                ExamAttemptSession.get(session, attemptId);
        when(monitoringService.lockAttemptForSubmission(
                attemptId,
                18L))
                .thenReturn(Optional.of(new ExamMonitoringAttempt(
                        attemptId,
                        18L,
                        "scheduled-exam:4",
                        "sql",
                        attempt.startedAt(),
                        attempt.deadline())));
        ExamSchedule exam = scheduledExam(LocalDate.now(), LocalTime.NOON);
        exam.setSubject("sql");
        when(examScheduleRepository.findById(4L))
                .thenReturn(Optional.of(exam));

        assertEquals(
                "result",
                controller.submitScheduledExam(
                        4L,
                        Map.of("q0", "1", "q1", "not-a-number"),
                        attemptId,
                        session,
                        new ExtendedModelMap()));

        ArgumentCaptor<ExamResult> resultCaptor =
                ArgumentCaptor.forClass(ExamResult.class);
        verify(examResultRepository).save(resultCaptor.capture());
        ExamResult result = resultCaptor.getValue();
        assertEquals(1, result.getScore());
        assertEquals(2, result.getTotalQuestions());
        assertEquals(50, result.getPercentage());
        assertEquals("sql", result.getSubject());
        assertNull(session.getAttribute("activeScheduledExamId"));

        assertEquals(
                "redirect:/dashboard",
                controller.submitScheduledExam(
                        4L,
                        Map.of(),
                        attemptId,
                        session,
                        new ExtendedModelMap()));
        verify(examResultRepository).save(resultCaptor.getValue());
    }

    private ExamSchedule scheduledExam(LocalDate date, LocalTime time) {
        ExamSchedule exam = new ExamSchedule();
        exam.setExamName("Scheduled test");
        exam.setSubject("java");
        exam.setExamDate(date);
        exam.setExamTime(time);
        exam.setDurationMinutes(30);
        return exam;
    }
}
