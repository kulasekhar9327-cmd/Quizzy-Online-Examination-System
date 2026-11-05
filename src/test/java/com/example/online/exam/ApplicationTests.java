package com.example.online.exam;

import com.example.online.exam.entity.ExamSchedule;
import com.example.online.exam.entity.ExamResult;
import com.example.online.exam.entity.Student;
import com.example.online.exam.entity.ScheduledExamQuestion;
import com.example.online.exam.repository.ExamResultRepository;
import com.example.online.exam.repository.ExamScheduleRepository;
import com.example.online.exam.repository.ScheduledExamQuestionRepository;
import com.example.online.exam.repository.StudentRepository;
import com.example.online.exam.service.ExamAttemptSession;
import com.example.online.exam.service.ExamAttemptSession.AttemptStatus;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:quizzy-context-test;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
class ApplicationTests {

	@Autowired
	private WebApplicationContext webApplicationContext;

	@Autowired
	private ExamScheduleRepository examScheduleRepository;

	@Autowired
	private ScheduledExamQuestionRepository scheduledQuestionRepository;

	@Autowired
	private ExamResultRepository examResultRepository;

	@Autowired
	private StudentRepository studentRepository;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	private MockMvc mockMvc;

	@Value("${spring.datasource.url}")
	private String datasourceUrl;

	@BeforeEach
	void assertTestDatabaseIsIsolated() {
		Assertions.assertTrue(
				datasourceUrl.startsWith("jdbc:h2:mem:"),
				"Tests must use an in-memory H2 database");
		mockMvc = webAppContextSetup(webApplicationContext)
				.addFilters((Filter) springSecurityFilterChain)
				.build();
	}

	@Test
	void contextLoads() {
		Assertions.assertNotNull(mockMvc);
	}

	@Test
	void javaExamSubmissionAcceptsRenderedCsrfToken()
			throws Exception {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute("studentId", 1L);
		session.setAttribute(
				"activeJavaQuestions",
				List.of(new QuizController.Question(
						"Question", new String[]{"A", "B", "C", "D"}, 0)));
		session.setAttribute("examStartTime", LocalDateTime.now());
		session.setAttribute("examDurationSeconds", 600L);

		String html = mockMvc.perform(get("/").session(session))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		String csrfToken = csrfToken(html);
		long previousResults = examResultRepository
				.findByStudentIdOrderByExamDateDesc(1L).size();

		mockMvc.perform(post("/submit")
						.session(session)
						.header("X-CSRF-TOKEN", csrfToken)
						.param(
								"attemptId",
								ExamAttemptSession.getForSlot(
										session, "java").attemptId())
						.param("q0", "0"))
				.andExpect(status().isOk())
				.andExpect(view().name("result"));

		Assertions.assertEquals(
				previousResults + 1,
				examResultRepository
						.findByStudentIdOrderByExamDateDesc(1L).size());
	}

	@Test
	void javaExamSubmissionRejectsMissingCsrfToken()
			throws Exception {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute("studentId", 1L);
		session.setAttribute(
				"activeJavaQuestions",
				List.of(new QuizController.Question(
						"Question", new String[]{"A", "B", "C", "D"}, 0)));
		session.setAttribute("examStartTime", LocalDateTime.now());
		session.setAttribute("examDurationSeconds", 600L);
		long previousResults = examResultRepository
				.findByStudentIdOrderByExamDateDesc(1L).size();

		mockMvc.perform(post("/submit")
						.session(session)
						.param("q0", "0"))
				.andExpect(status().isForbidden());

		Assertions.assertEquals(
				previousResults,
				examResultRepository
						.findByStudentIdOrderByExamDateDesc(1L).size());
	}

