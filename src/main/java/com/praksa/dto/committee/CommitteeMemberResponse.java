package com.praksa.dto.committee;

import com.praksa.model.CommitteeMember;
import com.praksa.model.enums.MemberRole;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
public class CommitteeMemberResponse {

    private final UUID id;
    private final UUID thesisId;
    private final UUID professorId;
    private final String professorName;
    private final MemberRole memberRole;
    private final UUID proposedById;
    private final String proposedByName;
    private final UUID approvedById;
    private final OffsetDateTime approvedAt;
    private final String notes;

    public static CommitteeMemberResponse from(CommitteeMember m) {
        return new CommitteeMemberResponse(m);
    }

    private CommitteeMemberResponse(CommitteeMember m) {
        this.id = m.getId();
        this.thesisId = m.getThesis().getId();
        this.professorId = m.getProfessor().getId();
        this.professorName = m.getProfessor().getFullName();
        this.memberRole = m.getMemberRole();
        this.proposedById = m.getProposedBy() != null ? m.getProposedBy().getId() : null;
        this.proposedByName = m.getProposedBy() != null ? m.getProposedBy().getFullName() : null;
        this.approvedById = m.getApprovedBy() != null ? m.getApprovedBy().getId() : null;
        this.approvedAt = m.getApprovedAt();
        this.notes = m.getNotes();
    }
}
