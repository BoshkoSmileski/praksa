package com.praksa.controller;

import com.praksa.dto.ApiResponse;
import com.praksa.dto.defense.DefenseResponse;
import com.praksa.dto.defense.ScheduleDefenseRequest;
import com.praksa.dto.thesis.ThesisResponse;
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
    // STUDENT asks to defend. Thesis → PENDING_DEFENSE_SCHEDULING. No room/date yet.
    @PostMapping("/request")
    public ResponseEntity<ApiResponse<ThesisResponse>> request(@PathVariable UUID thesisId) {
        return ResponseEntity.ok(ApiResponse.ok(defenseService.requestDefense(thesisId)));
    }

    // POST /api/theses/{thesisId}/defenses
    // STUDENT_SERVICE schedules (or reschedules) the defense with room + date/time.
    @PostMapping
    public ResponseEntity<ApiResponse<DefenseResponse>> schedule(
            @PathVariable UUID thesisId,
            @Valid @RequestBody ScheduleDefenseRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(defenseService.scheduleDefense(thesisId, request)));
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
