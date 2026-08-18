package com.praksa.dto.thesis;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Roadmap Item #8 — explicit defense-condition verification.
 *
 * Student Service uses this to confirm the student has fulfilled the required
 * defense conditions before a defense can be scheduled. For this project the
 * checks are MANUAL boolean confirmations — there is deliberately no integration
 * with an external examination / student-information system.
 *
 * BOTH must be true for the verification to succeed:
 *   - examsCompleted        → required exams / credits / academic requirements are fulfilled
 *   - documentationComplete → required documentation is complete
 *
 * @NotNull only guarantees the caller sent an explicit boolean; the "both must be
 * true" business rule is enforced in the service layer (so the thesis status is
 * never advanced on a partial confirmation).
 */
@Getter
@Setter
public class DefenseEligibilityRequest {

    @NotNull(message = "examsCompleted is required")
    private Boolean examsCompleted;

    @NotNull(message = "documentationComplete is required")
    private Boolean documentationComplete;
}
