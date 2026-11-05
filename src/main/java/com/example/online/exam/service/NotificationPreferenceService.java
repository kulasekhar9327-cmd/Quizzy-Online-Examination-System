package com.example.online.exam.service;

import com.example.online.exam.entity.NotificationType;
import com.example.online.exam.entity.StudentNotificationPreference;
import com.example.online.exam.repository.StudentNotificationPreferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class NotificationPreferenceService {

    private final StudentNotificationPreferenceRepository repository;

    public NotificationPreferenceService(
            StudentNotificationPreferenceRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public StudentNotificationPreference getOrDefault(Long studentId) {
        return repository.findById(studentId)
                .orElseGet(() -> new StudentNotificationPreference(studentId));
    }

    @Transactional
    public StudentNotificationPreference save(
            Long studentId,
            boolean examEmailEnabled,
            boolean resultEmailEnabled,
            boolean supportEmailEnabled,
            boolean announcementEmailEnabled,
            boolean inAppEnabled) {
        StudentNotificationPreference preference =
                repository.findById(studentId)
                        .orElseGet(() ->
                                new StudentNotificationPreference(studentId));
        preference.update(
                examEmailEnabled,
                resultEmailEnabled,
                supportEmailEnabled,
                announcementEmailEnabled,
                inAppEnabled);
        return repository.save(preference);
    }

    @Transactional(readOnly = true)
    public boolean emailEnabled(Long studentId, NotificationType type) {
        return emailEnabled(getOrDefault(studentId), type);
    }

    @Transactional(readOnly = true)
    public Map<Long, StudentNotificationPreference> loadForStudents(
            Collection<Long> studentIds) {
        if (studentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, StudentNotificationPreference> preferences =
                repository.findByStudentIdIn(studentIds).stream()
                        .collect(Collectors.toMap(
                                StudentNotificationPreference::getStudentId,
                                Function.identity()));
        for (Long studentId : studentIds) {
            preferences.computeIfAbsent(
                    studentId,
                    StudentNotificationPreference::new);
        }
        return Map.copyOf(preferences);
    }

    public boolean emailEnabled(
            StudentNotificationPreference preference,
            NotificationType type) {
        return switch (type) {
            case EXAM_AVAILABLE, EXAM_SCHEDULE_UPDATED ->
                    preference.isExamEmailEnabled();
            case RESULT_PUBLISHED -> preference.isResultEmailEnabled();
            case SUPPORT_MESSAGE -> preference.isSupportEmailEnabled();
            case ADMIN_ANNOUNCEMENT ->
                    preference.isAnnouncementEmailEnabled();
            case ACCOUNT -> true;
        };
    }

    @Transactional(readOnly = true)
    public boolean inAppEnabled(Long studentId, NotificationType type) {
        return type == NotificationType.ACCOUNT
                || getOrDefault(studentId).isInAppEnabled();
    }

    public boolean inAppEnabled(
            StudentNotificationPreference preference,
            NotificationType type) {
        return type == NotificationType.ACCOUNT
                || preference.isInAppEnabled();
    }
}
