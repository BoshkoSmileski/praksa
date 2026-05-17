package com.praksa.dto.defense;

import com.praksa.model.Defense;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
public class DefenseResponse {

    private final UUID id;
    private final UUID thesisId;
    private final String room;
    private final OffsetDateTime scheduledAt;
    private final boolean isCancelled;
    private final UUID cancelledById;
    private final String cancelledByName;
    private final OffsetDateTime cancelledAt;
    private final OffsetDateTime createdAt;

    public static DefenseResponse from(Defense d) {
        return new DefenseResponse(d);
    }

    private DefenseResponse(Defense d) {
        this.id = d.getId();
        this.thesisId = d.getThesis().getId();
        this.room = d.getRoom();
        this.scheduledAt = d.getScheduledAt();
        this.isCancelled = d.isCancelled();
        this.cancelledById = d.getCancelledBy() != null ? d.getCancelledBy().getId() : null;
        this.cancelledByName = d.getCancelledBy() != null ? d.getCancelledBy().getFullName() : null;
        this.cancelledAt = d.getCancelledAt();
        this.createdAt = d.getCreatedAt();
    }
}
