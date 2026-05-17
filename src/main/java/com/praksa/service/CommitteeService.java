package com.praksa.service;

import com.praksa.dto.committee.CommitteeMemberResponse;
import com.praksa.dto.committee.ProposeCommitteeRequest;
import com.praksa.dto.committee.SubmitReviewNotesRequest;

import java.util.List;
import java.util.UUID;

public interface CommitteeService {

    // Step 8a — Mentor proposes 2 professors; mentor auto-added as third member
    List<CommitteeMemberResponse> proposeCommittee(UUID thesisId, ProposeCommitteeRequest request);

    // Step 8b — Admin officially approves and forms the committee → COMMITTEE_REVIEW
    List<CommitteeMemberResponse> approveCommittee(UUID thesisId);

    // Step 8c — A committee member submits their review notes (optional text)
    CommitteeMemberResponse submitReviewNotes(UUID thesisId, UUID memberId, SubmitReviewNotesRequest request);

    // Step 8d — Admin advances the thesis after the review period → COMMITTEE_ACCEPTED
    void acceptCommitteeReview(UUID thesisId);

    // Read
    List<CommitteeMemberResponse> getCommittee(UUID thesisId);
}
