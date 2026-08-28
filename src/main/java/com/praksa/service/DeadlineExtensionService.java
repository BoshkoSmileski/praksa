package com.praksa.service;

import com.praksa.dto.thesis.DeadlineExtensionCreateRequest;
import com.praksa.dto.thesis.DeadlineExtensionDecisionRequest;
import com.praksa.dto.thesis.DeadlineExtensionResponse;

import java.util.List;
import java.util.UUID;

/**
 * Official faculty procedure: a student may request an extension of the thesis's defense
 * deadline ({@code Thesis.defenseDeadline}), for a maximum of 15 additional days, with a
 * written explanation. See {@code DeadlineExtensionServiceImpl} for the full design rationale
 * and CLAUDE.md for the dated handoff entry.
 */
public interface DeadlineExtensionService {

    // STUDENT (thesis owner) submits a request with a reason and the number of additional days
    // (1-15). Creates a PENDING row — never mutates the deadline directly. Requires the thesis
    // to already have a defenseDeadline set (i.e. defense eligibility has been verified) and
    // that no PENDING request or previously-APPROVED extension already exists for this thesis.
    DeadlineExtensionResponse submitDeadlineExtensionRequest(UUID thesisId, DeadlineExtensionCreateRequest request);

    // STUDENT_SERVICE approves or rejects the thesis's current PENDING request.
    // Approval extends Thesis.defenseDeadline by EXACTLY the requested number of days and
    // never changes the thesis's workflow status. Rejection requires a reason and leaves the
    // deadline untouched.
    DeadlineExtensionResponse decideDeadlineExtensionRequest(UUID thesisId, DeadlineExtensionDecisionRequest request);

    // Full history of extension requests for this thesis (PENDING/APPROVED/REJECTED), newest
    // first. Thesis-scoped read access — same policy as the other thesis-level reads.
    List<DeadlineExtensionResponse> getDeadlineExtensionRequests(UUID thesisId);
}
