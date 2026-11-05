package com.example.online.exam;

import com.example.online.exam.entity.Student;
import com.example.online.exam.repository.StudentRepository;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:quizzy-profile-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
class StudentProfileControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Autowired
    private StudentRepository studentRepository;

    private MockMvc mockMvc;
    private Student student;
    private Student otherStudent;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        student = studentRepository.saveAndFlush(new Student(
                "Profile Student", "profile-" + suffix + "@example.test", "hash"));
        otherStudent = studentRepository.saveAndFlush(new Student(
                "Other Student", "other-" + suffix + "@example.test", "hash"));
        mockMvc = webAppContextSetup(webApplicationContext)
                .addFilters((Filter) springSecurityFilterChain)
                .build();
    }

    @Test
    void authenticatedStudentViewsProfileWithoutSensitiveFields()
            throws Exception {
        mockMvc.perform(get("/profile").session(studentSession(student)))
                .andExpect(status().isOk())
                .andExpect(view().name("student-profile"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        student.getFullName())))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        student.getEmail())))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("name=\"password\""))));
    }

    @Test
    void studentCanUpdateOwnNameAndEmailAndCannotChooseAnotherStudent()
            throws Exception {
        MockHttpSession session = studentSession(student);
        String token = csrfFrom(session);
        String updatedEmail = "updated-" + UUID.randomUUID() + "@example.test";

        mockMvc.perform(post("/profile")
                        .session(session)
                        .header("X-CSRF-TOKEN", token)
                        .param("studentId", otherStudent.getId().toString())
                        .param("fullName", "  Updated Student  ")
                        .param("email", updatedEmail.toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(view().name("student-profile"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Your profile has been updated.")));

        Student updated = studentRepository.findById(student.getId()).orElseThrow();
        Student unchanged = studentRepository.findById(otherStudent.getId()).orElseThrow();
        assertEquals("Updated Student", updated.getFullName());
        assertEquals(updatedEmail, updated.getEmail());
        assertEquals("Updated Student", session.getAttribute("studentName"));
        assertEquals("Other Student", unchanged.getFullName());
        assertEquals(otherStudent.getEmail(), unchanged.getEmail());
    }

    @Test
    void invalidAndDuplicateEmailUpdatesAreRejected()
            throws Exception {
        MockHttpSession session = studentSession(student);
        String token = csrfFrom(session);

        mockMvc.perform(post("/profile")
                        .session(session)
                        .header("X-CSRF-TOKEN", token)
                        .param("fullName", " ")
                        .param("email", "not-an-email"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Enter a name")));

        mockMvc.perform(post("/profile")
                        .session(session)
                        .header("X-CSRF-TOKEN", token)
                        .param("fullName", "New Name")
                        .param("email", otherStudent.getEmail()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "That email address is already in use.")));

        Student unchanged = studentRepository.findById(student.getId()).orElseThrow();
        assertEquals("Profile Student", unchanged.getFullName());
        assertEquals(student.getEmail(), unchanged.getEmail());
    }

    @Test
    void profileRequiresAuthenticatedSessionAndCsrf()
            throws Exception {
        mockMvc.perform(get("/profile"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        mockMvc.perform(get("/profile")
                        .session(studentSession(otherStudent)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        otherStudent.getEmail())))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(student.getEmail()))));

        mockMvc.perform(post("/profile")
                        .session(studentSession(student))
                        .param("fullName", "No CSRF")
                        .param("email", "csrf-" + UUID.randomUUID()
                                + "@example.test"))
                .andExpect(status().isForbidden());
    }

    private String csrfFrom(MockHttpSession session) throws Exception {
        String html = mockMvc.perform(get("/profile").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Matcher matcher = Pattern.compile(
                        "name=\"_csrf\"[^>]*value=\"([^\"]+)\"")
                .matcher(html);
        assertTrue(matcher.find(), "Profile form must render a CSRF token.");
        return matcher.group(1);
    }

    private MockHttpSession studentSession(Student account) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("studentId", account.getId());
        session.setAttribute("studentName", account.getFullName());
        return session;
    }
}
