package com.praksa.dto.defense;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
public class ScheduleDefenseRequest {

    @NotBlank(message = "Room is required")
    private String room;

    @NotNull(message = "Defense date and time are required")
    @Future(message = "Defense must be scheduled in the future")
    private OffsetDateTime scheduledAt;
}
