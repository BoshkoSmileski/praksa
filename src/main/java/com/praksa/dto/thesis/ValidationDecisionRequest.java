package com.praksa.dto.thesis;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Used by Archive and Student Service when validating a thesis application.
 *
 * - approved=true  → optional comment (may be left blank)
 * - approved=false → comment is required and stored as the rejection reason
 *
 * The "comment is required when rejecting" rule is enforced in the service layer
 * because @AssertTrue cross-field validation is ugly with Bean Validation.
 */
@Getter
@Setter
public class ValidationDecisionRequest {

    @NotNull(message = "Decision is required")
    private Boolean approved;

    @Size(max = 2000, message = "Comment cannot exceed 2000 characters")
    private String comment;
}
