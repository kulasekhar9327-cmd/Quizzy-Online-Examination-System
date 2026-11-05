package com.example.online.exam;

import com.example.online.exam.service.EmailDeliveryException;
import com.example.online.exam.service.EmailMessage;
import com.example.online.exam.service.EmailService;
import com.example.online.exam.service.PasswordRecoveryService;
import com.example.online.exam.service.PasswordRecoveryService.RecoveryEmail;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

@Controller
public class PasswordRecoveryController {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(PasswordRecoveryController.class);
    private static final DateTimeFormatter EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    private final PasswordRecoveryService recoveryService;
    private final EmailService emailService;
    private final String applicationBaseUrl;

    public PasswordRecoveryController(
            PasswordRecoveryService recoveryService,
            EmailService emailService,
            @Value("${quizzy.app.base-url:}") String applicationBaseUrl) {
        this.recoveryService = recoveryService;
        this.emailService = emailService;
        this.applicationBaseUrl = applicationBaseUrl;
    }

    @GetMapping("/forgot-password")
    public String showForgotPassword(Model model) {
        model.addAttribute("requestId", UUID.randomUUID().toString());
        return "forgot-password";
    }

    @PostMapping("/forgot-password")
    public String requestPasswordRecovery(
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String requestId,
            Model model) {
        Optional<RecoveryEmail> recovery =
                recoveryService.issueRecovery(email, requestId);
        if (recovery.isPresent()) {
            RecoveryEmail details = recovery.get();
            Optional<String> resetLink = resetLink(details.token());
            if (resetLink.isEmpty()) {
                LOGGER.warn(
                        "Password recovery email was not sent because the application URL is not configured correctly.");
            } else {
                String body = recoveryEmailBody(
                        resetLink.get(), details.expiresAt().format(EXPIRY_FORMAT));
                try {
                    emailService.send(new EmailMessage(
                            details.recipient(),
                            "Reset your Quizzy password",
                            body));
                } catch (EmailDeliveryException exception) {
                    LOGGER.warn(
                            "Password recovery email delivery failed; the request response remains generic.");
                }
            }
        }

        model.addAttribute(
                "success",
                "If an account with that email exists, recovery instructions "
                        + "will be sent.");
        model.addAttribute("requestId", UUID.randomUUID().toString());
        return "forgot-password";
    }

    @GetMapping("/reset-password")
    public String showResetPassword(
            @RequestParam(required = false) String token,
            Model model,
            HttpServletResponse response) {
        setSensitivePageHeaders(response);
        boolean usable = recoveryService.isTokenUsable(token);
        model.addAttribute("token", usable ? token : "");
        model.addAttribute("tokenUsable", usable);
        return "reset-password";
    }

    @PostMapping("/reset-password")
    public String resetPassword(
            @RequestParam(required = false) String token,
            @RequestParam(required = false) String password,
            @RequestParam(required = false) String confirmPassword,
            Model model) {
        if (!recoveryService.isTokenUsable(token)) {
            model.addAttribute("tokenUsable", false);
            model.addAttribute(
                    "error",
                    "This password-reset link is invalid or expired. Request a new one.");
            return "reset-password";
        }
        if (!recoveryService.validPassword(password)) {
            model.addAttribute("token", token);
            model.addAttribute("tokenUsable", true);
            model.addAttribute(
                    "error",
                    "Password must contain "
                            + PasswordRecoveryService.PASSWORD_MIN_LENGTH
                            + " to "
                            + PasswordRecoveryService.PASSWORD_MAX_LENGTH
                            + " characters.");
            return "reset-password";
        }
        if (!password.equals(confirmPassword)) {
            model.addAttribute("token", token);
            model.addAttribute("tokenUsable", true);
            model.addAttribute("error", "The password confirmation does not match.");
            return "reset-password";
        }
        if (!recoveryService.resetPassword(token, password, confirmPassword)) {
            model.addAttribute("tokenUsable", false);
            model.addAttribute(
                    "error",
                    "This password-reset link is invalid or expired. Request a new one.");
            return "reset-password";
        }
        return "redirect:/login?passwordReset";
    }

    private Optional<String> resetLink(String token) {
        if (applicationBaseUrl == null || applicationBaseUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            URI base = URI.create(applicationBaseUrl.trim());
            if (base.getHost() == null
                    || base.getUserInfo() != null
                    || !"https".equalsIgnoreCase(base.getScheme())
                    || base.getQuery() != null
                    || base.getFragment() != null) {
                return Optional.empty();
            }
            return Optional.of(
                    UriComponentsBuilder.fromUri(base)
                            .path("/reset-password")
                            .queryParam("token", token)
                            .build()
                            .encode()
                            .toUriString());
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private String recoveryEmailBody(String resetLink, String expiry) {
        return """
                Hello,

                We received a request to reset your Quizzy password. Use the secure link below:
                %s

                This link expires at %s and can be used only once.

                If you did not request a password reset, you can ignore this email. Your password will not change.

                Quizzy Support
                """.formatted(resetLink, expiry);
    }

    private void setSensitivePageHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
    }
}
