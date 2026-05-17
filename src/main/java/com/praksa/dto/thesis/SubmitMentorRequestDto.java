package com.praksa.dto.thesis;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class SubmitMentorRequestDto {

    @NotNull(message = "Mentor ID is required")
    private UUID mentorId;

    // Optional comment to the mentor about the topic idea
    private String studentComment;
}
