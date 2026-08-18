package com.praksa.service;

import com.praksa.model.Notification;
import com.praksa.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmailService} email-delivery / notification-status semantics
 * (roadmap Item #15 — production-safe SMTP configuration).
 *
 * Uses a mocked {@link JavaMailSender}; no real SMTP access is performed.
 */
@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock private JavaMailSender mailSender;
    @Mock private NotificationRepository notificationRepository;

    private EmailService emailService;

    @BeforeEach
    void setUp() {
        emailService = new EmailService(mailSender, notificationRepository);
        ReflectionTestUtils.setField(emailService, "fromAddress", "no-reply@test.local");
    }

    private void mailEnabled(boolean enabled) {
        ReflectionTestUtils.setField(emailService, "mailEnabled", enabled);
    }

    @Test
    @DisplayName("enabled + successful send → notification is marked is_sent=true with a sentAt timestamp")
    void successfulSend_marksNotificationSent() {
        mailEnabled(true);
        UUID id = UUID.randomUUID();
        Notification notification = Notification.builder().id(id).isSent(false).build();
        when(notificationRepository.findById(id)).thenReturn(Optional.of(notification));

        emailService.sendAsync(id, "student@test.local", "Subject", "Body");

        verify(mailSender).send(any(SimpleMailMessage.class));
        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(saved.capture());
        assertThat(saved.getValue().isSent()).isTrue();
        assertThat(saved.getValue().getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("enabled + send throws MailException → notification is NOT marked sent")
    void failedSend_doesNotMarkNotificationSent() {
        mailEnabled(true);
        UUID id = UUID.randomUUID();
        doThrow(new MailSendException("SMTP down"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        emailService.sendAsync(id, "student@test.local", "Subject", "Body");

        // MailException is caught and swallowed; the row is never touched, so it
        // stays is_sent=false. No lookup and no save of a "sent" state must occur.
        verify(notificationRepository, never()).findById(any());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("disabled → no SMTP connection attempted and notification is NOT marked sent")
    void disabled_skipsSendAndDoesNotMarkSent() {
        mailEnabled(false);
        UUID id = UUID.randomUUID();

        emailService.sendAsync(id, "student@test.local", "Subject", "Body");

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        verify(notificationRepository, never()).save(any());
    }
}