	@Test
	void submittingJavaAttemptPreservesConcurrentSubjectAttempt()
			throws Exception {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute("studentId", 1L);
		session.setAttribute(
				"activeJavaQuestions",
				List.of(new QuizController.Question(
						"Java question", new String[]{"A", "B", "C", "D"}, 0)));
		session.setAttribute("examStartTime", LocalDateTime.now());
		session.setAttribute("examDurationSeconds", 600L);
		session.setAttribute("activeSubject", "python");
		session.setAttribute(
				"activeSubjectQuestions",
				List.of(new QuizController.Question(
						"Python question", new String[]{"A", "B", "C", "D"}, 0)));
		session.setAttribute("activeSubjectStartTime", LocalDateTime.now());
		session.setAttribute("activeSubjectDurationSeconds", 600L);

		String javaHtml = mockMvc.perform(get("/").session(session))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		String subjectHtml = mockMvc.perform(
						get("/exams/active/python").session(session))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		String javaAttemptId = ExamAttemptSession
				.getForSlot(session, "java").attemptId();
		String subjectAttemptId = ExamAttemptSession
				.getForSlot(session, "subject").attemptId();
		Assertions.assertNotEquals(javaAttemptId, subjectAttemptId);

		mockMvc.perform(post("/submit")
						.session(session)
						.header("X-CSRF-TOKEN", csrfToken(javaHtml))
						.param("attemptId", javaAttemptId)
						.param("q0", "0"))
				.andExpect(status().isOk())
				.andExpect(view().name("result"));

		Assertions.assertEquals(
				AttemptStatus.ACTIVE,
				ExamAttemptSession.get(session, subjectAttemptId).status());
		mockMvc.perform(post("/exams/submit")
						.session(session)
						.header("X-CSRF-TOKEN", csrfToken(subjectHtml))
						.param("attemptId", subjectAttemptId)
						.param("subject", "python")
						.param("q0", "0"))
				.andExpect(status().isOk())
				.andExpect(view().name("result"));
	}

	@Test
	void scheduledExamSubmissionAcceptsRenderedCsrfToken()
			throws Exception {
		ExamSchedule exam = new ExamSchedule(
				"Scheduled Test",
				"Java",
				LocalDate.now().plusDays(1),
				LocalTime.NOON,
				"Test exam");
		ExamSchedule savedExam = examScheduleRepository.save(exam);
		MockHttpSession session = new MockHttpSession();
		session.setAttribute("studentId", 1L);
		session.setAttribute("activeScheduledExamId", savedExam.getId());
		session.setAttribute(
				"activeScheduledQuestions",
				List.of(new ScheduledExamController.ExamQuestion(
						"Question", new String[]{"A", "B", "C", "D"}, 0)));
		session.setAttribute(
				"scheduledExamStartTime",
				LocalDateTime.now().minusSeconds(10));
		session.setAttribute("scheduledExamDurationSeconds", 600L);

		String html = mockMvc.perform(
						get("/scheduled-exams/" + savedExam.getId())
								.session(session))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		mockMvc.perform(post(
						"/scheduled-exams/{id}/submit",
						savedExam.getId())
						.session(session)
						.header("X-CSRF-TOKEN", csrfToken(html))
						.param(
								"attemptId",
								ExamAttemptSession.getForSlot(
										session, "scheduled").attemptId())
						.param("q0", "0"))
				.andExpect(status().isOk())
				.andExpect(view().name("result"));
	}

	@Test
	void startingAndSubmittingScheduledExamPreservesJavaAttempt()
			throws Exception {
		LocalDateTime scheduledStart = LocalDateTime.now().minusMinutes(1);
		ExamSchedule exam = new ExamSchedule(
				"Scheduled Test",
				"Java",
				scheduledStart.toLocalDate(),
				scheduledStart.toLocalTime(),
				"Test exam");
		exam.setDurationMinutes(30);
		ExamSchedule savedExam = examScheduleRepository.save(exam);
		ScheduledExamQuestion question = new ScheduledExamQuestion();
		question.setExamSchedule(savedExam);
		question.setQuestionText("Scheduled question");
		question.setOptionA("A");
		question.setOptionB("B");
		question.setOptionC("C");
		question.setOptionD("D");
		question.setCorrectAnswer("A");
		scheduledQuestionRepository.save(question);

		MockHttpSession session = new MockHttpSession();
		session.setAttribute("studentId", 1L);
		session.setAttribute(
				"activeJavaQuestions",
				List.of(new QuizController.Question(
						"Java question", new String[]{"A", "B", "C", "D"}, 0)));
		session.setAttribute("examStartTime", LocalDateTime.now());
		session.setAttribute("examDurationSeconds", 600L);
		mockMvc.perform(get("/").session(session))
				.andExpect(status().isOk());
		String javaAttemptId = ExamAttemptSession
				.getForSlot(session, "java").attemptId();
		String csrfToken = csrfToken(mockMvc.perform(get("/login")
						.session(session))
				.andReturn()
				.getResponse()
				.getContentAsString());

		mockMvc.perform(post(
						"/scheduled-exams/{id}/start",
						savedExam.getId())
						.session(session)
						.header("X-CSRF-TOKEN", csrfToken))
				.andExpect(status().is3xxRedirection());

		String scheduledHtml = mockMvc.perform(get(
						"/scheduled-exams/{id}",
						savedExam.getId())
						.session(session))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		String scheduledAttemptId = ExamAttemptSession
				.getForSlot(session, "scheduled").attemptId();
		Assertions.assertNotEquals(javaAttemptId, scheduledAttemptId);
		Assertions.assertEquals(
				AttemptStatus.ACTIVE,
				ExamAttemptSession.get(session, javaAttemptId).status());

		mockMvc.perform(post(
						"/scheduled-exams/{id}/submit",
						savedExam.getId())
						.session(session)
						.header("X-CSRF-TOKEN", csrfToken(scheduledHtml))
						.param("attemptId", scheduledAttemptId)
						.param("q0", "0"))
				.andExpect(status().isOk())
				.andExpect(view().name("result"));
		Assertions.assertEquals(
				AttemptStatus.ACTIVE,
				ExamAttemptSession.get(session, javaAttemptId).status());
	}

