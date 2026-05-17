package com.praksa.controller;

import com.praksa.dto.ApiResponse;
import com.praksa.dto.defense.DefenseResultResponse;
import com.praksa.dto.defense.RecordResultRequest;
import com.praksa.service.DefenseResultService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/theses/{thesisId}/defenses/{defenseId}/result")
@RequiredArgsConstructor
public class DefenseResultController {

    private final DefenseResultService resultService;

    // POST /api/theses/{thesisId}/defenses/{defenseId}/result
    @PostMapping
    public ResponseEntity<ApiResponse<DefenseResultResponse>> recordResult(
            @PathVariable UUID thesisId,
            @PathVariable UUID defenseId,
            @Valid @RequestBody RecordResultRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                resultService.recordResult(thesisId, defenseId, request)));
    }

    // GET /api/theses/{thesisId}/defenses/{defenseId}/result
    @GetMapping
    public ResponseEntity<ApiResponse<DefenseResultResponse>> getResult(
            @PathVariable UUID thesisId,
            @PathVariable UUID defenseId) {
        return ResponseEntity.ok(ApiResponse.ok(
                resultService.getResult(thesisId, defenseId)));
    }
}
