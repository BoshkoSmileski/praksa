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
    // Named WITHOUT an "is" prefix so Lombok's boolean getter (isExternalNonVoting()) maps to
    // the unambiguous JSON wire key "externalNonVoting" (verified by
    // CommitteeMemberResponseSerializationTest — never guessed, per the project's documented
    // isSent -> "sent" precedent). true = external professional, non-voting: may appear on the
    // committee and participate in review/defense, but must never be treated as a voter.
    private final boolean externalNonVoting;

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
        this.externalNonVoting = m.isExternalNonVoting();
    }
}
