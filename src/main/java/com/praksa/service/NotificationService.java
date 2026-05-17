package com.praksa.service;

import com.praksa.dto.notification.NotificationResponse;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.NotificationType;

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
     * Returns all notifications for the currently logged-in user,
     * newest first.
     */
    List<NotificationResponse> getMyNotifications();

    /**
     * Returns all unsent notifications — useful for a retry mechanism.
     */
    List<NotificationResponse> getUnsentNotifications();
}
