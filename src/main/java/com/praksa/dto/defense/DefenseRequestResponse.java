package com.praksa.dto.defense;

import com.praksa.model.DefenseRequest;
import com.praksa.model.enums.DefenseRequestStatus;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
public class DefenseRequestResponse {

    private final UUID id;
    private final UUID thesisId;
    private final String room;
    private final OffsetDateTime scheduledAt;
    private final DefenseRequestStatus status;
    private final String reason;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime decidedAt;
    private final UUID requestedById;
    private final String requestedByName;
    private final UUID decidedById;
    private final String decidedByName;

    public static DefenseRequestResponse from(DefenseRequest r) {
        return new DefenseRequestResponse(r);
    }

    private DefenseRequestResponse(DefenseRequest r) {
        this.id = r.getId();
        this.thesisId = r.getThesis().getId();
        this.room = r.getRoom();
        this.scheduledAt = r.getScheduledAt();
        this.status = r.getStatus();
        this.reason = r.getReason();
        this.createdAt = r.getCreatedAt();
        this.decidedAt = r.getDecidedAt();
        this.requestedById = r.getRequestedBy() != null ? r.getRequestedBy().getId() : null;
        this.requestedByName = r.getRequestedBy() != null ? r.getRequestedBy().getFullName() : null;
        this.decidedById = r.getDecidedBy() != null ? r.getDecidedBy().getId() : null;
        this.decidedByName = r.getDecidedBy() != null ? r.getDecidedBy().getFullName() : null;
    }
}
