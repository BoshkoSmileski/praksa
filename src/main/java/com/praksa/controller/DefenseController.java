package com.praksa.controller;

import com.praksa.dto.ApiResponse;
import com.praksa.dto.defense.DefenseRequestCreateRequest;
import com.praksa.dto.defense.DefenseRequestDecisionRequest;
import com.praksa.dto.defense.DefenseRequestResponse;
import com.praksa.dto.defense.DefenseResponse;
import com.praksa.service.DefenseResultService;
import com.praksa.service.DefenseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/theses/{thesisId}/defenses")
@RequiredArgsConstructor
public class DefenseController {

    private final DefenseService defenseService;
    private final DefenseResultService resultService;

    // POST /api/theses/{thesisId}/defenses/request
    // STUDENT proposes the room + date/time they want to defend at. Stored as a PENDING
    // DefenseRequest — no Defense row yet, no thesis status change.
    @PostMapping("/request")
    public ResponseEntity<ApiResponse<DefenseRequestResponse>> createRequest(
            @PathVariable UUID thesisId,
            @Valid @RequestBody DefenseRequestCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(defenseService.createDefenseRequest(thesisId, request)));
    }

    // PATCH /api/theses/{thesisId}/defenses/request/decision
    // STUDENT_SERVICE approves or rejects the thesis's current PENDING request.
    @PatchMapping("/request/decision")
    public ResponseEntity<ApiResponse<DefenseRequestResponse>> decideRequest(
            @PathVariable UUID thesisId,
            @Valid @RequestBody DefenseRequestDecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(defenseService.decideDefenseRequest(thesisId, request)));
    }

    // GET /api/theses/{thesisId}/defenses/request
    // Full proposal history for this thesis (current + past rejected/approved), thesis-scoped.
    @GetMapping("/request")
    public ResponseEntity<ApiResponse<List<DefenseRequestResponse>>> getRequests(@PathVariable UUID thesisId) {
        return ResponseEntity.ok(ApiResponse.ok(defenseService.getDefenseRequests(thesisId)));
    }

    // GET /api/theses/{thesisId}/defenses/active
    @GetMapping("/active")
    public ResponseEntity<ApiResponse<DefenseResponse>> getActive(@PathVariable UUID thesisId) {
        return ResponseEntity.ok(ApiResponse.ok(defenseService.getActiveDefense(thesisId)));
    }

    // GET /api/theses/{thesisId}/defenses
    @GetMapping
    public ResponseEntity<ApiResponse<List<DefenseResponse>>> getAll(@PathVariable UUID thesisId) {
        return ResponseEntity.ok(ApiResponse.ok(defenseService.getAllDefenses(thesisId)));
    }

    // PATCH /api/theses/{thesisId}/defenses/cancel
    @PatchMapping("/cancel")
    public ResponseEntity<ApiResponse<DefenseResponse>> cancel(@PathVariable UUID thesisId) {
        return ResponseEntity.ok(ApiResponse.ok(defenseService.cancelDefense(thesisId)));
    }

    // GET /api/theses/{thesisId}/defenses/{defenseId}/record-pdf
    // Official defense record ("записник за одбрана") as a downloadable PDF. Available only
    // once the defense has been graded; access is authorized server-side (student owner,
    // assigned mentor, committee members, STUDENT_SERVICE, ARCHIVE).
    @GetMapping("/{defenseId}/record-pdf")
    public ResponseEntity<byte[]> downloadRecordPdf(
            @PathVariable UUID thesisId,
            @PathVariable UUID defenseId) {
        byte[] pdf = resultService.generateRecordPdf(thesisId, defenseId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"zapisnik-odbrana-" + defenseId + ".pdf\"")
                .body(pdf);
    }
}
