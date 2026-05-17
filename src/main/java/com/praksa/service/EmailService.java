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

    @Value("${spring.mail.username}")
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
