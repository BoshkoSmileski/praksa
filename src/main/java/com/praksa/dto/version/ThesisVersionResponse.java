package com.praksa.dto.version;

import com.praksa.model.ThesisVersion;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
public class ThesisVersionResponse {

    private final UUID id;
    private final UUID thesisId;
    private final int versionNumber;
    private final boolean isFinal;
    private final OffsetDateTime uploadedAt;

    // A download URL the client can call — never the raw file system path.
    // The path on disk is an internal implementation detail.
    private final String downloadUrl;

    public static ThesisVersionResponse from(ThesisVersion v) {
        return new ThesisVersionResponse(v);
    }

    private ThesisVersionResponse(ThesisVersion v) {
        this.id = v.getId();
        this.thesisId = v.getThesis().getId();
        this.versionNumber = v.getVersionNumber();
        this.isFinal = v.isFinal();
        this.uploadedAt = v.getUploadedAt();
        // Build a clean REST URL from the version ID
        this.downloadUrl = "/api/theses/" + v.getThesis().getId()
                + "/versions/" + v.getId() + "/download";
    }
}
