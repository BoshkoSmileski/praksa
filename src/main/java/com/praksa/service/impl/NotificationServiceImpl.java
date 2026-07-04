package com.praksa.service.impl;

import com.praksa.dto.notification.NotificationResponse;
import com.praksa.model.Notification;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;
import com.praksa.repository.NotificationRepository;
import com.praksa.repository.UserRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.service.EmailService;
import com.praksa.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final SecurityUtils securityUtils;

    @Override
    @Transactional
    public void notify(User recipient, Thesis thesis, NotificationType type) {
        notify(recipient, thesis, type, type.getDefaultBody());
    }

    @Override
    @Transactional
    public void notify(User recipient, Thesis thesis, NotificationType type, String customMessage) {
        // 1. Save the notification record synchronously, within the calling transaction.
        //    If the parent transaction rolls back, this record also rolls back — correct behavior.
        Notification notification = Notification.builder()
                .user(recipient)
                .thesis(thesis)
                .type(type.name())
                .isSent(false)
                .build();

        notificationRepository.save(notification);

        // 2. Capture all data we need BEFORE leaving the transaction boundary.
        //    After this method returns, the Hibernate session will close.
        //    We extract primitives now so the async thread has everything it needs
        //    without touching any lazy JPA fields.
        UUID notificationId = notification.getId();
        String recipientEmail = recipient.getEmail();
        String subject = buildSubject(type, thesis);
        String body = buildBody(recipient, thesis, type, customMessage);

        // 3. Fire the async email — this returns immediately.
        //    The HTTP response does NOT wait for the email to be sent.
        emailService.sendAsync(notificationId, recipientEmail, subject, body);

        log.debug("Notification created: type={}, recipient={}, thesis={}",
                type, recipientEmail, thesis != null ? thesis.getId() : "none");
    }

    @Override
    @Transactional
    public void notifyRole(Role role, Thesis thesis, NotificationType type) {
        // Fan out to every user with this role.
        // For a small team (1-2 archive users, 1-2 service users) this is fine.
        // For thousands of users we'd switch to a single "queue" notification
        // visible via a dashboard endpoint.
        List<User> recipients = userRepository.findByRole(role);
        for (User recipient : recipients) {
            notify(recipient, thesis, type);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationResponse> getMyNotifications() {
        User currentUser = securityUtils.getCurrentUser();
        return notificationRepository.findByUserOrderByCreatedAtDesc(currentUser)
                .stream()
                .map(NotificationResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationResponse> getUnsentNotifications() {
        return notificationRepository.findByIsSentFalse()
                .stream()
                .map(NotificationResponse::from)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Email content builders
    // -------------------------------------------------------------------------

    private String buildSubject(NotificationType type, Thesis thesis) {
        if (thesis != null) {
            return "[DiplomaSystem] " + type.getSubject() + " — " + thesis.getTitle();
        }
        return "[DiplomaSystem] " + type.getSubject();
    }

    private String buildBody(User recipient, Thesis thesis, NotificationType type, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("Dear ").append(recipient.getFullName()).append(",\n\n");
        sb.append(message).append("\n\n");

        if (thesis != null) {
            sb.append("Thesis: ").append(thesis.getTitle()).append("\n");
            sb.append("Current status: ").append(thesis.getStatus()).append("\n\n");
        }

        sb.append("This is an automated message from the DiplomaSystem.\n");
        sb.append("Please do not reply to this email.");
        return sb.toString();
    }
}
