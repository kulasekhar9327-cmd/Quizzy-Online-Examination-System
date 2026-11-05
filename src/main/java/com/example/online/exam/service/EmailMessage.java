package com.example.online.exam.service;

public record EmailMessage(
        String recipient,
        String subject,
        String textBody) {
}
