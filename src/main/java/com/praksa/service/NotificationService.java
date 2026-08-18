package com.praksa.service;

import com.praksa.dto.notification.NotificationResponse;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;

import java.util.List;
import java.util.UUID;

public interface NotificationService {

    /**
     * Core method — call this from any service after a meaningful event.
     * Saves the notification record and fires the email asynchronously.
     *
     * @param recipient the user who should receive the notification
     * @param thesis    the related thesis (can be null for system notifications)
     * @param type      the type of event that happened
     */
    void notify(User recipient, Thesis thesis, NotificationType type);

    /**
     * Same as above but with a custom message body instead of the default one.
     */
    void notify(User recipient, Thesis thesis, NotificationType type, String customMessage);

    /**
     * Fan out a notification to every user with the given role.
     * Used for queue-style notifications (e.g. "an application is awaiting Archive validation").
     */
    void notifyRole(Role role, Thesis thesis, NotificationType type);

    /**
     * Returns all notifications for the currently logged-in user,
     * newest first.
     */
    List<NotificationResponse> getMyNotifications();

    /**
     * Returns all unsent notifications — useful for a retry mechanism.
     */
    List<NotificationResponse> getUnsentNotifications();

    /**
     * Retry delivery of unsent notifications (P2.6).
     *
     * Loads up to {@code batchSize} notifications that are still {@code isSent=false} and
     * were created before {@code createdBefore} (an age threshold), rebuilds each email
     * from the persisted row, and re-dispatches it through the SAME
     * {@link EmailService#sendAsync} path used by the normal notification flow. This method
     * never creates a new {@code Notification} row and never marks one sent itself —
     * {@code EmailService} stays the single authority for delivery, the {@code isSent=true}
     * transition (only on genuine success), and the {@code app.mail.enabled} guard.
     *
     * A failure on one notification is isolated so the rest of the batch is still processed.
     *
     * @param batchSize     maximum number of unsent notifications to process in this run
     * @param createdBefore only notifications created strictly before this instant are eligible
     * @return the number of notifications for which a send was dispatched
     */
    int retryUnsentNotifications(int batchSize, java.time.OffsetDateTime createdBefore);

    /**
     * P3.6 — mark a single notification as read (application-side read state), scoped to the
     * authenticated user. The notification is loaded and its ownership verified against the
     * current user server-side: a notification that does not exist yields 404, and a
     * notification owned by another user yields 403 — the caller-supplied id is never trusted
     * as proof of ownership. Idempotent: marking an already-read notification succeeds and
     * leaves it read. This never resends an email, never touches {@code isSent}/{@code sentAt},
     * never creates another row, and never changes the type/recipient/thesis.
     *
     * @param notificationId the notification to mark read
     * @return the updated notification (now {@code isRead=true})
     */
    NotificationResponse markAsRead(UUID notificationId);

    /**
     * P3.6 — mark ALL of the authenticated user's unread notifications as read. Scoped to the
     * current user only; already-read rows are untouched; no other user's rows can be affected;
     * no email behavior changes.
     *
     * @return the number of notifications transitioned from unread to read
     */
    int markAllAsRead();

    /**
     * P3.6 — count of the authenticated user's UNREAD notifications. User-scoped: only the
     * current user's unread notifications are counted, never a global or another user's total.
     */
    long getUnreadCount();
}
