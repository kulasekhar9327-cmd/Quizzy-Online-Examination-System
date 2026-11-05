package com.example.online.exam;

import com.example.online.exam.entity.ExamMonitoringViolation;
import com.example.online.exam.entity.ExamMonitoringAttempt;
import com.example.online.exam.entity.Student;
import com.example.online.exam.repository.ExamMonitoringAttemptRepository;
import com.example.online.exam.repository.ExamMonitoringViolationRepository;
import com.example.online.exam.repository.ExamResultRepository;
import com.example.online.exam.repository.StudentRepository;
import com.example.online.exam.service.ExamAttemptSession;
import com.example.online.exam.service.ExamAttemptSession.AttemptStatus;
import com.example.online.exam.service.ExamMonitoringService;
import com.example.online.exam.service.ExamMonitoringService.Outcome;
import com.example.online.exam.service.ExamMonitoringService.RecordResult;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:monitoring-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
class ExamMonitoringControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private ExamMonitoringViolationRepository violationRepository;

    @Autowired
    private ExamMonitoringAttemptRepository attemptRepository;

    @Autowired
    private ExamResultRepository examResultRepository;

    @Autowired
    private ExamMonitoringService monitoringService;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    private MockMvc mockMvc;
    private Long firstStudentId;
    private Long secondStudentId;

    @BeforeEach
    void setUp() {
        violationRepository.deleteAll();
        attemptRepository.deleteAll();
        examResultRepository.deleteAll();
        studentRepository.deleteAll();
        firstStudentId = studentRepository.save(
                new Student("First Student", "first@example.test", "hashed"))
                .getId();
        secondStudentId = studentRepository.save(
                new Student("Second Student", "second@example.test", "hashed"))
                .getId();
        mockMvc = webAppContextSetup(webApplicationContext)
                .addFilters((Filter) springSecurityFilterChain)
                .build();
    }

    @Test
    void validAuthenticatedRequestUsesServerTimestampAndIgnoresClientIdentity()
            throws Exception {
        TestAttempt attempt = activeAttempt(firstStudentId, "python", 300);
        String oldTimestamp = "2000-01-01T00:00:00Z";

        mockMvc.perform(postViolation(
                        attempt,
                        csrfToken(attempt.session()),
                        UUID.randomUUID().toString(),
                        "TAB_SWITCH",
                        99,
                        oldTimestamp))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warningNumber").value(1))
                .andExpect(jsonPath("$.warningCount").value(1))
                .andExpect(jsonPath("$.duplicate").value(false));

        ExamMonitoringViolation saved = violationRepository.findAll().get(0);
        assertNotEquals(Instant.parse(oldTimestamp), saved.getOccurredAt());
        assertTrue(saved.getOccurredAt().isAfter(Instant.parse(oldTimestamp)));
        assertEquals(firstStudentId, saved.getStudentId());
        assertEquals("python", saved.getExamIdentifier());
        assertEquals(1, saved.getWarningNumber());
    }

    @Test
    void serverDeterminesWarningSequenceCapsAtFiveAndIgnoresForgedNumbers()
            throws Exception {
        TestAttempt attempt = activeAttempt(firstStudentId, "java", 300);
        String csrf = csrfToken(attempt.session());

        for (int warning = 1; warning <= 5; warning++) {
            mockMvc.perform(postViolation(
                            attempt,
                            csrf,
                            UUID.randomUUID().toString(),
                            "FOCUS_LOSS",
                            6 - warning,
                            null))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.warningNumber").value(warning))
                    .andExpect(jsonPath("$.warningCount").value(warning))
                    .andExpect(jsonPath("$.disqualified")
                            .value(warning == 5));
        }

        mockMvc.perform(postViolation(
                        attempt,
                        csrf,
                        UUID.randomUUID().toString(),
                        "COPY",
                        1,
                        null))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.warningCount").value(5))
                .andExpect(jsonPath("$.attemptStatus")
                        .value("DISQUALIFIED"));

        assertEquals(5, violationRepository
                .countByStudentIdAndAttemptId(
                        firstStudentId,
                        attempt.attemptId()));
        ExamMonitoringAttempt disqualified = attemptRepository
                .findById(attempt.attemptId())
                .orElseThrow();
        assertEquals(ExamMonitoringAttempt.Status.DISQUALIFIED,
                disqualified.getStatus());
        assertEquals(5, disqualified.getWarningCount());
        assertTrue(disqualified.getDisqualifiedAt() != null);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "TAB_SWITCH",
        "FOCUS_LOSS",
        "FULLSCREEN_EXIT",
        "COPY",
        "CUT",
        "PASTE"
    })
    void acceptsSupportedObservationTypes(String type) throws Exception {
        TestAttempt attempt = activeAttempt(firstStudentId, "sql", 300);
        mockMvc.perform(postViolation(
                        attempt,
                        csrfToken(attempt.session()),
                        UUID.randomUUID().toString(),
                        type,
                        77,
                        null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warningNumber").value(1));
    }

    @Test
    void rejectsUnsupportedViolationType() throws Exception {
        TestAttempt attempt = activeAttempt(firstStudentId, "python", 300);
        mockMvc.perform(postViolation(
                        attempt,
                        csrfToken(attempt.session()),
                        UUID.randomUUID().toString(),
                        "SCREENSHOT",
                        1,
                        null))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMalformedBodyAndNoncanonicalAttemptUuid()
            throws Exception {
        TestAttempt attempt = activeAttempt(firstStudentId, "java", 300);
        String csrf = csrfToken(attempt.session());

        mockMvc.perform(post("/api/exam-monitoring/violations")
                        .session(attempt.session())
                        .header("X-CSRF-TOKEN", csrf)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/exam-monitoring/violations")
                        .session(attempt.session())
                        .header("X-CSRF-TOKEN", csrf)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(violationBody(
                                "1-1-1-1-1",
                                UUID.randomUUID().toString(),
                                "COPY",
                                1,
                                null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void csrfRejectsMissingAndInvalidTokensAndAcceptsValidToken()
            throws Exception {
        TestAttempt attempt = activeAttempt(firstStudentId, "java", 300);
        String body = violationBody(
                attempt.attemptId(),
                UUID.randomUUID().toString(),
                "COPY",
                1,
                null);

        mockMvc.perform(post("/api/exam-monitoring/violations")
                        .session(attempt.session())
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/exam-monitoring/violations")
                        .session(attempt.session())
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("X-CSRF-TOKEN", "invalid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(postViolation(
                        attempt,
                        csrfToken(attempt.session()),
                        UUID.randomUUID().toString(),
                        "COPY",
                        1,
                        null))
                .andExpect(status().isCreated());
    }

    @Test
    void rejectsUnauthenticatedRequestEvenWithValidCsrfToken()
            throws Exception {
        MockHttpSession session = new MockHttpSession();
        String csrf = csrfToken(session);
        TestAttempt attempt = activeAttempt(firstStudentId, "java", 300);

        mockMvc.perform(post("/api/exam-monitoring/violations")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(violationBody(
                                attempt.attemptId(),
                                UUID.randomUUID().toString(),
                                "COPY",
                                1,
                                null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsCrossOriginRequestEvenWhenCsrfTokenIsValid()
            throws Exception {
        TestAttempt attempt = activeAttempt(firstStudentId, "java", 300);
        mockMvc.perform(postViolation(
                        attempt,
                        csrfToken(attempt.session()),
                        UUID.randomUUID().toString(),
                        "COPY",
                        1,
                        null)
                        .header("Origin", "https://attacker.example"))
                .andExpect(status().isForbidden());
    }

    @Test
    void exactAttemptIdValidationRejectsMismatchedAttemptsButKeepsValidAttemptUsable()
            throws Exception {
        TestAttempt validAttempt = activeAttempt(firstStudentId, "python", 300);
        String csrf = csrfToken(validAttempt.session());
        MockHttpSession otherSession = newSession(firstStudentId);
        String sameStudentSameExamAttemptId = ExamAttemptSession.start(
                otherSession,
                "python-legacy",
                firstStudentId,
                "python",
                "python",
                LocalDateTime.now(),
                300);
        monitoringService.registerAttempt(
                ExamAttemptSession.get(
                        otherSession,
                        sameStudentSameExamAttemptId));

        mockMvc.perform(postViolation(
                        validAttempt,
                        csrf,
                        UUID.randomUUID().toString(),
                        "COPY",
                        1,
                        null,
                        sameStudentSameExamAttemptId))
                .andExpect(status().isNotFound());

        mockMvc.perform(postViolation(
                        validAttempt,
                        csrf,
                        UUID.randomUUID().toString(),
                        "TAB_SWITCH",
                        1,
                        null,
                        UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());

        TestAttempt otherStudentAttempt =
                activeAttempt(secondStudentId, "python", 300);
        mockMvc.perform(postViolation(
                        validAttempt,
                        csrf,
                        UUID.randomUUID().toString(),
                        "PASTE",
                        1,
                        null,
                        otherStudentAttempt.attemptId()))
                .andExpect(status().isNotFound());

        String validEventId = UUID.randomUUID().toString();
        mockMvc.perform(postViolation(
                        validAttempt,
                        csrf,
                        validEventId,
                        "PASTE",
                        1,
                        null,
                        validAttempt.attemptId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warningNumber").value(1))
                .andExpect(jsonPath("$.warningCount").value(1));

        mockMvc.perform(postViolation(
                        validAttempt,
                        csrf,
                        validEventId,
                        "PASTE",
                        1,
                        null,
                        validAttempt.attemptId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.warningCount").value(1));

        mockMvc.perform(postViolation(
                        validAttempt,
                        csrf,
                        UUID.randomUUID().toString(),
                        "TAB_SWITCH",
                        1,
                        null,
                        validAttempt.attemptId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warningNumber").value(2))
                .andExpect(jsonPath("$.warningCount").value(2));

        assertEquals(2, violationRepository
                .countByStudentIdAndAttemptId(
                        firstStudentId,
                        validAttempt.attemptId()));
    }

    @Test
    void rejectsUnknownOtherStudentSubmittedAndExpiredAttempts()
            throws Exception {
        TestAttempt ownerAttempt =
                activeAttempt(firstStudentId, "java", 300);
        TestAttempt otherAttempt =
                activeAttempt(secondStudentId, "java", 300);
        String csrf = csrfToken(ownerAttempt.session());

        mockMvc.perform(postViolation(
                        ownerAttempt,
                        csrf,
                        UUID.randomUUID().toString(),
                        "PASTE",
                        1,
                        null,
                        otherAttempt.attemptId()))
                .andExpect(status().isNotFound());

        mockMvc.perform(postViolation(
                        ownerAttempt,
                        csrf,
                        UUID.randomUUID().toString(),
                        "PASTE",
                        1,
                        null,
                        UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());

        ExamAttemptSession.finish(
                ownerAttempt.session(),
                "java",
                ownerAttempt.attemptId(),
                AttemptStatus.SUBMITTED);
        mockMvc.perform(postViolation(
                        ownerAttempt,
                        csrf,
                        UUID.randomUUID().toString(),
                        "PASTE",
                        1,
                        null))
                .andExpect(status().isGone());

        TestAttempt expired = activeAttempt(
                firstStudentId,
                "expired",
                LocalDateTime.now().minusMinutes(1),
                30);
        mockMvc.perform(postViolation(
                        expired,
                        csrfToken(expired.session()),
                        UUID.randomUUID().toString(),
                        "PASTE",
                        1,
                        null))
                .andExpect(status().isGone());
    }

    @Test
    void examTypesHaveIsolatedMonitoringStateAndCompletion()
            throws Exception {
        TestAttempt javaAttempt =
                activeAttempt(firstStudentId, "java", 300);
        String scheduledAttemptId = ExamAttemptSession.start(
                javaAttempt.session(),
                "scheduled",
                firstStudentId,
                "scheduled-exam:14",
                "Java",
                LocalDateTime.now(),
                300);
        monitoringService.registerAttempt(
                ExamAttemptSession.get(
                        javaAttempt.session(),
                        scheduledAttemptId));
        String subjectAttemptId = ExamAttemptSession.start(
                javaAttempt.session(),
                "subject",
                firstStudentId,
                "python",
                "python",
                LocalDateTime.now(),
                300);
        monitoringService.registerAttempt(
                ExamAttemptSession.get(
                        javaAttempt.session(),
                        subjectAttemptId));

        ExamAttemptSession.finish(
                javaAttempt.session(),
                "java",
                javaAttempt.attemptId(),
                AttemptStatus.SUBMITTED);

        assertEquals(
                AttemptStatus.ACTIVE,
                ExamAttemptSession.get(
                        javaAttempt.session(),
                        scheduledAttemptId).status());
        assertEquals(
                AttemptStatus.ACTIVE,
                ExamAttemptSession.get(
                        javaAttempt.session(),
                        subjectAttemptId).status());

        TestAttempt scheduled = new TestAttempt(
                javaAttempt.session(),
                scheduledAttemptId);
        mockMvc.perform(postViolation(
                        scheduled,
                        csrfToken(scheduled.session()),
                        UUID.randomUUID().toString(),
                        "TAB_SWITCH",
                        5,
                        null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attemptId")
                        .value(scheduledAttemptId));

        ExamAttemptSession.finish(
                javaAttempt.session(),
                "scheduled",
                scheduledAttemptId,
                AttemptStatus.SUBMITTED);
        assertEquals(
                AttemptStatus.ACTIVE,
                ExamAttemptSession.get(
                        javaAttempt.session(),
                        subjectAttemptId).status());
    }

    @Test
    void deadlineIsExclusiveAndSubmissionGraceDoesNotChangeMonitoringBoundary()
            throws Exception {
        TestAttempt attempt = activeAttempt(firstStudentId, "java", 300);
        ExamAttemptSession.ActiveAttempt record =
                ExamAttemptSession.get(attempt.session(), attempt.attemptId());
        assertTrue(ExamAttemptSession.isAcceptingMonitoring(
                record,
                firstStudentId,
                record.deadline().minusNanos(1)));
        org.junit.jupiter.api.Assertions.assertFalse(
                ExamAttemptSession.isAcceptingMonitoring(
                        record,
                        firstStudentId,
                        record.deadline()));
        org.junit.jupiter.api.Assertions.assertFalse(
                ExamAttemptSession.isAcceptingMonitoring(
                        record,
                        firstStudentId,
                        record.deadline().plusSeconds(30)));
    }

    @Test
    void duplicateRetriesAreIdempotentAndConflictingReuseIsRejected()
            throws Exception {
        TestAttempt attempt = activeAttempt(firstStudentId, "python", 300);
        String csrf = csrfToken(attempt.session());
        String eventId = UUID.randomUUID().toString();

        mockMvc.perform(postViolation(
                        attempt, csrf, eventId, "PASTE", 5, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warningNumber").value(1));

        mockMvc.perform(postViolation(
                        attempt, csrf, eventId, "PASTE", 1, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.warningCount").value(1));

        mockMvc.perform(postViolation(
                        attempt, csrf, eventId, "CUT", 1, null))
                .andExpect(status().isConflict());
        assertEquals(1, violationRepository
                .countByStudentIdAndAttemptId(
                        firstStudentId,
                        attempt.attemptId()));
    }

    @Test
    void concurrentDuplicateRequestsCreateOneRecordAndOneWarning()
            throws Exception {
        TestAttempt attempt = activeAttempt(firstStudentId, "java", 300);
        String csrf = csrfToken(attempt.session());
        String eventId = UUID.randomUUID().toString();
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            List<Future<MvcResult>> requests = new ArrayList<>();
            for (int index = 0; index < 6; index++) {
                requests.add(executor.submit(() -> mockMvc.perform(
                                postViolation(
                                        attempt,
                                        csrf,
                                        eventId,
                                        "FOCUS_LOSS",
                                        1,
                                        null))
                        .andReturn()));
            }

            int acceptedResponses = 0;
            for (Future<MvcResult> request : requests) {
                int statusCode = request.get().getResponse().getStatus();
                assertTrue(statusCode == 201 || statusCode == 200);
                if (statusCode == 201) {
                    acceptedResponses++;
                }
            }
            assertEquals(1, acceptedResponses);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, violationRepository
                .countByStudentIdAndAttemptId(
                        firstStudentId,
                        attempt.attemptId()));
    }

    @Test
    void simultaneousUniqueEventsReceiveSequentialServerWarnings()
            throws Exception {
        TestAttempt attempt = activeAttempt(firstStudentId, "java", 300);
        String csrf = csrfToken(attempt.session());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> mockMvc.perform(
                            postViolation(
                                    attempt,
                                    csrf,
                                    UUID.randomUUID().toString(),
                                    "TAB_SWITCH",
                                    5,
                                    null))
                    .andReturn());
            Future<MvcResult> second = executor.submit(() -> mockMvc.perform(
                            postViolation(
                                    attempt,
                                    csrf,
                                    UUID.randomUUID().toString(),
                                    "COPY",
                                    1,
                                    null))
                    .andReturn());
            List<Integer> warningNumbers = List.of(
                    warningNumber(first.get()),
                    warningNumber(second.get()));
            assertEquals(List.of(1, 2), warningNumbers.stream().sorted().toList());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(2, violationRepository
                .countByStudentIdAndAttemptId(
                        firstStudentId,
                        attempt.attemptId()));
    }

    @Test
    void databaseRowLockPreventsConcurrentWarningsFromExceedingFive()
            throws Exception {
        TestAttempt attempt = activeAttempt(firstStudentId, "java", 300);
        ExamAttemptSession.ActiveAttempt active =
                ExamAttemptSession.get(
                        attempt.session(),
                        attempt.attemptId());
        for (int warning = 0; warning < 4; warning++) {
            assertEquals(
                    Outcome.ACCEPTED,
                    monitoringService.recordViolation(
                            firstStudentId,
                            active,
                            UUID.randomUUID().toString(),
                            "FOCUS_LOSS").outcome());
        }

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<RecordResult>> requests = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                requests.add(executor.submit(() ->
                        monitoringService.recordViolation(
                                firstStudentId,
                                active,
                                UUID.randomUUID().toString(),
                                "COPY")));
            }

            int accepted = 0;
            for (Future<RecordResult> request : requests) {
                Outcome outcome = request.get().outcome();
                assertTrue(
                        outcome == Outcome.ACCEPTED
                                || outcome == Outcome.DISQUALIFIED);
                if (outcome == Outcome.ACCEPTED) {
                    accepted++;
                }
            }
            assertEquals(1, accepted);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(5, violationRepository
                .countByStudentIdAndAttemptId(
                        firstStudentId,
                        attempt.attemptId()));
        ExamMonitoringAttempt persisted = attemptRepository
                .findById(attempt.attemptId())
                .orElseThrow();
        assertEquals(5, persisted.getWarningCount());
        assertEquals(
                ExamMonitoringAttempt.Status.DISQUALIFIED,
                persisted.getStatus());
        assertTrue(persisted.getDisqualifiedAt() != null);
    }

    @Test
    void disqualifiedJavaAttemptCannotCreateResultOrBeReactivated()
            throws Exception {
        MockHttpSession session = newSession(firstStudentId);
        session.setAttribute(
                "activeJavaQuestions",
                List.of(new QuizController.Question(
                        "Question",
                        new String[]{"A", "B", "C", "D"},
                        0)));
        LocalDateTime startedAt = LocalDateTime.now();
        session.setAttribute("examStartTime", startedAt);
        session.setAttribute("examDurationSeconds", 600L);
        String subjectAttemptId = ExamAttemptSession.start(
                session,
                "subject",
                firstStudentId,
                "python",
                "python",
                startedAt,
                600);
        monitoringService.registerAttempt(
                ExamAttemptSession.get(session, subjectAttemptId));
        String scheduledAttemptId = ExamAttemptSession.start(
                session,
                "scheduled",
                firstStudentId,
                "scheduled-exam:22",
                "Java",
                startedAt,
                600);
        monitoringService.registerAttempt(
                ExamAttemptSession.get(session, scheduledAttemptId));
        String attemptId = ExamAttemptSession.start(
                session,
                "java",
                firstStudentId,
                "java",
                "java",
                startedAt,
                600);
        monitoringService.registerAttempt(
                ExamAttemptSession.get(session, attemptId));
        TestAttempt attempt = new TestAttempt(session, attemptId);
        String csrf = csrfToken(session);

        for (int warning = 1; warning <= 5; warning++) {
            mockMvc.perform(postViolation(
                            attempt,
                            csrf,
                            UUID.randomUUID().toString(),
                            "TAB_SWITCH",
                            warning,
                            null))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("value=\"" + attemptId + "\"")));

        mockMvc.perform(post("/submit")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf)
                        .param("attemptId", attemptId)
                        .param("q0", "0"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .view().name("exam-disqualified"));

        assertTrue(examResultRepository
                .findByStudentIdOrderByExamDateDesc(firstStudentId)
                .isEmpty());
        assertEquals(
                AttemptStatus.DISQUALIFIED,
                ExamAttemptSession.getForSlot(session, "java").status());
        assertEquals(
                AttemptStatus.ACTIVE,
                ExamAttemptSession.get(session, subjectAttemptId).status());
        assertEquals(
                AttemptStatus.ACTIVE,
                ExamAttemptSession.get(session, scheduledAttemptId).status());
        org.junit.jupiter.api.Assertions.assertNull(
                ExamAttemptSession.ensure(
                        session,
                        "java",
                        firstStudentId,
                        "java",
                        "java",
                        startedAt,
                        600));
        assertEquals(
                "DISQUALIFIED",
                attemptRepository.findById(attemptId).orElseThrow()
                        .getStatus().name());
    }

    @Test
    void studentCannotDisqualifyAnotherSessionsAttempt()
            throws Exception {
        TestAttempt ownerAttempt =
                activeAttempt(firstStudentId, "java", 300);
        MockHttpSession otherSession = newSession(secondStudentId);

        mockMvc.perform(post("/api/exam-monitoring/violations")
                        .session(otherSession)
                        .header("X-CSRF-TOKEN", csrfToken(otherSession))
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(violationBody(
                                ownerAttempt.attemptId(),
                                UUID.randomUUID().toString(),
                                "TAB_SWITCH",
                                5,
                                null)))
                .andExpect(status().isNotFound());

        assertEquals(0, violationRepository
                .countByStudentIdAndAttemptId(
                        firstStudentId,
                        ownerAttempt.attemptId()));
        assertEquals(
                ExamMonitoringAttempt.Status.ACTIVE,
                attemptRepository.findById(ownerAttempt.attemptId())
                        .orElseThrow().getStatus());
    }

    @Test
    void disqualifiedAttemptHistoryIsVisibleOnlyToItsOwnerAndAdmin()
            throws Exception {
        TestAttempt attempt = activeAttempt(firstStudentId, "sql", 300);
        for (int warning = 1; warning <= 5; warning++) {
            mockMvc.perform(postViolation(
                            attempt,
                            csrfToken(attempt.session()),
                            UUID.randomUUID().toString(),
                            "CUT",
                            warning,
                            null))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get(
                        "/api/exam-monitoring/violations/{attemptId}",
                        attempt.attemptId())
                        .session(newSession(secondStudentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disqualified").value(false))
                .andExpect(jsonPath("$.warningCount").value(0));

        mockMvc.perform(get("/admin/api/exam-monitoring/disqualified")
                        .session(newSession(secondStudentId)))
                .andExpect(status().isForbidden());

        MockHttpSession adminSession = new MockHttpSession();
        adminSession.setAttribute("quizzyAdmin", true);
        mockMvc.perform(get("/admin/api/exam-monitoring/disqualified")
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attemptId")
                        .value(attempt.attemptId()))
                .andExpect(jsonPath("$[0].studentId")
                        .value(firstStudentId))
                .andExpect(jsonPath("$[0].warningCount").value(5))
                .andExpect(jsonPath("$[0].status")
                        .value("DISQUALIFIED"));
    }

    @Test
    void historyIsScopedToAuthenticatedStudent() throws Exception {
        TestAttempt ownerAttempt =
                activeAttempt(firstStudentId, "python", 300);
        mockMvc.perform(postViolation(
                        ownerAttempt,
                        csrfToken(ownerAttempt.session()),
                        UUID.randomUUID().toString(),
                        "CUT",
                        1,
                        null))
                .andExpect(status().isCreated());

        mockMvc.perform(get(
                        "/api/exam-monitoring/violations/{attemptId}",
                        ownerAttempt.attemptId())
                        .session(newSession(secondStudentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warningCount").value(0))
                .andExpect(jsonPath("$.violations").isEmpty());

        mockMvc.perform(get(
                        "/api/exam-monitoring/violations/{attemptId}",
                        ownerAttempt.attemptId())
                        .session(newSession(firstStudentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warningCount").value(1))
                .andExpect(jsonPath("$.violations[0].violationType")
                        .value("CUT"));
    }

    @Test
    void monitoringHistoryRequiresAuthentication() throws Exception {
        mockMvc.perform(get(
                        "/api/exam-monitoring/violations/{attemptId}",
                        UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
    }

    private int warningNumber(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        Matcher matcher = Pattern.compile(
                "\"warningNumber\"\\s*:\\s*(\\d+)").matcher(content);
        assertTrue(matcher.find(), content);
        return Integer.parseInt(matcher.group(1));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            postViolation(
                    TestAttempt attempt,
                    String csrf,
                    String eventId,
                    String type,
                    int forgedWarningNumber,
                    String timestamp) {
        return postViolation(
                attempt,
                csrf,
                eventId,
                type,
                forgedWarningNumber,
                timestamp,
                attempt.attemptId());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            postViolation(
                    TestAttempt attempt,
                    String csrf,
                    String eventId,
                    String type,
                    int forgedWarningNumber,
                    String timestamp,
                    String submittedAttemptId) {
        return post("/api/exam-monitoring/violations")
                .session(attempt.session())
                .header("X-CSRF-TOKEN", csrf)
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content(violationBody(
                        submittedAttemptId,
                        eventId,
                        type,
                        forgedWarningNumber,
                        timestamp));
    }

    private String violationBody(
            String attemptId,
            String eventId,
            String type,
            int forgedWarningNumber,
            String timestamp) {
        String timestampProperty = timestamp == null
                ? ""
                : ",\"occurredAt\":\"" + timestamp + "\"";
        return """
                {
                  "studentId": 999999,
                  "attemptId": "%s",
                  "clientEventId": "%s",
                  "violationType": "%s",
                  "warningNumber": %d%s
                }
                """.formatted(
                attemptId,
                eventId,
                type,
                forgedWarningNumber,
                timestampProperty);
    }

    private String csrfToken(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/login").session(session))
                .andReturn();
        String html = result.getResponse().getContentAsString();
        Matcher matcher = Pattern.compile(
                "name=\"_csrf\"\\s+value=\"([^\"]+)\"")
                .matcher(html);
        assertTrue(matcher.find(), "CSRF token was not rendered: " + html);
        return matcher.group(1);
    }

    private TestAttempt activeAttempt(
            Long studentId,
            String identifier,
            long durationSeconds) {
        return activeAttempt(
                studentId,
                identifier,
                LocalDateTime.now(),
                durationSeconds);
    }

    private TestAttempt activeAttempt(
            Long studentId,
            String identifier,
            LocalDateTime start,
            long durationSeconds) {
        MockHttpSession session = newSession(studentId);
        String attemptId = ExamAttemptSession.start(
                session,
                identifier,
                studentId,
                identifier,
                identifier,
                start,
                durationSeconds);
        monitoringService.registerAttempt(
                ExamAttemptSession.get(session, attemptId));
        return new TestAttempt(session, attemptId);
    }

    private MockHttpSession newSession(Long studentId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("studentId", studentId);
        return session;
    }

    private record TestAttempt(
            MockHttpSession session,
            String attemptId) {
    }
}
