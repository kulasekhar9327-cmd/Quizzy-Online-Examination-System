package com.example.online.exam;

import com.example.online.exam.entity.Student;
import com.example.online.exam.entity.NotificationType;
import com.example.online.exam.entity.SupportCategory;
import com.example.online.exam.entity.SupportConversation;
import com.example.online.exam.entity.SupportMessage;
import com.example.online.exam.entity.SupportSender;
import com.example.online.exam.entity.SupportStatus;
import com.example.online.exam.repository.StudentRepository;
import com.example.online.exam.repository.AppNotificationRepository;
import com.example.online.exam.repository.SupportConversationRepository;
import com.example.online.exam.repository.SupportMessageRepository;
import com.example.online.exam.service.SupportService;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:quizzy-support-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
class SupportControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private AppNotificationRepository notificationRepository;

    @Autowired
    private SupportConversationRepository conversationRepository;

    @Autowired
    private SupportMessageRepository messageRepository;

    @Autowired
    private SupportService supportService;

    private MockMvc mockMvc;
    private Student student;
    private Student otherStudent;

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        String suffix = UUID.randomUUID().toString();
        student = studentRepository.saveAndFlush(new Student(
                "Support Student", "student-" + suffix + "@example.test", "hash"));
        otherStudent = studentRepository.saveAndFlush(new Student(
                "Other Student", "other-" + suffix + "@example.test", "hash"));
        mockMvc = webAppContextSetup(webApplicationContext)
                .addFilters((Filter) springSecurityFilterChain)
                .build();
    }

    @Test
    void studentCanCreateAndReadOnlyTheirOwnConversationWithSafeText()
            throws Exception {
        MockHttpSession session = studentSession(student);
        String token = csrfFrom("/support/new", session);
        String scriptText = "<script>alert('x')</script>";

        String location = mockMvc.perform(post("/support")
                        .session(session)
                        .header("X-CSRF-TOKEN", token)
                        .param("category", "EXAM_PROBLEMS")
                        .param("subject", "Exam issue")
                        .param("message", scriptText)
                        .param("sender", "ADMIN")
                        .param("sentAt", "2000-01-01T00:00:00"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        assertNotNull(location);
        Long conversationId = Long.valueOf(location.substring(
                location.lastIndexOf('/') + 1));
        SupportConversation conversation =
                conversationRepository.findById(conversationId).orElseThrow();
        SupportMessage message = messageRepository
                .findByConversation_IdOrderBySentAtAsc(conversationId)
                .getFirst();
        assertEquals(student.getId(), conversation.getStudent().getId());
        assertEquals(SupportStatus.OPEN, conversation.getStatus());
        assertEquals(SupportSender.STUDENT, message.getSenderType());
        assertTrue(message.getSentAt().isAfter(
                LocalDateTime.now().minusMinutes(1)));
        assertTrue(message.isReadByStudent());
        assertFalse(message.isReadByAdmin());

        mockMvc.perform(get(location).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "&lt;script&gt;")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "&lt;/script&gt;")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("<script>"))));
        mockMvc.perform(get(location).session(studentSession(otherStudent)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/support").session(studentSession(otherStudent)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Exam issue"))));
    }

    @Test
    void studentValidationAndUnauthenticatedAccessAreRejected()
            throws Exception {
        mockMvc.perform(get("/support"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        MockHttpSession session = studentSession(student);
        String token = csrfFrom("/support/new", session);
        mockMvc.perform(post("/support")
                        .session(session)
                        .header("X-CSRF-TOKEN", token)
                        .param("category", "NOT_A_CATEGORY")
                        .param("subject", " ")
                        .param("message", "  "))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/support/new"));
        mockMvc.perform(post("/support")
                        .session(session)
                        .param("category", "OTHER")
                        .param("subject", "No CSRF")
                        .param("message", "Request should fail"))
                .andExpect(status().isForbidden());
        assertTrue(conversationRepository.findAll().isEmpty());
    }

    @Test
    void adminReplyUpdatesStatusAndUnreadStateAndStudentReplyReopensWork()
            throws Exception {
        Long conversationId = supportService.createConversation(
                student.getId(), "Timer issue", SupportCategory.TIMER_SUBMISSION,
                "Timer stopped unexpectedly");
        MockHttpSession adminSession = adminSession();

        mockMvc.perform(get("/admin/support")
                        .session(adminSession)
                        .param("status", "OPEN")
                        .param("category", "TIMER_SUBMISSION"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Awaiting admin action")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Unread student message")));

        String adminToken = csrfFrom(
                "/admin/support/" + conversationId, adminSession);
        assertTrue(messageRepository
                .findByConversation_IdOrderBySentAtAsc(conversationId)
                .getFirst().isReadByAdmin());
        mockMvc.perform(post("/admin/support/" + conversationId + "/messages")
                        .session(adminSession)
                        .header("X-CSRF-TOKEN", adminToken)
                        .param("message", "We are looking into this."))
                .andExpect(status().is3xxRedirection());

        SupportConversation conversation =
                conversationRepository.findById(conversationId).orElseThrow();
        List<SupportMessage> afterAdminReply = messageRepository
                .findByConversation_IdOrderBySentAtAsc(conversationId);
        assertEquals(SupportStatus.WAITING_FOR_STUDENT, conversation.getStatus());
        assertEquals(2, afterAdminReply.size());
        assertEquals(SupportSender.ADMIN, afterAdminReply.getLast().getSenderType());
        assertFalse(afterAdminReply.getLast().isReadByStudent());
        assertTrue(afterAdminReply.getLast().isReadByAdmin());
        var supportNotice = notificationRepository
                .findByRecipientTypeAndRecipientIdAndEventKey(
                        "STUDENT",
                        student.getId(),
                        "support-message:" + afterAdminReply.getLast().getId())
                .orElseThrow();
        assertEquals(
                NotificationType.SUPPORT_MESSAGE,
                supportNotice.getNotificationType());
        assertEquals(
                "/support/" + conversationId,
                supportNotice.getTargetUrl());
        assertTrue(conversation.getLastMessageAt().isAfter(
                afterAdminReply.getFirst().getSentAt()));

        MockHttpSession studentSession = studentSession(student);
        mockMvc.perform(get("/support").session(studentSession))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "New admin reply")));
        String studentToken = csrfFrom(
                "/support/" + conversationId, studentSession);
        assertTrue(messageRepository
                .findByConversation_IdOrderBySentAtAsc(conversationId)
                .getLast().isReadByStudent());
        mockMvc.perform(post("/support/" + conversationId + "/messages")
                        .session(studentSession)
                        .header("X-CSRF-TOKEN", studentToken)
                        .param("message", "Thank you for checking."))
                .andExpect(status().is3xxRedirection());

        List<SupportMessage> allMessages = messageRepository
                .findByConversation_IdOrderBySentAtAsc(conversationId);
        assertEquals(3, allMessages.size());
        assertEquals(SupportSender.STUDENT, allMessages.getLast().getSenderType());
        assertEquals(SupportStatus.IN_PROGRESS,
                conversationRepository.findById(conversationId)
                        .orElseThrow().getStatus());
        assertTrue(allMessages.get(0).getSentAt()
                .isBefore(allMessages.get(1).getSentAt()));
        assertTrue(allMessages.get(1).getSentAt()
                .isBefore(allMessages.get(2).getSentAt()));
    }

    @Test
    void adminCanChangeStatusFilterInboxAndClosedConversationRejectsReplies()
            throws Exception {
        Long targetId = supportService.createConversation(
                student.getId(), "Login issue", SupportCategory.ACCOUNT_LOGIN,
                "I cannot sign in");
        supportService.createConversation(
                otherStudent.getId(), "Feedback question", SupportCategory.FEEDBACK,
                "How can I update feedback?");
        MockHttpSession adminSession = adminSession();
        String token = csrfFrom("/admin/support/" + targetId, adminSession);

        mockMvc.perform(post("/admin/support/" + targetId + "/status")
                        .session(adminSession)
                        .header("X-CSRF-TOKEN", token)
                        .param("status", "CLOSED"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/admin/support")
                        .session(adminSession)
                        .param("status", "CLOSED")
                        .param("category", "ACCOUNT_LOGIN"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Login issue")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Feedback question"))));

        MockHttpSession studentSession = studentSession(student);
        String studentToken = csrfFrom("/support/new", studentSession);
        mockMvc.perform(post("/support/" + targetId + "/messages")
                        .session(studentSession)
                        .header("X-CSRF-TOKEN", studentToken)
                        .param("message", "A new detail"))
                .andExpect(status().is3xxRedirection());
        assertEquals(1, messageRepository
                .findByConversation_IdOrderBySentAtAsc(targetId).size());

        mockMvc.perform(get("/admin/support")
                        .session(adminSession)
                        .param("status", "INVALID"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "selected support filter is not valid")));
    }

    @Test
    void adminPagesAndUnknownConversationIdsAreProtected()
            throws Exception {
        mockMvc.perform(get("/admin/support"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
        mockMvc.perform(get("/admin/support")
                        .session(studentSession(student)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
        mockMvc.perform(get("/support/9223372036854775807")
                        .session(studentSession(student)))
                .andExpect(status().isNotFound());
    }

    private String csrfFrom(String path, MockHttpSession session)
            throws Exception {
        String html = mockMvc.perform(get(path).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Matcher matcher = Pattern.compile(
                        "name=\"_csrf\"[^>]*value=\"([^\"]+)\"")
                .matcher(html);
        assertTrue(matcher.find(), "Page must render a CSRF token.");
        return matcher.group(1);
    }

    private MockHttpSession studentSession(Student account) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("studentId", account.getId());
        return session;
    }

    private MockHttpSession adminSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("quizzyAdmin", true);
        return session;
    }
}
