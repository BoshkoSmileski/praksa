package com.praksa.service;

import com.praksa.dto.version.AddCommentRequest;
import com.praksa.dto.version.ThesisCommentResponse;
import com.praksa.dto.version.ThesisVersionResponse;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface ThesisVersionService {

    // Upload a new PDF version — auto-numbers it
    ThesisVersionResponse uploadVersion(UUID thesisId, MultipartFile file);

    // Mark one version as the final submission — clears the flag from any previous final
    ThesisVersionResponse markAsFinal(UUID thesisId, UUID versionId);

    // List all versions for a thesis, ordered oldest to newest
    List<ThesisVersionResponse> getVersions(UUID thesisId);

    // Download — returns a Spring Resource for streaming the file
    Resource downloadVersion(UUID thesisId, UUID versionId);

    // Comments
    ThesisCommentResponse addComment(UUID thesisId, UUID versionId, AddCommentRequest request);
    List<ThesisCommentResponse> getComments(UUID thesisId, UUID versionId);
}
