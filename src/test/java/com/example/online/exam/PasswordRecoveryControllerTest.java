package com.example.online.exam;

import com.example.online.exam.entity.Student;
import com.example.online.exam.repository.StudentRepository;
import com.example.online.exam.service.EmailDeliveryException;
import com.example.online.exam.service.EmailMessage;
import com.example.online.exam.service.EmailService;
import com.example.online.exam.service.PasswordRecoveryService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:quizzy-recovery-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "quizzy.app.base-url=https://quizzy.example"
})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class PasswordRecoveryControllerTest {

    private static final String GENERIC_RESPONSE =
            "If an account with that email exists, recovery instructions will be sent.";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private PasswordRecoveryService recoveryService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private EmailService emailService;

    private MockMvc mockMvc;
    private Student student;

    @BeforeEach
    void setUp() {
        student = studentRepository.saveAndFlush(new Student(
                "Recovery Student",
                "recovery-" + UUID.randomUUID() + "@example.test",
                passwordEncoder.encode("old-password")));
        reset(emailService);
        mockMvc = webAppContextSetup(webApplicationContext)
                .addFilters((Filter) springSecurityFilterChain)
                .build();
    }

    @Test
    void forgotPasswordPageLoadsAndLoginLinksToIt() throws Exception {
        mockMvc.perform(get("/forgot-password"))
                .andExpect(status().isOk())
                .andExpect(view().name("forgot-password"));
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/forgot-password")));
    }

    @Test
    void registeredAndUnregisteredRequestsHaveSameSafeResponse()
            throws Exception {
        MockHttpSession session = new MockHttpSession();
        String csrf = csrfFrom("/forgot-password", session);
        String registeredRequestId = UUID.randomUUID().toString();
        String registeredHtml = mockMvc.perform(post("/forgot-password")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf)
                        .param("requestId", registeredRequestId)
                        .param("email", student.getEmail()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        GENERIC_RESPONSE)))
                .andReturn().getResponse().getContentAsString();
        String unregisteredHtml = mockMvc.perform(post("/forgot-password")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf)
                        .param("requestId", UUID.randomUUID().toString())
                        .param("email", "unknown-" + UUID.randomUUID()
                                + "@example.test"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        GENERIC_RESPONSE)))
                .andReturn().getResponse().getContentAsString();
        assertEquals(
                extractSuccessText(registeredHtml),
                extractSuccessText(unregisteredHtml));
        verify(emailService).send(any(EmailMessage.class));
    }

    @Test
    void validTokenResetsPasswordOnceAndOnlyHashIsPersisted()
            throws Exception {
        RecoveryRequest request = requestRecovery();
        assertNotEquals(
                request.token(),
                studentRepository.findById(student.getId()).orElseThrow()
                        .getPasswordRecoveryTokenHash());
        assertEquals(
                PasswordRecoveryService.hashToken(request.token()),
                studentRepository.findById(student.getId()).orElseThrow()
                        .getPasswordRecoveryTokenHash());

        mockMvc.perform(get("/reset-password").param("token", request.token()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Cache-Control", "no-store"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Referrer-Policy", "no-referrer"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Choose a new password")));
        resetPageSession = new MockHttpSession();
        String csrf = csrfFrom(
                "/reset-password?token=" + request.token(), resetPageSession);
        mockMvc.perform(post("/reset-password")
                        .session(resetPageSession)
                        .header("X-CSRF-TOKEN", csrf)
                        .param("token", request.token())
                        .param("password", "new-strong-password")
                        .param("confirmPassword", "new-strong-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?passwordReset"));

        Student updated = studentRepository.findById(student.getId()).orElseThrow();
        assertNotEquals("old-password", updated.getPassword());
        assertTrue(passwordEncoder.matches(
                "new-strong-password", updated.getPassword()));
        assertFalse(passwordEncoder.matches(
                "old-password", updated.getPassword()));
        assertEquals(null, updated.getPasswordRecoveryTokenHash());
        assertFalse(recoveryService.resetPassword(
                request.token(), "another-password", "another-password"));

        MockHttpSession loginSession = new MockHttpSession();
        String loginCsrf = csrfFrom("/login", loginSession);
        mockMvc.perform(post("/login")
                        .session(loginSession)
                        .header("X-CSRF-TOKEN", loginCsrf)
                        .param("email", student.getEmail())
                        .param("password", "old-password"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Invalid email or password.")));
        mockMvc.perform(post("/login")
                        .session(loginSession)
                        .header("X-CSRF-TOKEN", loginCsrf)
                        .param("email", student.getEmail())
                        .param("password", "new-strong-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard"));
    }

    private MockHttpSession resetPageSession;

    @Test
    void unknownExpiredAndReusedTokensAreRejected() throws Exception {
        mockMvc.perform(get("/reset-password").param("token", "unknown-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "cannot be used")));
        assertFalse(recoveryService.resetPassword(
                "unknown-token", "new-strong-password", "new-strong-password"));

        String expired = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[32]);
        student.setPasswordRecoveryTokenHash(
                PasswordRecoveryService.hashToken(expired));
        student.setPasswordRecoveryExpiresAt(LocalDateTime.now().minusMinutes(1));
        studentRepository.saveAndFlush(student);
        mockMvc.perform(get("/reset-password").param("token", expired))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "cannot be used")));
        assertFalse(recoveryService.resetPassword(
                expired, "new-strong-password", "new-strong-password"));
    }

    @Test
    void invalidPasswordAndConfirmationDoNotConsumeToken() throws Exception {
        RecoveryRequest request = requestRecovery();
        resetPageSession = new MockHttpSession();
        String csrf = csrfFrom(
                "/reset-password?token=" + request.token(), resetPageSession);

        mockMvc.perform(post("/reset-password")
                        .session(resetPageSession)
                        .header("X-CSRF-TOKEN", csrf)
                        .param("token", request.token())
                        .param("password", "short")
                        .param("confirmPassword", "short"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "8 to 72 characters")));
        mockMvc.perform(post("/reset-password")
                        .session(resetPageSession)
                        .header("X-CSRF-TOKEN", csrf)
                        .param("token", request.token())
                        .param("password", "new-strong-password")
                        .param("confirmPassword", "not-the-same"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "confirmation does not match")));
        assertTrue(recoveryService.isTokenUsable(request.token()));
    }

    @Test
    void recoveryRequestsRequireCsrfAndRateLimitEmailResends()
            throws Exception {
        mockMvc.perform(post("/forgot-password")
                        .param("email", student.getEmail()))
                .andExpect(status().isForbidden());

        MockHttpSession session = new MockHttpSession();
        String csrf = csrfFrom("/forgot-password", session);
        String firstRequestId = UUID.randomUUID().toString();
        for (int request = 0;
             request < PasswordRecoveryService.MAX_REQUESTS_PER_WINDOW;
             request++) {
            String requestId = request == 0
                    ? firstRequestId
                    : UUID.randomUUID().toString();
            mockMvc.perform(post("/forgot-password")
                            .session(session)
                            .header("X-CSRF-TOKEN", csrf)
                            .param("requestId", requestId)
                            .param("email", student.getEmail()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(
                            GENERIC_RESPONSE)));
            if (request == 0) {
                mockMvc.perform(post("/forgot-password")
                                .session(session)
                                .header("X-CSRF-TOKEN", csrf)
                                .param("requestId", firstRequestId)
                                .param("email", student.getEmail()))
                        .andExpect(status().isOk())
                        .andExpect(content().string(
                                org.hamcrest.Matchers.containsString(
                                        GENERIC_RESPONSE)));
            }
        }
        mockMvc.perform(post("/forgot-password")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf)
                        .param("requestId", UUID.randomUUID().toString())
                        .param("email", student.getEmail()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        GENERIC_RESPONSE)));
        verify(emailService, org.mockito.Mockito.times(
                PasswordRecoveryService.MAX_REQUESTS_PER_WINDOW))
                .send(any(EmailMessage.class));
    }

    @Test
    void emailFailureIsNonEnumeratingAndDoesNotExposeTokenInLogs(
            CapturedOutput output) throws Exception {
        doThrow(new EmailDeliveryException("delivery unavailable"))
                .when(emailService).send(any(EmailMessage.class));
        MockHttpSession session = new MockHttpSession();
        String csrf = csrfFrom("/forgot-password", session);
        String response = mockMvc.perform(post("/forgot-password")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf)
                        .param("requestId", UUID.randomUUID().toString())
                        .param("email", student.getEmail()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        GENERIC_RESPONSE)))
                .andReturn().getResponse().getContentAsString();
        String storedHash = studentRepository.findById(student.getId())
                .orElseThrow().getPasswordRecoveryTokenHash();
        assertTrue(storedHash.matches("[0-9a-f]{64}"));
        assertFalse(response.contains(storedHash));
        assertFalse(output.getAll().contains(storedHash));
        org.mockito.ArgumentCaptor<EmailMessage> message =
                org.mockito.ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailService).send(message.capture());
        Matcher tokenMatcher = Pattern.compile(
                        "https://quizzy\\.example/reset-password\\?token=([A-Za-z0-9_-]+)")
                .matcher(message.getValue().textBody());
        assertTrue(tokenMatcher.find());
        assertFalse(output.getAll().contains(tokenMatcher.group(1)));
        assertTrue(message.getValue().textBody().contains(
                "If you did not request a password reset"));
    }

    @Test
    void invalidCsrfTokenIsRejected() throws Exception {
        mockMvc.perform(post("/forgot-password")
                        .session(new MockHttpSession())
                        .header("X-CSRF-TOKEN", "invalid-token")
                        .param("email", student.getEmail()))
                .andExpect(status().isForbidden());
    }

    private RecoveryRequest requestRecovery() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String csrf = csrfFrom("/forgot-password", session);
        mockMvc.perform(post("/forgot-password")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf)
                        .param("requestId", UUID.randomUUID().toString())
                        .param("email", student.getEmail()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        GENERIC_RESPONSE)));
        resetPageSession = new MockHttpSession();
        org.mockito.ArgumentCaptor<EmailMessage> message =
                org.mockito.ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailService).send(message.capture());
        Matcher matcher = Pattern.compile(
                        "https://quizzy\\.example/reset-password\\?token=([A-Za-z0-9_-]+)")
                .matcher(message.getValue().textBody());
        assertTrue(matcher.find());
        assertTrue(message.getValue().textBody().contains(
                "This link expires at"));
        LocalDateTime expiresAt = studentRepository.findById(student.getId())
                .orElseThrow().getPasswordRecoveryExpiresAt();
        return new RecoveryRequest(matcher.group(1), expiresAt);
    }

    private String csrfFrom(String path, MockHttpSession session)
            throws Exception {
        String html = mockMvc.perform(get(path).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Matcher matcher = Pattern.compile(
                        "name=\"_csrf\"[^>]*value=\"([^\"]+)\"")
                .matcher(html);
        assertTrue(matcher.find(), "Form must render a CSRF token.");
        return matcher.group(1);
    }

    private String extractSuccessText(String html) {
        Matcher matcher = Pattern.compile(
                        "<p class=\"success\"[^>]*>(.*?)</p>",
                        Pattern.DOTALL)
                .matcher(html);
        assertTrue(matcher.find());
        return matcher.group(1).trim();
    }

    private record RecoveryRequest(
            String token,
            LocalDateTime expiresAt) {
    }
}
