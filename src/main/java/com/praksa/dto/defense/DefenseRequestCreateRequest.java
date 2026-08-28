package com.praksa.dto.defense;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * STUDENT-submitted proposal: the room and date/time they want to defend at.
 *
 * Presence is validated here (bean validation); the business-accurate 5-15 day window is
 * validated in the service (DefenseServiceImpl), which is the single source of truth for
 * that rule and is re-run at approval time against the ORIGINAL creation timestamp.
 */
@Getter
@Setter
public class DefenseRequestCreateRequest {

    @NotBlank(message = "Room is required")
    private String room;

    @NotNull(message = "Defense date and time are required")
    private OffsetDateTime scheduledAt;
}
