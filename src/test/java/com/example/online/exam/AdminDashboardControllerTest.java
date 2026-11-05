package com.example.online.exam;

import com.example.online.exam.entity.ExamResult;
import com.example.online.exam.entity.ExamSchedule;
import com.example.online.exam.entity.Question;
import com.example.online.exam.entity.Student;
import com.example.online.exam.repository.ExamResultRepository;
import com.example.online.exam.repository.ExamScheduleRepository;
import com.example.online.exam.repository.QuestionRepository;
import com.example.online.exam.repository.StudentRepository;
import com.example.online.exam.repository.SupportConversationRepository;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:quizzy-admin-dashboard-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
class AdminDashboardControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private ExamScheduleRepository examScheduleRepository;

    @Autowired
    private ExamResultRepository examResultRepository;

    @Autowired
    private SupportConversationRepository supportConversationRepository;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        supportConversationRepository.deleteAll();
        examResultRepository.deleteAll();
        examScheduleRepository.deleteAll();
        questionRepository.deleteAll();
        studentRepository.deleteAll();
        mockMvc = webAppContextSetup(webApplicationContext)
                .addFilters((Filter) springSecurityFilterChain)
                .build();
    }

    @Test
    void adminCanAccessDashboardAndUnauthenticatedOrStudentCannot()
            throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));

        MockHttpSession studentSession = new MockHttpSession();
        studentSession.setAttribute("studentId", 42L);
        mockMvc.perform(get("/admin/dashboard").session(studentSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));

        mockMvc.perform(get("/admin/dashboard").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("totalStudents", 0L))
                .andExpect(model().attribute("totalAttempts", 0L))
                .andExpect(model().attribute("averagePercentage", 0.0))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString(
                                "Subject analytics will appear")));
    }

    @Test
    void statisticsAndSubjectAnalyticsMatchValidDatabaseRecords()
            throws Exception {
        Student firstStudent = saveStudent("Dashboard One");
        saveStudent("Dashboard Two");
        saveQuestion();
        examScheduleRepository.save(new ExamSchedule(
                "Dashboard Exam",
                "java",
                LocalDate.now().plusDays(4),
                LocalTime.NOON,
                "Test schedule"));

        LocalDateTime now = LocalDateTime.now();
        ExamResult javaFirst = saveResult(
                firstStudent, "java", 80, 20, now.minusDays(1));
        saveResult(firstStudent, "java", 60, 20, now);
        ExamResult sqlResult = saveResult(
                firstStudent, "sql", 60, 20, now.minusHours(1));
        sqlResult.publishAt(now.plusMinutes(1));
        examResultRepository.saveAndFlush(sqlResult);
        saveResult(firstStudent, "invalid", 140, 20, now);
        saveResult(firstStudent, "incomplete", 75, 0, now);

        mockMvc.perform(get("/admin/dashboard").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("totalStudents", 2L))
                .andExpect(model().attribute("totalQuestions", 1L))
                .andExpect(model().attribute("totalSchedules", 1L))
                .andExpect(model().attribute("totalAttempts", 3L))
                .andExpect(model().attribute("totalPublishedResults", 1L))
                .andExpect(model().attribute("averagePercentage", 200.0 / 3.0))
                .andExpect(model().attributeExists(
                        "subjectMetrics", "participationTrend",
                        "recentAttempts", "recentPublishedResults"));

        var response = mockMvc.perform(
                        get("/admin/dashboard").session(adminSession()))
                .andReturn();
        @SuppressWarnings("unchecked")
        List<AdminDashboardController.SubjectMetric> metrics =
                (List<AdminDashboardController.SubjectMetric>) response
                        .getModelAndView().getModel().get("subjectMetrics");
        assertEquals(2, metrics.size());
        assertEquals("java", metrics.get(0).subject());
        assertEquals(2, metrics.get(0).attempts());
        assertEquals(70.0, metrics.get(0).averagePercentage());
        assertEquals("sql", metrics.get(1).subject());
        assertEquals(60.0, metrics.get(1).averagePercentage());

        @SuppressWarnings("unchecked")
        List<AdminDashboardController.RecentAttempt> recent =
                (List<AdminDashboardController.RecentAttempt>) response
                        .getModelAndView().getModel().get("recentAttempts");
        assertEquals(3, recent.size());
        assertEquals("java", recent.get(0).subject());
        assertEquals(60, recent.get(0).percentage());

        @SuppressWarnings("unchecked")
        List<AdminDashboardController.TrendMetric> trend =
                (List<AdminDashboardController.TrendMetric>) response
                        .getModelAndView().getModel().get("participationTrend");
        assertEquals(2, trend.size());
        assertTrue(trend.get(1).date().isAfter(trend.get(0).date()));

        @SuppressWarnings("unchecked")
        List<AdminDashboardController.RecentPublishedResult> published =
                (List<AdminDashboardController.RecentPublishedResult>) response
                        .getModelAndView().getModel()
                        .get("recentPublishedResults");
        assertEquals(1, published.size());
        assertEquals("sql", published.get(0).subject());

        assertTrue(javaFirst.getExamDate().isBefore(now));
    }

    @Test
    void recentAttemptsAreSortedNewestFirst()
            throws Exception {
        Student student = saveStudent("Recent Activity Student");
        LocalDateTime now = LocalDateTime.now();
        saveResult(student, "older", 40, 10, now.minusDays(2));
        saveResult(student, "newer", 90, 10, now);

        var response = mockMvc.perform(
                        get("/admin/dashboard").session(adminSession()))
                .andExpect(status().isOk())
                .andReturn();
        @SuppressWarnings("unchecked")
        List<AdminDashboardController.RecentAttempt> recent =
                (List<AdminDashboardController.RecentAttempt>) response
                        .getModelAndView().getModel().get("recentAttempts");
        assertEquals("newer", recent.get(0).subject());
        assertEquals("older", recent.get(1).subject());
    }

    @Test
    void quickNavigationPointsToExistingGetMappings()
            throws Exception {
        Set<String> getMappings = handlerMapping.getHandlerMethods().entrySet()
                .stream()
                .filter(entry -> entry.getValue().getMethod()
                        .getAnnotation(org.springframework.web.bind.annotation.GetMapping.class)
                        != null)
                .flatMap(entry -> entry.getKey().getPatternValues().stream())
                .collect(Collectors.toSet());

        assertTrue(getMappings.containsAll(Set.of(
                "/admin/questions",
                "/admin/results",
                "/admin/schedules",
                "/admin/feedback",
                "/admin/support",
                "/admin/announcements",
                "/admin/analytics")));

        mockMvc.perform(get("/admin/dashboard").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString(
                                "href=\"/admin/questions\"")))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString(
                                "href=\"/admin/announcements\"")));
    }

    private Student saveStudent(String name) {
        String email = "dashboard-" + UUID.randomUUID() + "@example.test";
        return studentRepository.saveAndFlush(
                new Student(name, email, "test-hash"));
    }

    private void saveQuestion() {
        Question question = new Question();
        question.setSubject("java");
        question.setQuestionText("Dashboard test question");
        question.setOptionA("A");
        question.setOptionB("B");
        question.setOptionC("C");
        question.setOptionD("D");
        question.setCorrectAnswer("A");
        questionRepository.saveAndFlush(question);
    }

    private ExamResult saveResult(
            Student student,
            String subject,
            int percentage,
            int totalQuestions,
            LocalDateTime submittedAt) {
        return examResultRepository.saveAndFlush(new ExamResult(
                student.getId(),
                subject,
                Math.max(0, percentage * Math.max(totalQuestions, 1) / 100),
                totalQuestions,
                percentage,
                submittedAt.minusMinutes(10),
                submittedAt,
                600L));
    }

    private MockHttpSession adminSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("quizzyAdmin", true);
        return session;
    }
}
