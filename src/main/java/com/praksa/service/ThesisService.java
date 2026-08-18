package com.praksa.service;

import com.praksa.dto.thesis.*;

import java.util.List;
import java.util.UUID;

public interface ThesisService {

    // Step 1 — Student requests eligibility check
    ThesisResponse createThesis(CreateThesisRequest request);

    // Step 1 — Admin/student-service approves or rejects eligibility
    ThesisResponse decideEligibility(UUID thesisId, EligibilityDecisionRequest request);

    // Step 2 — Student picks a mentor and sends request
    ThesisResponse submitMentorRequest(UUID thesisId, SubmitMentorRequestDto request);

    // Step 2 — Mentor accepts, rejects, or requests changes
    ThesisResponse decideMentorRequest(UUID thesisId, MentorDecisionRequest request);

    // Step 2 (after REQUEST_CHANGES) — Student revises title/idea and resubmits to same mentor
    ThesisResponse reviseProposal(UUID thesisId, ReviseProposalRequest request);

    // Step 3 — Student submits (or resubmits after rejection) the formal application
    ThesisResponse submitApplication(UUID thesisId);

    // Step 4a — Archive validates documentation (approve or reject)
    ThesisResponse archiveValidate(UUID thesisId, ValidationDecisionRequest request);

    // Step 4b — Student Service validates documentation (approve or reject)
    ThesisResponse serviceValidate(UUID thesisId, ValidationDecisionRequest request);

    // Steps 5–6 — Mentor approves the final version of the thesis
    ThesisResponse approveFinalThesis(UUID thesisId);

    // P2.2 — Archive adds/edits the free-text archive notes on an ARCHIVED thesis.
    // Restricted to the ARCHIVE role. Never changes the thesis status and never emits
    // a notification — it only annotates the archive record. A null/blank note clears it.
    ThesisResponse updateArchiveNotes(UUID thesisId, ArchiveNotesRequest request);

    // Item #8 — Student Service explicitly verifies the student has fulfilled the
    // defense conditions (exams completed + documentation complete). Both must be true.
    // On success: PENDING_DEFENSE_CHECK → PENDING_DEFENSE_SCHEDULING (the state from
    // which the student may then request the defense). Restricted to STUDENT_SERVICE.
    ThesisResponse verifyDefenseEligibility(UUID thesisId, DefenseEligibilityRequest request);

    // Read operations
    ThesisResponse getThesisById(UUID thesisId);
    List<ThesisResponse> getMyTheses();
    List<ThesisStatusHistoryResponse> getStatusHistory(UUID thesisId);

    // Committee page — theses relevant to the caller's committee involvement.
    //   MENTOR:          theses where they hold a committee seat (incl. their own as MENTOR_MEMBER)
    //   STUDENT_SERVICE: theses currently in a committee-related status
    //   COMMITTEE:       theses in DEFENSE_SCHEDULED (their grading scope)
    List<ThesisResponse> getCommitteeTheses();

    // Defenses page — theses that have (or are ready for) a defense, scoped by role.
    //   STUDENT:         own theses in defense-related statuses
    //   MENTOR:          assigned theses in defense-related statuses
    //   COMMITTEE:       theses in DEFENSE_SCHEDULED
    //   STUDENT_SERVICE: all theses in defense-related statuses
    List<ThesisResponse> getDefenseTheses();

    // Archive search — exact match on the registration number.
    // Throws ResourceNotFoundException if no thesis carries that number.
    ThesisResponse findByRegistrationNumber(String registrationNumber);

    // Returns a Resource for the generated application PDF.
    // Throws if the PDF doesn't exist or the caller doesn't have read access.
    org.springframework.core.io.Resource downloadApplicationPdf(UUID thesisId);
}
