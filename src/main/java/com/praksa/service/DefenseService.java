package com.praksa.service;

import com.praksa.dto.defense.DefenseRequestCreateRequest;
import com.praksa.dto.defense.DefenseRequestDecisionRequest;
import com.praksa.dto.defense.DefenseRequestResponse;
import com.praksa.dto.defense.DefenseResponse;

import java.util.List;
import java.util.UUID;

public interface DefenseService {

    // STUDENT proposes the actual room + date/time for their defense. Stored as a PENDING
    // DefenseRequest — this does NOT create a Defense row and does NOT change the thesis
    // status. Allowed once STUDENT_SERVICE has verified defense eligibility (Item #8), i.e.
    // when the thesis is PENDING_DEFENSE_SCHEDULING, or again after a previously scheduled
    // defense was cancelled (DEFENSE_SCHEDULED with no active Defense).
    DefenseRequestResponse createDefenseRequest(UUID thesisId, DefenseRequestCreateRequest request);

    // STUDENT_SERVICE approves or rejects the thesis's current PENDING DefenseRequest.
    // Approval re-validates the date window and room availability against the CURRENT
    // database state, creates exactly one Defense, and transitions the thesis to
    // DEFENSE_SCHEDULED. Rejection (or a conflict discovered at approval time) marks the
    // request REJECTED with a reason and creates no Defense.
    DefenseRequestResponse decideDefenseRequest(UUID thesisId, DefenseRequestDecisionRequest request);

    // Full history of proposals for this thesis (PENDING/APPROVED/REJECTED), newest first.
    // Thesis-scoped read access — same policy as the other thesis-level reads.
    List<DefenseRequestResponse> getDefenseRequests(UUID thesisId);

    // Student or mentor cancels — workflow does NOT go backwards
    DefenseResponse cancelDefense(UUID thesisId);

    // Returns the currently active (non-cancelled) defense
    DefenseResponse getActiveDefense(UUID thesisId);

    // Returns full history including cancelled ones
    List<DefenseResponse> getAllDefenses(UUID thesisId);
}
