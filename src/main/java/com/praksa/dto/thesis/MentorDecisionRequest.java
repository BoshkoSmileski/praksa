package com.praksa.dto.thesis;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MentorDecisionRequest {

    @NotNull(message = "Decision is required")
    private Boolean accepted;

    // Mentor can leave a comment regardless of their decision
    private String mentorComment;
}
