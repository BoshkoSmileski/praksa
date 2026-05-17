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
        this.sentAt = n.getSentAt();
        this.createdAt = n.getCreatedAt();
    }
}
