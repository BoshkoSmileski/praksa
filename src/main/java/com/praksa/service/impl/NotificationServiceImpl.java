package com.praksa.service.impl;

import com.praksa.dto.notification.NotificationResponse;
import com.praksa.model.Notification;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;
import com.praksa.exception.ResourceNotFoundException;
import com.praksa.exception.UnauthorizedException;
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
        // BUG-19: this endpoint exposes system-wide unsent notifications, so it is an
        // operational/oversight view restricted to STUDENT_SERVICE. Authorization runs
        // BEFORE any repository query — an unauthorized caller never reaches the DB.
        requireRole(securityUtils.getCurrentUser(), Role.STUDENT_SERVICE);
        return notificationRepository.findByIsSentFalse()
                .stream()
                .map(NotificationResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public int retryUnsentNotifications(int batchSize, java.time.OffsetDateTime createdBefore) {
        // Bounded, oldest-first page of still-unsent rows old enough to have cleared the
        // normal flow's original async send. This method only READS notifications; the
        // actual write (marking isSent=true) happens inside EmailService.sendAsync in its
        // own transaction, on success only.
        List<Notification> unsent = notificationRepository
                .findByIsSentFalseAndCreatedAtBeforeOrderByCreatedAtAsc(
                        createdBefore, org.springframework.data.domain.PageRequest.of(0, batchSize));

        if (unsent.isEmpty()) {
            return 0;
        }

        int dispatched = 0;
        for (Notification notification : unsent) {
            try {
                // Rebuild the email from the persisted row while the session is open, then
                // hand PRIMITIVES to the async layer — exactly as the normal notify() flow
                // does — so the async thread never touches a lazy JPA field. We reuse the
                // same subject/body builders (single source of truth for content).
                //
                // NOTE: the original custom message (if any) is not persisted on the row, so
                // a retry uses the type's default body. See the P2.6 handoff note.
                NotificationType type = NotificationType.valueOf(notification.getType());
                User recipient = notification.getUser();
                Thesis thesis = notification.getThesis();

                UUID notificationId = notification.getId();
                String recipientEmail = recipient.getEmail();
                String subject = buildSubject(type, thesis);
                String body = buildBody(recipient, thesis, type, type.getDefaultBody());

                // Delegate to the SAME async delivery path. No new Notification row is created;
                // EmailService owns the mail-enabled guard, the send, and the mark-sent write.
                emailService.sendAsync(notificationId, recipientEmail, subject, body);
                dispatched++;
            } catch (Exception e) {
                // Failure isolation: a single bad row (e.g. an unparseable type or a missing
                // recipient) must not abort the rest of the batch.
                log.error("Retry: could not dispatch notification {} (type={}): {}",
                        notification.getId(), notification.getType(), e.getMessage());
            }
        }

        log.info("Retry: dispatched {} of {} eligible unsent notification(s)", dispatched, unsent.size());
        return dispatched;
    }

    @Override
    @Transactional
    public NotificationResponse markAsRead(UUID notificationId) {
        // P3.6 — ownership is enforced SERVER-SIDE from the authenticated principal.
        // The caller-supplied id is never trusted as proof of ownership.
        User currentUser = securityUtils.getCurrentUser();

        // 1. Load the notification (404 if it does not exist).
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Известувањето не е пронајдено."));

        // 2. Verify it belongs to the current user (403 otherwise). getId() on the LAZY
        //    user proxy is safe — it does not trigger initialization.
        if (!notification.getUser().getId().equals(currentUser.getId())) {
            throw new UnauthorizedException("Не можете да го менувате известувањето на друг корисник.");
        }

        // 3. Idempotent mark-read. Only write when it actually changes, so an already-read
        //    notification is a clean no-op (no error, no duplicate row). NOTHING else is
        //    touched: isSent/sentAt/type/recipient/thesis are all left exactly as they were,
        //    and no email is triggered.
        if (!notification.isRead()) {
            notification.setRead(true);
            notificationRepository.save(notification);
        }

        return NotificationResponse.from(notification);
    }

    @Override
    @Transactional
    public int markAllAsRead() {
        // User-scoped bulk update: only the authenticated user's unread rows are affected.
        User currentUser = securityUtils.getCurrentUser();
        return notificationRepository.markAllReadByUser(currentUser);
    }

    @Override
    @Transactional(readOnly = true)
    public long getUnreadCount() {
        // User-scoped: only the authenticated user's own unread notifications are counted.
        User currentUser = securityUtils.getCurrentUser();
        return notificationRepository.countByUserAndIsReadFalse(currentUser);
    }

    private void requireRole(User user, Role required) {
        if (user.getRole() != required) {
            throw new UnauthorizedException("Оваа акција бара улога: " + required);
        }
    }

    // -------------------------------------------------------------------------
    // Email content builders
    // -------------------------------------------------------------------------

    private String buildSubject(NotificationType type, Thesis thesis) {
        if (thesis != null) {
            return "[Дипломски систем] " + type.getSubject() + " — " + thesis.getTitle();
        }
        return "[Дипломски систем] " + type.getSubject();
    }

    private String buildBody(User recipient, Thesis thesis, NotificationType type, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("Почитуван/а ").append(recipient.getFullName()).append(",\n\n");
        sb.append(message).append("\n\n");

        if (thesis != null) {
            sb.append("Дипломска работа: ").append(thesis.getTitle()).append("\n");
            sb.append("Тековен статус: ").append(thesis.getStatus()).append("\n\n");
        }

        sb.append("Ова е автоматска порака од системот за дипломски работи.\n");
        sb.append("Ве молиме не одговарајте на оваа е-пошта.");
        return sb.toString();
    }
}
