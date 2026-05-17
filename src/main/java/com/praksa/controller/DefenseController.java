package com.praksa.controller;

import com.praksa.dto.ApiResponse;
import com.praksa.dto.defense.DefenseResponse;
import com.praksa.dto.defense.ScheduleDefenseRequest;
import com.praksa.service.DefenseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/theses/{thesisId}/defenses")
@RequiredArgsConstructor
public class DefenseController {

    private final DefenseService defenseService;

    // POST /api/theses/{thesisId}/defenses
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
}
