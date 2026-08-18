package com.praksa.dto.notification;

import com.praksa.model.Notification;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
public class NotificationResponse {

    private final UUID id;
    private final UUID thesisId;
    private final String thesisTitle;
    private final String type;
    private final boolean isSent;
    // P3.6 — application read/unread state. Independent of isSent (email delivery).
    // Serialized on the wire as "read" (Jackson strips the "is" prefix from the boolean
    // getter, exactly as the existing isSent field serializes as "sent").
    private final boolean isRead;
    private final OffsetDateTime sentAt;
    private final OffsetDateTime createdAt;

    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n);
    }

    private NotificationResponse(Notification n) {
        this.id = n.getId();
        this.thesisId = n.getThesis() != null ? n.getThesis().getId() : null;
        this.thesisTitle = n.getThesis() != null ? n.getThesis().getTitle() : null;
        this.type = n.getType();
        this.isSent = n.isSent();
        this.isRead = n.isRead();
        this.sentAt = n.getSentAt();
        this.createdAt = n.getCreatedAt();
    }
}
