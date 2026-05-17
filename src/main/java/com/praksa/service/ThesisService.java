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

    // Step 2 — Mentor accepts or rejects the topic
    ThesisResponse decideMentorRequest(UUID thesisId, MentorDecisionRequest request);

    // Step 3 — Student submits the formal application form
    ThesisResponse submitApplication(UUID thesisId);

    // Step 4 — Admin validates the application (archive + student service)
    ThesisResponse validateApplication(UUID thesisId);

    // Steps 5–6 — Mentor approves the final version of the thesis
    ThesisResponse approveFinalThesis(UUID thesisId);

    // Read operations
    ThesisResponse getThesisById(UUID thesisId);
    List<ThesisResponse> getMyTheses();
    List<ThesisStatusHistoryResponse> getStatusHistory(UUID thesisId);
}
