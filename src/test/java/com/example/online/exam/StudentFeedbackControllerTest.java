package com.example.online.exam;

import com.example.online.exam.entity.ExamFeedback;
import com.example.online.exam.entity.ExamResult;
import com.example.online.exam.entity.Student;
import com.example.online.exam.repository.ExamFeedbackRepository;
import com.example.online.exam.repository.ExamResultRepository;
import com.example.online.exam.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentFeedbackControllerTest {

    @Mock
    private ExamFeedbackRepository feedbackRepository;

    @Mock
    private ExamResultRepository resultRepository;

    @Mock
    private StudentRepository studentRepository;

    private StudentFeedbackController controller;

    @BeforeEach
    void setUp() {
        controller = new StudentFeedbackController(
                feedbackRepository, resultRepository, studentRepository);
    }

    @Test
    void studentCanLeaveOneFeedbackForTheirCompletedResult() {
        MockHttpSession session = studentSession(12L);
        ExamResult result = completedResult(12L);
        Student student = new Student("Quizzy Student", "student@example.com", "hash");
        when(resultRepository.findByIdAndStudentId(7L, 12L))
                .thenReturn(Optional.of(result));
        when(feedbackRepository.existsByExamResult_Id(7L)).thenReturn(false);
        when(studentRepository.findById(12L)).thenReturn(Optional.of(student));

        assertEquals(
                "redirect:/feedback",
                controller.submitFeedback(
                        7L, 5, 3, 4, 5, "  Great exam  ", session,
                        new RedirectAttributesModelMap()));

        ArgumentCaptor<ExamFeedback> captor =
                ArgumentCaptor.forClass(ExamFeedback.class);
        verify(feedbackRepository).saveAndFlush(captor.capture());
        ExamFeedback saved = captor.getValue();
        assertEquals(result, saved.getExamResult());
        assertEquals(student, saved.getStudent());
        assertEquals(5, saved.getOverallRating());
        assertEquals("Great exam", saved.getComment());
    }

    @Test
    void invalidRatingsAreRejected() {
        assertEquals(
                "redirect:/feedback",
                controller.submitFeedback(
                        7L, 6, 3, 4, 5, "", studentSession(12L),
                        new RedirectAttributesModelMap()));

        verify(resultRepository, never()).findByIdAndStudentId(7L, 12L);
        verify(feedbackRepository, never()).saveAndFlush(any());
    }

    @Test
    void studentCannotLeaveFeedbackForAnotherStudentsResult() {
        when(resultRepository.findByIdAndStudentId(7L, 12L))
                .thenReturn(Optional.empty());

        assertEquals(
                "redirect:/feedback",
                controller.submitFeedback(
                        7L, 5, 3, 4, 5, "", studentSession(12L),
                        new RedirectAttributesModelMap()));

        verify(studentRepository, never()).findById(12L);
        verify(feedbackRepository, never()).saveAndFlush(any());
    }

    @Test
    void duplicateFeedbackIsRejectedBeforeSave() {
        when(resultRepository.findByIdAndStudentId(7L, 12L))
                .thenReturn(Optional.of(completedResult(12L)));
        when(feedbackRepository.existsByExamResult_Id(7L)).thenReturn(true);

        assertEquals(
                "redirect:/feedback",
                controller.submitFeedback(
                        7L, 5, 3, 4, 5, "", studentSession(12L),
                        new RedirectAttributesModelMap()));

        verify(feedbackRepository, never()).saveAndFlush(any());
    }

    @Test
    void databaseDuplicateRaceIsReportedWithoutOverwritingFeedback() {
        when(resultRepository.findByIdAndStudentId(7L, 12L))
                .thenReturn(Optional.of(completedResult(12L)));
        when(feedbackRepository.existsByExamResult_Id(7L))
                .thenReturn(false, true);
        when(studentRepository.findById(12L))
                .thenReturn(Optional.of(new Student(
                        "Quizzy Student", "student@example.com", "hash")));
        when(feedbackRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        assertEquals(
                "redirect:/feedback",
                controller.submitFeedback(
                        7L, 5, 3, 4, 5, "", studentSession(12L),
                        new RedirectAttributesModelMap()));
        verify(feedbackRepository).saveAndFlush(any());
        verify(feedbackRepository, times(2)).existsByExamResult_Id(7L);
    }

    private MockHttpSession studentSession(Long studentId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("studentId", studentId);
        return session;
    }

    private ExamResult completedResult(Long studentId) {
        ExamResult result = new ExamResult(
                studentId, "python", 4, 5, 80,
                LocalDateTime.now().minusMinutes(5),
                LocalDateTime.now(),
                300L);
        org.springframework.test.util.ReflectionTestUtils.setField(
                result, "id", 7L);
        return result;
    }
}
