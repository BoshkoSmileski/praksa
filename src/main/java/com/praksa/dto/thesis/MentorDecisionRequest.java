package com.praksa.dto.thesis;

import com.praksa.model.enums.MentorDecision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Mentor's decision on a thesis proposal.
 *
 * Comment rules (enforced in the service):
 *   - REQUEST_CHANGES → comment is REQUIRED (student needs to know what to change)
 *   - REJECT          → comment optional but encouraged
 *   - ACCEPT          → comment optional
 */
@Getter
@Setter
public class MentorDecisionRequest {

    @NotNull(message = "Decision is required")
    private MentorDecision decision;

    @Size(max = 2000, message = "Comment cannot exceed 2000 characters")
    private String mentorComment;
}
