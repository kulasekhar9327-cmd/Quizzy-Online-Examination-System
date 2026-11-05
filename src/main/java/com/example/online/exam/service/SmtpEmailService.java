package com.example.online.exam.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class SmtpEmailService implements EmailService {

    private final JavaMailSender mailSender;
    private final String smtpHost;
    private final String fromAddress;

    public SmtpEmailService(
            JavaMailSender mailSender,
            @Value("${spring.mail.host:}") String smtpHost,
            @Value("${quizzy.mail.from:}") String fromAddress) {
        this.mailSender = mailSender;
        this.smtpHost = smtpHost;
        this.fromAddress = fromAddress;
    }

    @Override
    public void send(EmailMessage message) {
        if (smtpHost == null || smtpHost.isBlank()
                || fromAddress == null || fromAddress.isBlank()) {
            throw new EmailDeliveryException(
                    "Email delivery is not configured.");
        }

        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(fromAddress);
        mail.setTo(message.recipient());
        mail.setSubject(message.subject());
        mail.setText(message.textBody());
        try {
            mailSender.send(mail);
        } catch (MailException exception) {
            throw new EmailDeliveryException(
                    "Email delivery failed.", exception);
        }
    }
}
