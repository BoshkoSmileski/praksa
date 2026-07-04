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

    // Read operations
    ThesisResponse getThesisById(UUID thesisId);
    List<ThesisResponse> getMyTheses();
    List<ThesisStatusHistoryResponse> getStatusHistory(UUID thesisId);

    // Archive search — exact match on the registration number.
    // Throws ResourceNotFoundException if no thesis carries that number.
    ThesisResponse findByRegistrationNumber(String registrationNumber);

    // Returns a Resource for the generated application PDF.
    // Throws if the PDF doesn't exist or the caller doesn't have read access.
    org.springframework.core.io.Resource downloadApplicationPdf(UUID thesisId);
}
