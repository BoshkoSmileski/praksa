package com.praksa.service;

import com.praksa.model.Notification;
import com.praksa.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j  // gives us a 'log' field via Lombok
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;
    private final NotificationRepository notificationRepository;

    /**
     * Master switch for email delivery. When false (the default when no SMTP is
     * configured), delivery is skipped cleanly: no SMTP connection is attempted and
     * the notification row is left is_sent=false — we never pretend an email was sent.
     */
    @Value("${app.mail.enabled:false}")
    private boolean mailEnabled;

    /** Sender address. Falls back to the SMTP username, then a local placeholder. */
    @Value("${app.mail.from:${spring.mail.username:no-reply@diploma-system.local}}")
    private String fromAddress;

    /**
     * Sends an email asynchronously on the 'emailTaskExecutor' thread pool.
     *
     * IMPORTANT: This method receives only primitives — never JPA entities.
     * By the time this runs, the original HTTP transaction is already committed
     * and the Hibernate session is closed. Any entity passed here would be
     * detached and accessing lazy fields would throw LazyInitializationException.
     *
     * @param notificationId the DB record to mark as sent on success
     * @param toEmail        recipient email address
     * @param subject        email subject line
     * @param body           plain-text email body
     */
    @Async("emailTaskExecutor")
    @Transactional  // own transaction — independent from the caller's transaction
    public void sendAsync(UUID notificationId, String toEmail, String subject, String body) {
        // Email delivery is disabled (no SMTP configured). Skip the send but do NOT
        // mark the notification as sent — the row stays is_sent=false, which is the
        // honest state (the message still shows in the in-app notification list).
        if (!mailEnabled) {
            log.info("Email delivery disabled (app.mail.enabled=false); notification {} "
                    + "left unsent, available in-app only.", notificationId);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(toEmail);
            message.setSubject(subject);
            message.setText(body);

            mailSender.send(message);

            // Email sent successfully — update the notification record.
            // We go directly to the repository here to avoid a circular dependency
            // (NotificationService → EmailService → NotificationService).
            markNotificationSent(notificationId);

            log.info("Email sent to {} for notification {}", toEmail, notificationId);

        } catch (MailException e) {
            // Email failed — log it but do NOT throw.
            // The notification record stays with is_sent=false.
            // A background retry job could later query findByIsSentFalse() and retry.
            log.error("Failed to send email to {} for notification {}: {}",
                    toEmail, notificationId, e.getMessage());
        }
    }

    private void markNotificationSent(UUID notificationId) {
        notificationRepository.findById(notificationId).ifPresent(notification -> {
            notification.setSent(true);
            notification.setSentAt(OffsetDateTime.now());
            notificationRepository.save(notification);
        });
    }
}
