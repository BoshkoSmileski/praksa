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

    @Operation(
        summary = "Get theses for the Committee view",
        description = "Returns theses relevant to the caller's committee involvement. " +
                      "MENTOR: theses where they mentor or hold a committee seat, in committee-related statuses. " +
                      "STUDENT_SERVICE: all theses in committee-related statuses. " +
                      "COMMITTEE: theses in DEFENSE_SCHEDULED (their grading scope)."
    )
    @GetMapping("/committee")
    public ResponseEntity<ApiResponse<List<ThesisResponse>>> getCommitteeTheses() {
        return ResponseEntity.ok(ApiResponse.ok(thesisService.getCommitteeTheses()));
    }

    @Operation(
        summary = "Get theses for the Defenses view",
        description = "Returns theses that have (or are ready for) a defense, scoped by role. " +
                      "STUDENT: own theses. MENTOR: assigned theses (incl. those they serve on as committee). " +
                      "COMMITTEE: theses in DEFENSE_SCHEDULED/ARCHIVED. STUDENT_SERVICE: all defense-related theses."
    )
    @GetMapping("/defenses")
    public ResponseEntity<ApiResponse<List<ThesisResponse>>> getDefenseTheses() {
        return ResponseEntity.ok(ApiResponse.ok(thesisService.getDefenseTheses()));
    }

    @Operation(summary = "Get thesis by ID")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ThesisResponse>> getThesisById(
            @Parameter(description = "Thesis UUID") @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(thesisService.getThesisById(id)));
    }

    @Operation(
        summary = "Find archived thesis by registration number",
        description = "Exact lookup by archive registration number (e.g. DT-2026-0001). " +
                      "Useful for Archive users searching the official record."
    )
    @GetMapping("/by-registration-number/{registrationNumber}")
    public ResponseEntity<ApiResponse<ThesisResponse>> findByRegistrationNumber(
            @Parameter(description = "Archive registration number, e.g. DT-2026-0001")
            @PathVariable String registrationNumber) {
        return ResponseEntity.ok(ApiResponse.ok(thesisService.findByRegistrationNumber(registrationNumber)));
    }

    @Operation(
        summary = "Download the thesis application PDF",
        description = "Returns the generated application form as a PDF stream. Authorized against " +
                      "THIS specific thesis: the student owner, the assigned mentor, a committee " +
                      "member seated on this thesis, STUDENT_SERVICE, or ARCHIVE. The bare COMMITTEE " +
                      "role is not sufficient — an unseated COMMITTEE user (or one seated on another " +
                      "thesis) receives 403."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "PDF stream returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not authorized for this thesis"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Thesis not found, or no application PDF generated yet")
    })
    @GetMapping("/{id}/application-pdf")
    public ResponseEntity<org.springframework.core.io.Resource> downloadApplicationPdf(@PathVariable UUID id) {
        org.springframework.core.io.Resource resource = thesisService.downloadApplicationPdf(id);
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"application-" + id + ".pdf\"")
                .body(resource);
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
                      "Approved → TOPIC_SELECTION. Rejected → ELIGIBILITY_REJECTED. Requires role: STUDENT_SERVICE."
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
        description = "Mentor decides: ACCEPT → APPLICATION_SUBMITTED · REJECT → MENTOR_REJECTED_TOPIC (mentor cleared) · " +
                      "REQUEST_CHANGES → MENTOR_REQUESTED_CHANGES (mentor stays, mentorComment is required). " +
                      "Requires role: MENTOR and must be the assigned mentor."
    )
    @PatchMapping("/{id}/mentor-decision")
    public ResponseEntity<ApiResponse<ThesisResponse>> decideMentorRequest(
            @PathVariable UUID id,
            @Valid @RequestBody MentorDecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(thesisService.decideMentorRequest(id, request)));
    }

    @Operation(
        summary = "Revise proposal (Step 2 revision loop)",
        description = "After mentor requests changes, student updates title (and optionally description) and resubmits to the SAME mentor. " +
                      "Status returns to PENDING_MENTOR_APPROVAL. Revision count is preserved on the thesis. " +
                      "Requires role: STUDENT and thesis ownership."
    )
    @PatchMapping("/{id}/revise-proposal")
    public ResponseEntity<ApiResponse<ThesisResponse>> reviseProposal(
            @PathVariable UUID id,
            @Valid @RequestBody ReviseProposalRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(thesisService.reviseProposal(id, request)));
    }

    @Operation(
        summary = "Submit formal application (Step 3)",
        description = "Student submits (or resubmits after rejection) the formal application. " +
                      "Always moves status to PENDING_ARCHIVE_VALIDATION. Requires role: STUDENT and thesis ownership."
    )
    @PatchMapping("/{id}/submit-application")
    public ResponseEntity<ApiResponse<ThesisResponse>> submitApplication(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(thesisService.submitApplication(id)));
    }

    @Operation(
        summary = "Archive validates application (Step 4a)",
        description = "Archive approves or rejects the documentation. " +
                      "Approve → PENDING_SERVICE_VALIDATION. Reject (with mandatory comment) → APPLICATION_REJECTED_BY_ARCHIVE. " +
                      "Requires role: ARCHIVE."
    )
    @PatchMapping("/{id}/archive-validate")
    public ResponseEntity<ApiResponse<ThesisResponse>> archiveValidate(
            @PathVariable UUID id,
            @Valid @RequestBody ValidationDecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(thesisService.archiveValidate(id, request)));
    }

    @Operation(
        summary = "Student Service validates application (Step 4b)",
        description = "Student Service approves or rejects the documentation. " +
                      "Approve → IN_PROGRESS. Reject (with mandatory comment) → APPLICATION_REJECTED_BY_SERVICE. " +
                      "Requires role: STUDENT_SERVICE."
    )
    @PatchMapping("/{id}/service-validate")
    public ResponseEntity<ApiResponse<ThesisResponse>> serviceValidate(
            @PathVariable UUID id,
            @Valid @RequestBody ValidationDecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(thesisService.serviceValidate(id, request)));
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

    @Operation(
        summary = "Update archive notes (P2.2)",
        description = "Archive adds or edits the free-text notes on an already-archived thesis " +
                      "(reuses Thesis.archiveNotes). A null/blank note clears the field. This never " +
                      "changes the thesis status and never sends a notification. Requires role: ARCHIVE, " +
                      "and the thesis must be in ARCHIVED status."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Archive notes updated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Thesis is not ARCHIVED, or notes exceed the length limit"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not ARCHIVE"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Thesis not found")
    })
    @PatchMapping("/{id}/archive-notes")
    public ResponseEntity<ApiResponse<ThesisResponse>> updateArchiveNotes(
            @PathVariable UUID id,
            @Valid @RequestBody ArchiveNotesRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(thesisService.updateArchiveNotes(id, request)));
    }

    @Operation(
        summary = "Verify defense eligibility (Item #8)",
        description = "Student Service explicitly confirms the student has fulfilled the defense conditions " +
                      "(required exams completed + required documentation complete). BOTH must be true. " +
                      "On success PENDING_DEFENSE_CHECK → PENDING_DEFENSE_SCHEDULING, after which the student " +
                      "may request the defense. If either condition is false the request is rejected (400) and " +
                      "the status is unchanged. Requires role: STUDENT_SERVICE."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Eligibility verified, status = PENDING_DEFENSE_SCHEDULING"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A condition is not confirmed, or the thesis is not in PENDING_DEFENSE_CHECK"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not STUDENT_SERVICE")
    })
    @PatchMapping("/{id}/defense-eligibility")
    public ResponseEntity<ApiResponse<ThesisResponse>> verifyDefenseEligibility(
            @PathVariable UUID id,
            @Valid @RequestBody DefenseEligibilityRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(thesisService.verifyDefenseEligibility(id, request)));
    }
}
