package com.praksa.dto.committee;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class ProposeCommitteeRequest {

    // Mentor proposes 2 additional professors (3-member committee) or 3 additional professors
    // (4-member committee — official faculty procedure allows one external non-voting member).
    // The mentor themselves are added automatically by the service and is never included here.
    @NotNull(message = "Professor list is required")
    @Size(min = 2, max = 3, message = "You must propose 2 professors (3-member committee) or "
            + "3 professors (4-member committee with one external non-voting member)")
    private List<UUID> professorIds;

    // Only meaningful when professorIds has 3 entries: marks exactly ONE of them as the
    // external non-voting member (must be null for a 2-professor / 3-member proposal, and
    // must be one of professorIds when set). This flag alone is never trusted as
    // authorization — CommitteeServiceImpl validates it against the actual proposed
    // professor list server-side before any CommitteeMember row is created.
    private UUID externalProfessorId;
}