	@Test
	void javaExamRendersSharedSecurityPanelAndMonitoringScript()
			throws Exception {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute("studentId", 1L);
		session.setAttribute(
				"activeJavaQuestions",
				List.of(new QuizController.Question(
						"Question", new String[]{"A", "B", "C", "D"}, 0)));
		session.setAttribute("examStartTime", LocalDateTime.now());
		session.setAttribute("examDurationSeconds", 600L);

		assertExamPage(
				"/",
				session,
				"quiz",
				"data-exam-form");
	}

	@Test
	void sharedExamScriptGuardsActiveFullscreenExitSubmission()
			throws Exception {
		mockMvc.perform(get("/js/exam-experience.js"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(
						"let fullscreenWasEntered")))
				.andExpect(content().string(containsString(
						"&& workspaceStarted")))
				.andExpect(content().string(containsString(
						"details.exited")))
				.andExpect(content().string(containsString(
						"if (submissionStarted)")))
				.andExpect(content().string(containsString(
						"event.stopImmediatePropagation()")))
				.andExpect(content().string(containsString(
						"form.requestSubmit()")))
				.andExpect(content().string(containsString(
						"Submitting your examination...")));
	}

	@Test
	void studentFeedbackAndAdminReviewPagesRender()
			throws Exception {
		Student student = studentRepository.save(new Student(
				"Feedback Student",
				"feedback-" + UUID.randomUUID() + "@example.com",
				"test-password-hash"));
		ExamResult result = examResultRepository.save(new ExamResult(
				student.getId(),
				"python",
				4,
				5,
				80,
				LocalDateTime.now().minusMinutes(5),
				LocalDateTime.now(),
				300L));

		MockHttpSession studentSession = new MockHttpSession();
		studentSession.setAttribute("studentId", student.getId());
		mockMvc.perform(get("/feedback").session(studentSession))
				.andExpect(status().isOk())
				.andExpect(view().name("student-feedback"))
				.andExpect(content().string(containsString("Python")))
				.andExpect(content().string(
						containsString("/feedback/" + result.getId())))
				.andExpect(content().string(containsString("Your Experience Matters!")))
				.andExpect(content().string(containsString("name=\"overallRating\"")))
				.andExpect(content().string(containsString("name=\"difficultyRating\"")))
				.andExpect(content().string(containsString("name=\"qualityRating\"")))
				.andExpect(content().string(containsString("name=\"experienceRating\"")))
				.andExpect(content().string(containsString("name=\"_csrf\"")))
				.andExpect(content().string(containsString("data-value=\"5\"")))
				.andExpect(content().string(containsString("maxlength=\"1000\"")))
				.andExpect(content().string(containsString("Very Hard")));

		MockHttpSession adminSession = new MockHttpSession();
		adminSession.setAttribute("quizzyAdmin", true);
		mockMvc.perform(get("/admin/feedback").session(adminSession))
				.andExpect(status().isOk())
				.andExpect(view().name("admin-feedback"));
		mockMvc.perform(get("/admin/results").session(adminSession))
				.andExpect(status().isOk())
				.andExpect(view().name("admin-results"));
	}

