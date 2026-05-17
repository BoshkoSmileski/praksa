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

    // Mentor proposes exactly 2 additional professors.
    // The mentor themselves are added automatically by the service.
    // We enforce size=2 here with @Size so the error reaches the client clearly.
    @NotNull(message = "Professor list is required")
    @Size(min = 2, max = 2, message = "You must propose exactly 2 professors")
    private List<UUID> professorIds;
}
