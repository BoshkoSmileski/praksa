package com.praksa.model.enums;

public enum ThesisStatus {
    // Step 1: Eligibility check
    PENDING_ELIGIBILITY_CHECK,
    ELIGIBILITY_REJECTED,

    // Step 2: Topic and mentor selection
    TOPIC_SELECTION,
    PENDING_MENTOR_APPROVAL,
    MENTOR_REQUESTED_CHANGES,
    MENTOR_REJECTED_TOPIC,

    // Step 3-4: Application submission → archive validation → student service validation
    APPLICATION_SUBMITTED,
    PENDING_ARCHIVE_VALIDATION,
    APPLICATION_REJECTED_BY_ARCHIVE,
    PENDING_SERVICE_VALIDATION,
    APPLICATION_REJECTED_BY_SERVICE,

    // Step 5-6: Working and final submission
    IN_PROGRESS,
    FINAL_SUBMITTED,

    // Step 7: Mentor approval
    MENTOR_APPROVED,

    // Step 8: Committee review
    COMMITTEE_REVIEW,
    COMMITTEE_ACCEPTED,

    // Step 9-11: Defense
    PENDING_DEFENSE_CHECK,
    DEFENSE_SCHEDULED,

    // Step 12: Archived
    ARCHIVED
}
