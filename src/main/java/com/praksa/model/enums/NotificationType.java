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
    MENTOR_REJECTED_TOPIC("Topic Rejected", "Your mentor has rejected your thesis topic. You may choose a different topic or mentor."),

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
