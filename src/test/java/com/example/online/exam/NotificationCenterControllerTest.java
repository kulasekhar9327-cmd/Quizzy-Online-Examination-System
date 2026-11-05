package com.example.online.exam;

import com.example.online.exam.entity.AppNotification;
import com.example.online.exam.entity.AnnouncementDelivery;
import com.example.online.exam.entity.AnnouncementDeliveryStatus;
import com.example.online.exam.entity.NotificationType;
import com.example.online.exam.entity.Student;
import com.example.online.exam.entity.StudentNotificationPreference;
import com.example.online.exam.repository.AnnouncementDeliveryRepository;
import com.example.online.exam.repository.AnnouncementRequestRepository;
import com.example.online.exam.repository.AppNotificationRepository;
import com.example.online.exam.repository.StudentRepository;
import com.example.online.exam.repository.StudentNotificationPreferenceRepository;
import com.example.online.exam.service.EmailDeliveryException;
import com.example.online.exam.service.EmailMessage;
import com.example.online.exam.service.EmailService;
import com.example.online.exam.service.NotificationPreferenceService;
import com.example.online.exam.service.NotificationService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:quizzy-notifications-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
class NotificationCenterControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Autowired
    private AppNotificationRepository notificationRepository;

    @Autowired
    private AnnouncementRequestRepository announcementRequestRepository;

    @Autowired
    private AnnouncementDeliveryRepository announcementDeliveryRepository;

    @Autowired
    private StudentNotificationPreferenceRepository preferenceRepository;

    @Autowired
    private NotificationPreferenceService preferenceService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private EmailService emailService;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private NotificationService notificationService;

    private MockMvc mockMvc;
    private Student student;
    private Student otherStudent;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        announcementDeliveryRepository.deleteAll();
        announcementRequestRepository.deleteAll();
        preferenceRepository.deleteAll();
        reset(emailService);
        String suffix = UUID.randomUUID().toString();
        student = studentRepository.saveAndFlush(new Student(
                "Notice Student", "notice-" + suffix + "@example.test", "hash"));
        otherStudent = studentRepository.saveAndFlush(new Student(
                "Other Notice Student",
                "other-notice-" + suffix + "@example.test",
                "hash"));
        mockMvc = webAppContextSetup(webApplicationContext)
                .addFilters((Filter) springSecurityFilterChain)
                .build();
    }

    @Test
    void studentCanViewOnlyOwnedNotificationsAndUnreadCount()
            throws Exception {
        AppNotification own = notification(student, "own-event", "Own result");
        notification(otherStudent, "other-event", "Private result");

        mockMvc.perform(get("/notifications").session(studentSession(student)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Own result")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Private result"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "1")));

        assertEquals(1, notificationService.unreadCount(student.getId()));
        assertEquals(1, notificationService.unreadCount(otherStudent.getId()));
        assertTrue(notificationRepository.findById(own.getId()).isPresent());
    }

    @Test
    void studentCanToggleOwnedReadStateAndMarkAllRead()
            throws Exception {
        AppNotification first = notification(student, "read-first", "Read first");
        AppNotification second = notification(student, "read-second", "Read second");
        MockHttpSession session = studentSession(student);
        String csrf = csrfFrom("/notifications", session);

        mockMvc.perform(post("/notifications/" + first.getId() + "/read")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/notifications"));
        assertTrue(notificationRepository.findById(first.getId())
                .orElseThrow().isRead());
        assertEquals(1, notificationService.unreadCount(student.getId()));

        mockMvc.perform(post("/notifications/" + first.getId() + "/unread")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf))
                .andExpect(status().is3xxRedirection());
        assertFalse(notificationRepository.findById(first.getId())
                .orElseThrow().isRead());

        mockMvc.perform(post("/notifications/read-all")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf))
                .andExpect(status().is3xxRedirection());
        assertTrue(notificationRepository.findById(first.getId())
                .orElseThrow().isRead());
        assertTrue(notificationRepository.findById(second.getId())
                .orElseThrow().isRead());
        assertEquals(0, notificationService.unreadCount(student.getId()));
    }

    @Test
    void studentCannotChangeAnotherStudentsNotificationAndCsrfIsRequired()
            throws Exception {
        AppNotification privateNotice =
                notification(student, "private-event", "Private notice");
        notification(otherStudent, "other-visible", "Other student's notice");
        MockHttpSession otherSession = studentSession(otherStudent);
        String csrf = csrfFrom("/notifications", otherSession);

        mockMvc.perform(post("/notifications/" + privateNotice.getId() + "/read")
                        .session(otherSession)
                        .header("X-CSRF-TOKEN", csrf))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/notifications/" + privateNotice.getId() + "/unread")
                        .session(otherSession)
                        .header("X-CSRF-TOKEN", csrf))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/notifications/read-all")
                        .session(otherSession))
                .andExpect(status().isForbidden());
        assertFalse(notificationRepository.findById(privateNotice.getId())
                .orElseThrow().isRead());
    }

    @Test
    void adminCanCreateAnnouncementForAllOrOneStudent()
            throws Exception {
        MockHttpSession adminSession = adminSession();
        String adminCsrf = csrfFrom("/admin/announcements", adminSession);
        String requestId = UUID.randomUUID().toString();
        long expectedRecipientCount = studentRepository.count();

        mockMvc.perform(post("/admin/announcements")
                        .session(adminSession)
                        .header("X-CSRF-TOKEN", adminCsrf)
                        .param("requestId", requestId)
                        .param("title", "Exam update")
                        .param("message", "The exam schedule is available.")
                        .param("recipient", "all"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/announcements"));

        assertEquals(1, notificationRepository
                .findByRecipientTypeAndRecipientIdAndEventKey(
                        "STUDENT", student.getId(), "announcement:" + requestId)
                .stream().count());
        assertEquals(1, notificationRepository
                .findByRecipientTypeAndRecipientIdAndEventKey(
                        "STUDENT", otherStudent.getId(), "announcement:" + requestId)
                .stream().count());
        assertEquals(expectedRecipientCount, notificationRepository
                .findByRecipientTypeAndEventKey(
                        "STUDENT", "announcement:" + requestId).size());

        mockMvc.perform(post("/admin/announcements")
                        .session(adminSession)
                        .header("X-CSRF-TOKEN", adminCsrf)
                        .param("requestId", requestId)
                        .param("title", "Exam update")
                        .param("message", "The exam schedule is available.")
                        .param("recipient", "all"))
                .andExpect(status().is3xxRedirection());
        assertEquals(expectedRecipientCount, notificationRepository
                .findByRecipientTypeAndEventKey(
                        "STUDENT", "announcement:" + requestId).size());

        String oneStudentEvent = UUID.randomUUID().toString();
        mockMvc.perform(post("/admin/announcements")
                        .session(adminSession)
                        .header("X-CSRF-TOKEN", adminCsrf)
                        .param("requestId", oneStudentEvent)
                        .param("title", "Individual update")
                        .param("message", "A private in-app update.")
                        .param("recipient", student.getId().toString()))
                .andExpect(status().is3xxRedirection());
        assertEquals(1, notificationRepository
                .findByRecipientTypeAndEventKey(
                        "STUDENT", "announcement:" + oneStudentEvent).size());
        verify(emailService, times((int) expectedRecipientCount + 1))
                .send(any(EmailMessage.class));
    }

    @Test
    void studentCannotCreateAdminAnnouncementsAndInvalidInputsAreRejected()
            throws Exception {
        mockMvc.perform(get("/admin/announcements")
                        .session(studentSession(student)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));

        MockHttpSession admin = adminSession();
        String csrf = csrfFrom("/admin/announcements", admin);
        mockMvc.perform(post("/admin/announcements")
                        .session(admin)
                        .header("X-CSRF-TOKEN", csrf)
                        .param("requestId", UUID.randomUUID().toString())
                        .param("title", " ")
                        .param("message", "Announcement")
                        .param("recipient", "all"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Enter a title")));
        assertTrue(notificationRepository.findAll().isEmpty());
    }

    @Test
    void announcementRetryIsIdempotentAndRejectsChangedContentOrAudience()
            throws Exception {
        MockHttpSession admin = adminSession();
        String csrf = csrfFrom("/admin/announcements", admin);
        String requestId = UUID.randomUUID().toString();

        postSelectedAnnouncement(
                admin, csrf, requestId, student.getId(), "Retry title", "Retry body")
                .andExpect(status().is3xxRedirection());
        postSelectedAnnouncement(
                admin, csrf, requestId, student.getId(), "Retry title", "Retry body")
                .andExpect(status().is3xxRedirection());
        assertEquals(1, announcementDeliveryRepository
                .findByAnnouncementRequestRequestIdOrderByStudentIdAsc(requestId)
                .size());
        verify(emailService, times(1)).send(any(EmailMessage.class));

        postSelectedAnnouncement(
                admin, csrf, requestId, student.getId(), "Changed title", "Retry body")
                .andExpect(status().is3xxRedirection());
        postSelectedAnnouncement(
                admin, csrf, requestId, otherStudent.getId(), "Retry title", "Retry body")
                .andExpect(status().is3xxRedirection());
        assertEquals(1, announcementDeliveryRepository
                .findByAnnouncementRequestRequestIdOrderByStudentIdAsc(requestId)
                .size());
        verify(emailService, times(1)).send(any(EmailMessage.class));
    }

    @Test
    void partialEmailFailureDoesNotStopOtherRecipients()
            throws Exception {
        MockHttpSession admin = adminSession();
        String csrf = csrfFrom("/admin/announcements", admin);
        String requestId = UUID.randomUUID().toString();
        doThrow(new EmailDeliveryException("private transport detail"))
                .when(emailService)
                .send(argThat(email ->
                        email != null && student.getEmail().equals(email.recipient())));

        mockMvc.perform(post("/admin/announcements")
                        .session(admin)
                        .header("X-CSRF-TOKEN", csrf)
                        .param("requestId", requestId)
                        .param("title", "Delivery status")
                        .param("message", "Send individually.")
                        .param("recipient", "selected")
                        .param("recipientIds", student.getId().toString())
                        .param("recipientIds", otherStudent.getId().toString()))
                .andExpect(status().is3xxRedirection());

        List<AnnouncementDelivery> deliveries =
                announcementDeliveryRepository
                        .findByAnnouncementRequestRequestIdOrderByStudentIdAsc(
                                requestId);
        assertEquals(2, deliveries.size());
        assertEquals(AnnouncementDeliveryStatus.FAILED,
                deliveries.get(0).getStatus());
        assertEquals("Email service reported delivery failure.",
                deliveries.get(0).getFailureReason());
        assertEquals(AnnouncementDeliveryStatus.SENT,
                deliveries.get(1).getStatus());
        assertTrue(deliveries.get(1).getSentAt() != null);
        verify(emailService, times(2)).send(any(EmailMessage.class));

        mockMvc.perform(post("/admin/announcements")
                        .session(admin)
                        .header("X-CSRF-TOKEN", csrf)
                        .param("requestId", requestId)
                        .param("title", "Delivery status")
                        .param("message", "Send individually.")
                        .param("recipient", "selected")
                        .param("recipientIds", student.getId().toString())
                        .param("recipientIds", otherStudent.getId().toString()))
                .andExpect(status().is3xxRedirection());
        verify(emailService, times(2)).send(any(EmailMessage.class));
    }

    @Test
    void concurrentRequestsSendEachRecipientAtMostOnce()
            throws Exception {
        String requestId = UUID.randomUUID().toString();
        List<Long> recipients = List.of(student.getId(), otherStudent.getId());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                int count = notificationService.createAnnouncementForRecipients(
                        requestId, "Concurrent mail", "One delivery per student.",
                        recipients);
                notificationService.sendPendingAnnouncementEmails(requestId);
                return count;
            });
            Future<Integer> second = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                int count = notificationService.createAnnouncementForRecipients(
                        requestId, "Concurrent mail", "One delivery per student.",
                        recipients);
                notificationService.sendPendingAnnouncementEmails(requestId);
                return count;
            });

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(2, first.get(20, TimeUnit.SECONDS));
            assertEquals(2, second.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(2, announcementDeliveryRepository
                .findByAnnouncementRequestRequestIdOrderByStudentIdAsc(requestId)
                .size());
        assertEquals(2, announcementDeliveryRepository
                .findByAnnouncementRequestRequestIdOrderByStudentIdAsc(requestId)
                .stream()
                .filter(delivery -> delivery.getStatus()
                        == AnnouncementDeliveryStatus.SENT)
                .count());
        verify(emailService, times(2)).send(any(EmailMessage.class));
    }

    @Test
    void invalidSelectedRecipientDoesNotCreateOrSendAnnouncement()
            throws Exception {
        MockHttpSession admin = adminSession();
        String csrf = csrfFrom("/admin/announcements", admin);
        mockMvc.perform(post("/admin/announcements")
                        .session(admin)
                        .header("X-CSRF-TOKEN", csrf)
                        .param("requestId", UUID.randomUUID().toString())
                        .param("title", "Invalid recipient")
                        .param("message", "Do not send.")
                        .param("recipient", "selected")
                        .param("recipientIds", Long.toString(Long.MAX_VALUE)))
                .andExpect(status().is3xxRedirection());
        assertTrue(announcementRequestRepository.findAll().isEmpty());
        verify(emailService, times(0)).send(any(EmailMessage.class));
    }

    @Test
    void studentCannotTriggerBulkEmail()
            throws Exception {
        MockHttpSession student = studentSession(this.student);
        String csrf = csrfFrom("/login", student);
        mockMvc.perform(post("/admin/announcements")
                        .session(student)
                        .header("X-CSRF-TOKEN", csrf)
                        .param("requestId", UUID.randomUUID().toString())
                        .param("title", "Unauthorized")
                        .param("message", "Must not be sent.")
                        .param("recipient", "all"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
        verify(emailService, times(0)).send(any(EmailMessage.class));
    }

    @Test
    void adminBulkEmailRequiresCsrfToken()
            throws Exception {
        mockMvc.perform(post("/admin/announcements")
                        .session(adminSession())
                        .param("requestId", UUID.randomUUID().toString())
                        .param("title", "Missing CSRF")
                        .param("message", "Must not send.")
                        .param("recipient", "all"))
                .andExpect(status().isForbidden());
        assertTrue(announcementRequestRepository.findAll().isEmpty());
        verify(emailService, times(0)).send(any(EmailMessage.class));
    }

    @Test
    void studentPreferencesPersistLoadAndRemainIsolated()
            throws Exception {
        MockHttpSession ownSession = studentSession(student);
        MockHttpSession otherSession = studentSession(otherStudent);
        mockMvc.perform(get("/notification-preferences").session(ownSession))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Notification preferences")));

        mockMvc.perform(post("/notification-preferences")
                        .session(ownSession)
                        .param("examEmailEnabled", "true")
                        .param("inAppEnabled", "true"))
                .andExpect(status().isForbidden());

        String csrf = csrfFrom("/notification-preferences", ownSession);
        mockMvc.perform(post("/notification-preferences")
                        .session(ownSession)
                        .header("X-CSRF-TOKEN", csrf)
                        .param("examEmailEnabled", "true")
                        .param("inAppEnabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/notification-preferences"));

        StudentNotificationPreference saved =
                preferenceRepository.findById(student.getId()).orElseThrow();
        assertTrue(saved.isExamEmailEnabled());
        assertFalse(saved.isResultEmailEnabled());
        assertFalse(saved.isSupportEmailEnabled());
        assertFalse(saved.isAnnouncementEmailEnabled());
        assertTrue(saved.isInAppEnabled());
        assertTrue(preferenceService.getOrDefault(otherStudent.getId())
                .isAnnouncementEmailEnabled());
        assertTrue(preferenceService.emailEnabled(
                student.getId(), NotificationType.EXAM_AVAILABLE));
        assertFalse(preferenceService.emailEnabled(
                student.getId(), NotificationType.RESULT_PUBLISHED));
        assertFalse(preferenceService.emailEnabled(
                student.getId(), NotificationType.SUPPORT_MESSAGE));
        assertFalse(preferenceService.emailEnabled(
                student.getId(), NotificationType.ADMIN_ANNOUNCEMENT));
        assertTrue(preferenceService.emailEnabled(
                student.getId(), NotificationType.ACCOUNT));

        mockMvc.perform(get("/notification-preferences")
                        .session(ownSession))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "checked")));
        mockMvc.perform(get("/notification-preferences")
                        .session(otherSession))
                .andExpect(status().isOk());
    }

    @Test
    void disabledPreferencesSuppressOptionalChannelsButNotAccountNotifications()
            throws Exception {
        preferenceService.save(
                student.getId(), false, false, false, false, false);
        String requestId = UUID.randomUUID().toString();
        notificationService.createAnnouncementForRecipients(
                requestId,
                "Preference test",
                "Respect the selected channels.",
                List.of(student.getId(), otherStudent.getId()));

        NotificationService.AnnouncementDeliverySummary summary =
                notificationService.sendPendingAnnouncementEmails(requestId);
        assertEquals(1, summary.sent());
        assertEquals(0, summary.failed());
        assertEquals(0, summary.pending());

        List<AnnouncementDelivery> deliveries =
                announcementDeliveryRepository
                        .findByAnnouncementRequestRequestIdOrderByStudentIdAsc(
                                requestId);
        assertEquals(AnnouncementDeliveryStatus.SUPPRESSED,
                deliveries.get(0).getStatus());
        assertEquals(AnnouncementDeliveryStatus.SENT,
                deliveries.get(1).getStatus());
        assertFalse(notificationRepository
                .findByRecipientTypeAndRecipientIdAndEventKey(
                        "STUDENT",
                        student.getId(),
                        "announcement:" + requestId)
                .isPresent());
        assertTrue(notificationRepository
                .findByRecipientTypeAndRecipientIdAndEventKey(
                        "STUDENT",
                        otherStudent.getId(),
                        "announcement:" + requestId)
                .isPresent());

        assertTrue(notificationService.createStudentNotification(
                student.getId(),
                "essential-account-notice",
                "Security notice",
                "Essential account notice.",
                NotificationType.ACCOUNT,
                "/profile",
                null));
        assertFalse(notificationService.createStudentNotification(
                student.getId(),
                "optional-result-notice",
                "Result",
                "Optional result alert.",
                NotificationType.RESULT_PUBLISHED,
                "/dashboard",
                null));
        verify(emailService, times(1)).send(any(EmailMessage.class));
    }

    @Test
    void adminDeliveryTrackingIsProtectedAndFiltersStatusesWithPagination()
            throws Exception {
        mockMvc.perform(get("/admin/announcement-deliveries"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
        mockMvc.perform(get("/admin/announcement-deliveries")
                        .session(studentSession(student)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));

        List<Long> recipientIds = new java.util.ArrayList<>();
        recipientIds.add(student.getId());
        recipientIds.add(otherStudent.getId());
        while (recipientIds.size() < 26) {
            recipientIds.add(studentRepository.saveAndFlush(new Student(
                    "Tracking Student " + recipientIds.size(),
                    "tracking-" + UUID.randomUUID() + "@example.test",
                    "test-hash")).getId());
        }
        String requestId = UUID.randomUUID().toString();
        notificationService.createAnnouncementForRecipients(
                requestId,
                "Tracking announcement",
                "Status filter test.",
                recipientIds);
        List<AnnouncementDelivery> rows =
                announcementDeliveryRepository
                        .findByAnnouncementRequestRequestIdOrderByStudentIdAsc(
                                requestId);
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            assertEquals(1, announcementDeliveryRepository.claimPending(
                    rows.get(1).getId(),
                    AnnouncementDeliveryStatus.PENDING,
                    AnnouncementDeliveryStatus.SENDING));
            assertEquals(1, announcementDeliveryRepository.claimPending(
                    rows.get(2).getId(),
                    AnnouncementDeliveryStatus.PENDING,
                    AnnouncementDeliveryStatus.SENDING));
            assertEquals(1, announcementDeliveryRepository.markSent(
                    rows.get(2).getId(),
                    AnnouncementDeliveryStatus.SENDING,
                    AnnouncementDeliveryStatus.SENT));
            assertEquals(1, announcementDeliveryRepository.claimPending(
                    rows.get(3).getId(),
                    AnnouncementDeliveryStatus.PENDING,
                    AnnouncementDeliveryStatus.SENDING));
            assertEquals(1, announcementDeliveryRepository.markFailed(
                    rows.get(3).getId(),
                    AnnouncementDeliveryStatus.SENDING,
                    AnnouncementDeliveryStatus.FAILED,
                    "Email service reported delivery failure."));
        });

        MockHttpSession admin = adminSession();
        mockMvc.perform(get("/admin/announcement-deliveries")
                        .session(admin)
                        .param("status", "SENT"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("deliveries",
                        org.hamcrest.Matchers.hasProperty("totalElements",
                                org.hamcrest.Matchers.equalTo(1L))));
        mockMvc.perform(get("/admin/announcement-deliveries")
                        .session(admin)
                        .param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Email service reported delivery failure.")));
        mockMvc.perform(get("/admin/announcement-deliveries")
                        .session(admin)
                        .param("status", "SENDING"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "SENDING")));
        mockMvc.perform(get("/admin/announcement-deliveries")
                        .session(admin)
                        .param("status", "PENDING")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("deliveries",
                        org.hamcrest.Matchers.hasProperty("totalElements",
                                org.hamcrest.Matchers.equalTo(23L))));
        mockMvc.perform(get("/admin/announcement-deliveries")
                        .session(admin)
                        .param("status", "PENDING")
                        .param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("deliveries",
                        org.hamcrest.Matchers.hasProperty("number",
                                org.hamcrest.Matchers.equalTo(1))));
    }

    @Test
    void announcementRequestIdempotencyValidatesContentAndRecipients() {
        String requestId = UUID.randomUUID().toString();
        int expectedRecipientCount = (int) studentRepository.count();
        int createdCount = notificationService.createAnnouncement(
                requestId, "Same title", "Same message", null);

        assertEquals(expectedRecipientCount, createdCount);
        assertEquals(createdCount, notificationService.createAnnouncement(
                requestId, " Same title ", " Same message ", null));
        assertEquals(expectedRecipientCount, notificationRepository
                .findByRecipientTypeAndEventKey(
                        "STUDENT", "announcement:" + requestId).size());

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> notificationService.createAnnouncement(
                        requestId, "Different title", "Same message", null));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> notificationService.createAnnouncement(
                        requestId,
                        "Same title",
                        "Same message",
                        student.getId()));
        assertEquals(expectedRecipientCount, notificationRepository
                .findByRecipientTypeAndEventKey(
                        "STUDENT", "announcement:" + requestId).size());
    }

    @Test
    void concurrentAnnouncementRetriesCreateOneSetAndReturnSameOutcome()
            throws Exception {
        String requestId = UUID.randomUUID().toString();
        int expectedRecipientCount = (int) studentRepository.count();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return notificationService.createAnnouncement(
                        requestId, "Concurrent", "Same request", null);
            });
            Future<Integer> second = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return notificationService.createAnnouncement(
                        requestId, "Concurrent", "Same request", null);
            });

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(expectedRecipientCount, first.get(15, TimeUnit.SECONDS));
            assertEquals(expectedRecipientCount, second.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(expectedRecipientCount, notificationRepository
                .findByRecipientTypeAndEventKey(
                        "STUDENT", "announcement:" + requestId).size());
        assertEquals(1, announcementRequestRepository.count());
    }

    @Test
    void notificationCanBeOpenedOnlyThroughValidatedInternalUrl()
            throws Exception {
        AppNotification notice = notificationRepository.saveAndFlush(
                new AppNotification(
                        student.getId(),
                        "unsafe-link",
                        "Link check",
                        "Text",
                        NotificationType.ACCOUNT,
                        "//attacker.example",
                        null));

        mockMvc.perform(get("/notifications")
                        .session(studentSession(student)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("attacker.example"))));
        assertTrue(notificationRepository.findById(notice.getId()).isPresent());
    }

    private AppNotification notification(
            Student recipient,
            String eventKey,
            String title) {
        return notificationRepository.saveAndFlush(new AppNotification(
                recipient.getId(),
                eventKey,
                title,
                "Notification details.",
                NotificationType.RESULT_PUBLISHED,
                "/dashboard#history",
                17L));
    }

    private org.springframework.test.web.servlet
            .ResultActions postSelectedAnnouncement(
                    MockHttpSession admin,
                    String csrf,
                    String requestId,
                    Long recipientId,
                    String title,
                    String message) throws Exception {
        return mockMvc.perform(post("/admin/announcements")
                .session(admin)
                .header("X-CSRF-TOKEN", csrf)
                .param("requestId", requestId)
                .param("title", title)
                .param("message", message)
                .param("recipient", "selected")
                .param("recipientIds", recipientId.toString()));
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
        session.setAttribute("studentName", account.getFullName());
        return session;
    }

    private MockHttpSession adminSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("quizzyAdmin", true);
        return session;
    }
}