	@Test
	void subjectExamTypesRenderSharedSecureExamWorkspace()
			throws Exception {
		for (String subject : List.of(
				"python", "sql", "aptitude", "mock-tests")) {
			MockHttpSession session = new MockHttpSession();
			session.setAttribute("studentId", 1L);
			session.setAttribute("activeSubject", subject);
			session.setAttribute(
					"activeSubjectQuestions",
					List.of(new QuizController.Question(
							"Question", new String[]{"A", "B", "C", "D"}, 0)));
			session.setAttribute(
					"activeSubjectStartTime", LocalDateTime.now());
			session.setAttribute("activeSubjectDurationSeconds", 600L);

			assertExamPage(
					"/exams/active/" + subject,
					session,
					"exam",
					"data-exam-form");
		}
	}

	@Test
	void scheduledExamRendersSharedSecurityPanelAndMonitoringScript()
			throws Exception {
		ExamSchedule exam = new ExamSchedule(
				"Scheduled Test",
				"Java",
				LocalDate.now().plusDays(1),
				LocalTime.NOON,
				"Test exam");
		ExamSchedule savedExam = examScheduleRepository.save(exam);

		MockHttpSession session = new MockHttpSession();
		session.setAttribute("studentId", 1L);
		session.setAttribute("activeScheduledExamId", savedExam.getId());
		session.setAttribute(
				"activeScheduledQuestions",
				List.of(new ScheduledExamController.ExamQuestion(
						"Question", new String[]{"A", "B", "C", "D"}, 0)));
		session.setAttribute("scheduledExamStartTime", LocalDateTime.now());
		session.setAttribute("scheduledExamDurationSeconds", 600L);

		assertExamPage(
				"/scheduled-exams/" + savedExam.getId(),
				session,
				"scheduled-exam",
				"data-exam-form");
	}

	private void assertExamPage(
			String path,
			MockHttpSession session,
			String template,
			String formMarker) throws Exception {
		mockMvc.perform(get(path).session(session))
				.andExpect(status().isOk())
				.andExpect(view().name(template))
				.andExpect(content().string(containsString("Exam security")))
				.andExpect(content().string(
						containsString("Server-confirmed warnings:")))
				.andExpect(content().string(
						containsString("data-warning-limit")))
				.andExpect(content().string(
						containsString("data-pending-warning-count")))
				.andExpect(content().string(
						containsString("data-pending-warning-status")))
				.andExpect(content().string(
						containsString("accepted unique violations")))
				.andExpect(content().string(
						containsString("disqualifies this exam")))
				.andExpect(content().string(
						containsString("not conclusive proof of cheating")))
				.andExpect(content().string(
						org.hamcrest.Matchers.not(containsString(
								"does not disqualify you"))))
				.andExpect(content().string(containsString(formMarker)))
				.andExpect(content().string(containsString("data-exam-preface")))
				.andExpect(content().string(containsString(
						"data-exam-workspace hidden")))
				.andExpect(content().string(containsString("data-exam-mode")))
				.andExpect(content().string(containsString(
						"/js/exam-experience.js")))
				.andExpect(content().string(containsString("name=\"attemptId\"")))
				.andExpect(content().string(containsString("name=\"_csrf\"")))
				.andExpect(content().string(containsString("name=\"_csrf\" content=")))
				.andExpect(content().string(containsString(
						"/js/exam-monitoring.js")));
		String slot = switch (template) {
			case "quiz" -> "java";
			case "exam" -> "subject";
			default -> "scheduled";
		};
		Assertions.assertTrue(ExamAttemptSession.isUuid(
				ExamAttemptSession.getForSlot(session, slot).attemptId()));
	}

	private String csrfToken(String html) {
		java.util.regex.Matcher csrf = java.util.regex.Pattern.compile(
				"<meta name=\"_csrf\" content=\"([^\"]+)\"")
				.matcher(html);
		if (csrf.find()) {
			return csrf.group(1);
		}
		csrf = java.util.regex.Pattern.compile(
				"name=\"_csrf\"\\s+value=\"([^\"]+)\"")
				.matcher(html);
		Assertions.assertTrue(
				csrf.find(),
				"Page must render a CSRF token.");
		return csrf.group(1);
	}

}
