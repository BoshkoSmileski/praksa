package com.praksa.dto.thesis;

import com.praksa.model.ThesisStatusHistory;
import com.praksa.model.enums.ThesisStatus;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
public class ThesisStatusHistoryResponse {

    private final UUID id;
    private final ThesisStatus oldStatus;
    private final ThesisStatus newStatus;
    private final UUID changedById;
    private final String changedByName;
    private final OffsetDateTime changedAt;

    public static ThesisStatusHistoryResponse from(ThesisStatusHistory h) {
        return new ThesisStatusHistoryResponse(h);
    }

    private ThesisStatusHistoryResponse(ThesisStatusHistory h) {
        this.id = h.getId();
        this.oldStatus = h.getOldStatus();
        this.newStatus = h.getNewStatus();
        this.changedById = h.getChangedBy() != null ? h.getChangedBy().getId() : null;
        this.changedByName = h.getChangedBy() != null ? h.getChangedBy().getFullName() : null;
        this.changedAt = h.getChangedAt();
    }
}
