package com.example.online.exam;

import com.example.online.exam.entity.ExamResult;
import com.example.online.exam.repository.ExamResultRepository;
import com.example.online.exam.repository.StudentRepository;
import com.example.online.exam.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResultManagementControllerTest {

    @Mock
    private ExamResultRepository examResultRepository;

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private AdminResultsController resultsController;

    @InjectMocks
    private AdminAnalyticsController analyticsController;

    @Test
    void adminCanPublishAnUnpublishedResult() {
        MockHttpSession session = adminSession();
        ExamResult result = result(70, false);
        when(examResultRepository.findById(5L))
                .thenReturn(Optional.of(result));

        assertEquals(
                "redirect:/admin/results",
                resultsController.publishResult(
                        5L, session, new RedirectAttributesModelMap()));

        assertTrue(result.isPublished());
        assertTrue(result.getPublishedAt() != null);
        assertTrue(!result.getPublishedAt().isAfter(LocalDateTime.now()));
        verify(examResultRepository).save(result);
        verify(notificationService).createStudentNotification(
                org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.startsWith("result-published:"),
                org.mockito.ArgumentMatchers.eq("Exam result published"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(
                        com.example.online.exam.entity.NotificationType.RESULT_PUBLISHED),
                org.mockito.ArgumentMatchers.eq("/dashboard#history"),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void repeatedPublicationDoesNotChangePublicationDateOrResave() {
        MockHttpSession session = adminSession();
        ExamResult result = result(70, false);
        LocalDateTime originalPublicationDate = LocalDateTime.now().minusDays(1);
        assertTrue(result.publishAt(originalPublicationDate));
        when(examResultRepository.findById(5L))
                .thenReturn(Optional.of(result));

        assertEquals(
                "redirect:/admin/results",
                resultsController.publishResult(
                        5L, session, new RedirectAttributesModelMap()));

        assertEquals(originalPublicationDate, result.getPublishedAt());
        verify(examResultRepository,
                org.mockito.Mockito.never()).save(result);
        verify(notificationService,
                org.mockito.Mockito.never()).createStudentNotification(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void bulkPublicationDeduplicatesIdsAndLeavesPublishedResultsUntouched() {
        MockHttpSession session = adminSession();
        ExamResult pending = result(70, false);
        ExamResult published = result(80, false);
        LocalDateTime originalPublicationDate = LocalDateTime.now().minusDays(1);
        published.publishAt(originalPublicationDate);
        when(examResultRepository.findAllById(
                org.mockito.ArgumentMatchers.anySet()))
                .thenReturn(List.of(pending, published));

        assertEquals(
                "redirect:/admin/results",
                resultsController.publishSelectedResults(
                        List.of(5L, 5L, 6L),
                        session,
                        new RedirectAttributesModelMap()));

        assertTrue(pending.isPublished());
        assertTrue(published.isPublished());
        assertEquals(originalPublicationDate, published.getPublishedAt());
        verify(examResultRepository).saveAll(List.of(pending, published));
        verify(notificationService).createStudentNotification(
                org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.startsWith("result-published:"),
                org.mockito.ArgumentMatchers.eq("Exam result published"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(
                        com.example.online.exam.entity.NotificationType.RESULT_PUBLISHED),
                org.mockito.ArgumentMatchers.eq("/dashboard#history"),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void adminAnalyticsIncludesPublishedAndPendingAttempts() {
        MockHttpSession session = adminSession();
        ExamResult published = result(90, true);
        ExamResult pending = result(30, false);
        when(examResultRepository.findAllByOrderByExamDateDesc())
                .thenReturn(List.of(published, pending));
        when(studentRepository.findAll()).thenReturn(List.of());

        ExtendedModelMap model = new ExtendedModelMap();
        assertEquals("admin-analytics", analyticsController.showAnalytics(
                session, model));
        assertEquals(2L, model.getAttribute("totalAttempts"));
        assertEquals(60.0, model.getAttribute("averagePercentage"));
        assertEquals(1L, model.getAttribute("passedAttempts"));
        assertEquals(1L, model.getAttribute("failedAttempts"));
    }

    private MockHttpSession adminSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("quizzyAdmin", true);
        return session;
    }

    private ExamResult result(int percentage, boolean published) {
        ExamResult result = new ExamResult(
                9L, "python", percentage / 10, 10, percentage,
                LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now(),
                60L);
        result.setPublished(published);
        return result;
    }
}
