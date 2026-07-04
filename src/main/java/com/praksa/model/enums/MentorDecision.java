package com.praksa.model.enums;

/**
 * Outcome of a mentor reviewing a thesis proposal.
 *
 *   ACCEPT          — mentor agrees, thesis advances to APPLICATION_SUBMITTED
 *   REJECT          — mentor declines entirely, mentor is cleared, student picks another
 *   REQUEST_CHANGES — mentor remains assigned, student must revise title/idea and resubmit
 */
public enum MentorDecision {
    ACCEPT,
    REJECT,
    REQUEST_CHANGES
}
