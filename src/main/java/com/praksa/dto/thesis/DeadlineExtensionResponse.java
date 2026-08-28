package com.praksa.dto.thesis;

import com.praksa.model.DeadlineExtensionRequest;
import com.praksa.model.enums.DeadlineExtensionStatus;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
public class DeadlineExtensionResponse {

    private final UUID id;
    private final UUID thesisId;
    private final String reason;
    private final int requestedDays;
    private final DeadlineExtensionStatus status;
    private final String decisionReason;
    private final OffsetDateTime previousDeadline;
    private final OffsetDateTime newDeadline;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime decidedAt;
    private final UUID requestedById;
    private final String requestedByName;
    private final UUID decidedById;
    private final String decidedByName;

    public static DeadlineExtensionResponse from(DeadlineExtensionRequest r) {
        return new DeadlineExtensionResponse(r);
    }

    private DeadlineExtensionResponse(DeadlineExtensionRequest r) {
        this.id = r.getId();
        this.thesisId = r.getThesis().getId();
        this.reason = r.getReason();
        this.requestedDays = r.getRequestedDays();
        this.status = r.getStatus();
        this.decisionReason = r.getDecisionReason();
        this.previousDeadline = r.getPreviousDeadline();
        this.newDeadline = r.getNewDeadline();
        this.createdAt = r.getCreatedAt();
        this.decidedAt = r.getDecidedAt();
        this.requestedById = r.getRequestedBy() != null ? r.getRequestedBy().getId() : null;
        this.requestedByName = r.getRequestedBy() != null ? r.getRequestedBy().getFullName() : null;
        this.decidedById = r.getDecidedBy() != null ? r.getDecidedBy().getId() : null;
        this.decidedByName = r.getDecidedBy() != null ? r.getDecidedBy().getFullName() : null;
    }
}
