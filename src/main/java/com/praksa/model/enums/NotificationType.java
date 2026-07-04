package com.praksa.model.enums;

import lombok.Getter;

@Getter
public enum NotificationType {

    // Eligibility
    ELIGIBILITY_APPROVED("Eligibility Approved", "Your eligibility check has been approved. You can now select a topic and mentor."),
    ELIGIBILITY_REJECTED("Eligibility Rejected", "Your eligibility check was rejected. Please contact the student service."),

    // Mentor workflow
    MENTOR_REQUEST_RECEIVED("New Thesis Topic Request", "A student has sent you a thesis topic request. Please review it."),
    MENTOR_ACCEPTED_TOPIC("Topic Accepted", "Your mentor has accepted your thesis topic. Please submit the formal application."),
    MENTOR_REQUESTED_CHANGES("Mentor Requested Changes", "Your mentor has requested changes to your proposal. Review the feedback and resubmit."),
    MENTOR_REJECTED_TOPIC("Topic Rejected", "Your mentor has rejected your thesis topic. You may choose a different topic or mentor."),
    STUDENT_RESUBMITTED_PROPOSAL("Student Resubmitted Proposal", "A student has revised and resubmitted their thesis proposal. Please review it."),

    // Application validation (two-stage: Archive then Student Service)
    APPLICATION_PENDING_ARCHIVE("Thesis Application Awaiting Archive Validation", "A thesis application is awaiting your validation."),
    APPLICATION_REJECTED_BY_ARCHIVE("Application Rejected by Archive", "Your thesis application was rejected by the Archive. Please review the comments and resubmit."),
    APPLICATION_PENDING_SERVICE("Thesis Application Awaiting Student Service Validation", "A thesis application is awaiting your validation."),
    APPLICATION_REJECTED_BY_SERVICE("Application Rejected by Student Service", "Your thesis application was rejected by Student Service. Please review the comments and resubmit."),

    // Application and progress
    APPLICATION_VALIDATED("Application Validated", "Your thesis application has been validated. You can now begin working on your thesis."),
    FINAL_VERSION_SUBMITTED("Final Version Submitted", "A student has submitted their final thesis version. Please review it."),
    MENTOR_APPROVED_THESIS("Thesis Approved by Mentor", "Your thesis has been approved by your mentor and sent to the committee."),

    // Committee
    COMMITTEE_FORMED("Committee Formed", "You have been assigned to a thesis defense committee."),
    COMMITTEE_REVIEW_ACCEPTED("Committee Review Accepted", "The committee review period is complete. Defense scheduling can proceed."),

    // Defense
    DEFENSE_SCHEDULED("Defense Scheduled", "Your thesis defense has been scheduled. Check the details and prepare your documents."),
    DEFENSE_CANCELLED("Defense Cancelled", "Your thesis defense has been cancelled. A new date will be scheduled."),
    DEFENSE_REMINDER("Defense Reminder — Tomorrow", "Reminder: your thesis defense is scheduled within 24 hours."),
    COMMITTEE_REVIEW_AUTO_ADVANCED("Committee Review Period Expired", "The committee did not respond within 5 business days. The review has been automatically marked as accepted."),

    // Final result
    THESIS_GRADED("Thesis Graded", "Your thesis defense grade has been recorded."),
    THESIS_ARCHIVED("Thesis Archived", "Congratulations! Your thesis has been successfully defended and archived.");

    // Subject line and default body used when sending the email
    private final String subject;
    private final String defaultBody;

    NotificationType(String subject, String defaultBody) {
        this.subject = subject;
        this.defaultBody = defaultBody;
    }
}
