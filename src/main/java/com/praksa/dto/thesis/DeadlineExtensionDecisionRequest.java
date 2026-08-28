package com.praksa.dto.thesis;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * STUDENT_SERVICE decision on a PENDING DeadlineExtensionRequest.
 *
 * <p>{@code reason} is optional in this DTO because it is only REQUIRED when approved=false —
 * a cross-field rule enforced in the service layer (same pattern as
 * {@code DefenseRequestDecisionRequest} and the archive/service validation rejection-comment
 * requirement elsewhere in ThesisServiceImpl).
 */
@Getter
@Setter
public class DeadlineExtensionDecisionRequest {

    @NotNull(message = "approved is required")
    private Boolean approved;

    private String reason;
}
