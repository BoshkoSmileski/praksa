package com.praksa.controller;

import com.praksa.dto.ApiResponse;
import com.praksa.dto.thesis.*;
import com.praksa.service.ThesisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/theses")
@RequiredArgsConstructor
@Tag(name = "Thesis", description = "Core thesis workflow — from eligibility check through archiving")
public class ThesisController {

    private final ThesisService thesisService;

    @Operation(
        summary = "Create thesis (Step 1)",
        description = "Student submits a new thesis title and requests an eligibility check. " +
                      "Requires role: STUDENT. Student must have no other active thesis."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Thesis created, status = PENDING_ELIGIBILITY_CHECK"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Student already has an active thesis"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a student")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<ThesisResponse>> createThesis(
            @Valid @RequestBody CreateThesisRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(thesisService.createThesis(request)));
    }

    @Operation(
        summary = "Get my theses",
        description = "Returns theses relevant to the caller: student sees their own, " +
                      "mentor sees assigned theses, admin/archive/committee see all."
    )
    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<ThesisResponse>>> getMyTheses() {
        return ResponseEntity.ok(ApiResponse.ok(thesisService.getMyTheses()));
    }

    @Operation(summary = "Get thesis by ID")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ThesisResponse>> getThesisById(
            @Parameter(description = "Thesis UUID") @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(thesisService.getThesisById(id)));
    }

    @Operation(
        summary = "Get status history",
        description = "Returns the full audit trail of status transitions for a thesis, oldest first."
    )
    @GetMapping("/{id}/history")
    public ResponseEntity<ApiResponse<List<ThesisStatusHistoryResponse>>> getHistory(
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(thesisService.getStatusHistory(id)));
    }

    @Operation(
        summary = "Decide eligibility (Step 1 result)",
        description = "Admin approves or rejects the eligibility check. " +
                      "Approved → TOPIC_SELECTION. Rejected → ELIGIBILITY_REJECTED. Requires role: ADMIN."
    )
    @PatchMapping("/{id}/eligibility")
    public ResponseEntity<ApiResponse<ThesisResponse>> decideEligibility(
            @PathVariable UUID id,
            @Valid @RequestBody EligibilityDecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(thesisService.decideEligibility(id, request)));
    }

    @Operation(
        summary = "Submit mentor request (Step 2)",
        description = "Student picks a mentor and sends a topic request. " +
                      "Allowed when status is TOPIC_SELECTION or MENTOR_REJECTED_TOPIC. " +
                      "Mentor must have fewer than 10 active theses. Requires role: STUDENT."
    )
    @PatchMapping("/{id}/mentor-request")
    public ResponseEntity<ApiResponse<ThesisResponse>> submitMentorRequest(
            @PathVariable UUID id,
            @Valid @RequestBody SubmitMentorRequestDto request) {
        return ResponseEntity.ok(ApiResponse.ok(thesisService.submitMentorRequest(id, request)));
    }

    @Operation(
        summary = "Decide mentor request (Step 2 result)",
        description = "Mentor accepts or rejects the topic. " +
                      "Accepted → APPLICATION_SUBMITTED. Rejected → MENTOR_REJECTED_TOPIC (mentor cleared). " +
                      "Requires role: MENTOR and must be the assigned mentor."
    )
    @PatchMapping("/{id}/mentor-decision")
    public ResponseEntity<ApiResponse<ThesisResponse>> decideMentorRequest(
            @PathVariable UUID id,
            @Valid @RequestBody MentorDecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(thesisService.decideMentorRequest(id, request)));
    }

    @Operation(
        summary = "Submit formal application (Step 3)",
        description = "Student submits the formal application form → ADMINISTRATIVE_VALIDATION. " +
                      "Requires role: STUDENT and thesis ownership."
    )
    @PatchMapping("/{id}/submit-application")
    public ResponseEntity<ApiResponse<ThesisResponse>> submitApplication(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(thesisService.submitApplication(id)));
    }

    @Operation(
        summary = "Validate application (Step 4)",
        description = "Admin validates the documentation → IN_PROGRESS. Requires role: ADMIN."
    )
    @PatchMapping("/{id}/validate")
    public ResponseEntity<ApiResponse<ThesisResponse>> validateApplication(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(thesisService.validateApplication(id)));
    }

    @Operation(
        summary = "Approve final thesis (Steps 5–7)",
        description = "Mentor approves the final submitted version → MENTOR_APPROVED. " +
                      "Requires role: MENTOR and must be the assigned mentor."
    )
    @PatchMapping("/{id}/approve-final")
    public ResponseEntity<ApiResponse<ThesisResponse>> approveFinalThesis(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(thesisService.approveFinalThesis(id)));
    }
}
