package com.praksa.controller;

import com.praksa.dto.ApiResponse;
import com.praksa.dto.committee.CommitteeMemberResponse;
import com.praksa.dto.committee.ProposeCommitteeRequest;
import com.praksa.dto.committee.SubmitReviewNotesRequest;
import com.praksa.service.CommitteeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/theses/{thesisId}/committee")
@RequiredArgsConstructor
public class CommitteeController {

    private final CommitteeService committeeService;

    // GET /api/theses/{thesisId}/committee
    @GetMapping
    public ResponseEntity<ApiResponse<List<CommitteeMemberResponse>>> getCommittee(
            @PathVariable UUID thesisId) {
        return ResponseEntity.ok(ApiResponse.ok(committeeService.getCommittee(thesisId)));
    }

    // POST /api/theses/{thesisId}/committee/propose
    @PostMapping("/propose")
    public ResponseEntity<ApiResponse<List<CommitteeMemberResponse>>> proposeCommittee(
            @PathVariable UUID thesisId,
            @Valid @RequestBody ProposeCommitteeRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(committeeService.proposeCommittee(thesisId, request)));
    }

    // POST /api/theses/{thesisId}/committee/approve
    @PostMapping("/approve")
    public ResponseEntity<ApiResponse<List<CommitteeMemberResponse>>> approveCommittee(
            @PathVariable UUID thesisId) {
        return ResponseEntity.ok(ApiResponse.ok(committeeService.approveCommittee(thesisId)));
    }

    // PATCH /api/theses/{thesisId}/committee/{memberId}/review
    @PatchMapping("/{memberId}/review")
    public ResponseEntity<ApiResponse<CommitteeMemberResponse>> submitReview(
            @PathVariable UUID thesisId,
            @PathVariable UUID memberId,
            @Valid @RequestBody SubmitReviewNotesRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                committeeService.submitReviewNotes(thesisId, memberId, request)));
    }

    // POST /api/theses/{thesisId}/committee/accept-review
    @PostMapping("/accept-review")
    public ResponseEntity<ApiResponse<Void>> acceptReview(@PathVariable UUID thesisId) {
        committeeService.acceptCommitteeReview(thesisId);
        return ResponseEntity.ok(ApiResponse.ok("Committee review accepted", null));
    }
}
