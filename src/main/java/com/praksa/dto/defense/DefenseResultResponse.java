package com.praksa.dto.defense;

import com.praksa.model.DefenseResult;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
public class DefenseResultResponse {

    private final UUID id;
    private final UUID defenseId;
    private final UUID thesisId;
    private final int grade;
    private final String notes;
    private final UUID recordedById;
    private final String recordedByName;
    private final OffsetDateTime recordedAt;

    public static DefenseResultResponse from(DefenseResult r) {
        return new DefenseResultResponse(r);
    }

    private DefenseResultResponse(DefenseResult r) {
        this.id = r.getId();
        this.defenseId = r.getDefense().getId();
        this.thesisId = r.getDefense().getThesis().getId();
        this.grade = r.getGrade();
        this.notes = r.getNotes();
        this.recordedById = r.getRecordedBy() != null ? r.getRecordedBy().getId() : null;
        this.recordedByName = r.getRecordedBy() != null ? r.getRecordedBy().getFullName() : null;
        this.recordedAt = r.getRecordedAt();
    }
}
