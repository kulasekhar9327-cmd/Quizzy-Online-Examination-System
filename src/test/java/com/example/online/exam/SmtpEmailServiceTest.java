package com.example.online.exam;

import com.example.online.exam.service.EmailDeliveryException;
import com.example.online.exam.service.EmailMessage;
import com.example.online.exam.service.SmtpEmailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SmtpEmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Test
    void sendsPlainTextThroughConfiguredSender() {
        SmtpEmailService service = new SmtpEmailService(
                mailSender, "smtp.example.test", "noreply@example.test");

        service.send(new EmailMessage(
                "student@example.test",
                "Quizzy password reset",
                "Use the reset link."));

        ArgumentCaptor<SimpleMailMessage> message =
                ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(message.capture());
        assertEquals("noreply@example.test", message.getValue().getFrom());
        assertEquals("student@example.test", message.getValue().getTo()[0]);
        assertEquals("Quizzy password reset", message.getValue().getSubject());
        assertEquals("Use the reset link.", message.getValue().getText());
    }

    @Test
    void reportsMissingSmtpConfigurationWithoutAttemptingDelivery() {
        SmtpEmailService service = new SmtpEmailService(
                mailSender, "", "");

        assertThrows(
                EmailDeliveryException.class,
                () -> service.send(new EmailMessage(
                        "student@example.test", "Subject", "Body")));
    }

    @Test
    void wrapsDeliveryFailure() {
        SmtpEmailService service = new SmtpEmailService(
                mailSender, "smtp.example.test", "noreply@example.test");
        org.mockito.Mockito.doThrow(new MailSendException("offline"))
                .when(mailSender).send(org.mockito.ArgumentMatchers
                        .any(SimpleMailMessage.class));

        assertThrows(
                EmailDeliveryException.class,
                () -> service.send(new EmailMessage(
                        "student@example.test", "Subject", "Sensitive body")));
    }
}
