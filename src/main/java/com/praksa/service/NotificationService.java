package com.praksa.service;

import com.praksa.dto.notification.NotificationResponse;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;

import java.util.List;

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
}
