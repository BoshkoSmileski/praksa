package com.praksa.controller;

import com.praksa.dto.ApiResponse;
import com.praksa.dto.version.AddCommentRequest;
import com.praksa.dto.version.ThesisCommentResponse;
import com.praksa.dto.version.ThesisVersionResponse;
import com.praksa.service.ThesisVersionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/theses/{thesisId}/versions")
@RequiredArgsConstructor
public class ThesisVersionController {

    private final ThesisVersionService versionService;

    // POST /api/theses/{thesisId}/versions
    // multipart/form-data — NOT application/json (because of the file)
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ThesisVersionResponse>> uploadVersion(
            @PathVariable UUID thesisId,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(ApiResponse.ok(versionService.uploadVersion(thesisId, file)));
    }

    // GET /api/theses/{thesisId}/versions
    @GetMapping
    public ResponseEntity<ApiResponse<List<ThesisVersionResponse>>> getVersions(
            @PathVariable UUID thesisId) {
        return ResponseEntity.ok(ApiResponse.ok(versionService.getVersions(thesisId)));
    }

    // PATCH /api/theses/{thesisId}/versions/{versionId}/mark-final
    @PatchMapping("/{versionId}/mark-final")
    public ResponseEntity<ApiResponse<ThesisVersionResponse>> markAsFinal(
            @PathVariable UUID thesisId,
            @PathVariable UUID versionId) {
        return ResponseEntity.ok(ApiResponse.ok(versionService.markAsFinal(thesisId, versionId)));
    }

    // GET /api/theses/{thesisId}/versions/{versionId}/download
    // Returns the PDF file as a binary download, not JSON
    @GetMapping("/{versionId}/download")
    public ResponseEntity<Resource> downloadVersion(
            @PathVariable UUID thesisId,
            @PathVariable UUID versionId) {
        Resource resource = versionService.downloadVersion(thesisId, versionId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + resource.getFilename() + "\"")
                .body(resource);
    }

    // POST /api/theses/{thesisId}/versions/{versionId}/comments
    @PostMapping("/{versionId}/comments")
    public ResponseEntity<ApiResponse<ThesisCommentResponse>> addComment(
            @PathVariable UUID thesisId,
            @PathVariable UUID versionId,
            @Valid @RequestBody AddCommentRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                versionService.addComment(thesisId, versionId, request)));
    }

    // GET /api/theses/{thesisId}/versions/{versionId}/comments
    @GetMapping("/{versionId}/comments")
    public ResponseEntity<ApiResponse<List<ThesisCommentResponse>>> getComments(
            @PathVariable UUID thesisId,
            @PathVariable UUID versionId) {
        return ResponseEntity.ok(ApiResponse.ok(
                versionService.getComments(thesisId, versionId)));
    }
}
