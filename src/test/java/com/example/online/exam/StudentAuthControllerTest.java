package com.example.online.exam;

import com.example.online.exam.entity.Student;
import com.example.online.exam.repository.StudentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ExtendedModelMap;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentAuthControllerTest {

    @Mock
    private StudentRepository studentRepository;

    private final PasswordEncoder passwordEncoder =
            new BCryptPasswordEncoder();

    private StudentAuthController controller;

    @BeforeEach
    void setUp() {
        controller = new StudentAuthController(
                studentRepository, passwordEncoder);
    }

    @Test
    void registrationNormalizesEmailAndStoresEncodedPassword() {
        when(studentRepository.findByEmail("student@example.com"))
                .thenReturn(Optional.empty());

        assertEquals(
                "register",
                controller.registerStudent(
                        "  Quizzy Student ",
                        " STUDENT@EXAMPLE.COM ",
                        "strong-password",
                        new ExtendedModelMap()));

        ArgumentCaptor<Student> studentCaptor =
                ArgumentCaptor.forClass(Student.class);
        verify(studentRepository).save(studentCaptor.capture());
        Student saved = studentCaptor.getValue();
        assertEquals("Quizzy Student", saved.getFullName());
        assertEquals("student@example.com", saved.getEmail());
        assertTrue(passwordEncoder.matches(
                "strong-password", saved.getPassword()));
    }

    @Test
    void duplicateEmailIsNotSaved() {
        when(studentRepository.findByEmail("student@example.com"))
                .thenReturn(Optional.of(
                        new Student("Existing Student",
                                "student@example.com", "encoded")));

        ExtendedModelMap model = new ExtendedModelMap();
        assertEquals(
                "register",
                controller.registerStudent(
                        "New Student",
                        "STUDENT@example.com",
                        "strong-password",
                        model));
        assertTrue(model.containsAttribute("error"));
        verify(studentRepository, never()).save(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void successfulLoginReplacesSessionAndStoresStudentIdentity() {
        Student student = new Student(
                "Quizzy Student",
                "student@example.com",
                passwordEncoder.encode("strong-password"));
        ReflectionTestUtils.setField(student, "id", 42L);
        when(studentRepository.findByEmail("student@example.com"))
                .thenReturn(Optional.of(student));

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession oldSession = (MockHttpSession) request.getSession(true);

        assertEquals(
                "redirect:/dashboard",
                controller.loginStudent(
                        " STUDENT@example.com ",
                        "strong-password",
                        request,
                        new ExtendedModelMap()));

        MockHttpSession newSession = (MockHttpSession) request.getSession(false);
        assertNotSame(oldSession, newSession);
        assertEquals(42L, newSession.getAttribute("studentId"));
        assertEquals(
                "Quizzy Student",
                newSession.getAttribute("studentName"));
    }
}
