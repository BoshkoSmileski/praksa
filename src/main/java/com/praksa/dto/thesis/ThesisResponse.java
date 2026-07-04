package com.praksa.dto.thesis;

import com.praksa.model.Thesis;
import com.praksa.model.enums.ThesisStatus;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Full thesis detail response.
 * Built from the Thesis entity INSIDE a @Transactional boundary.
 * Never pass the entity itself to the controller.
 */
@Getter
public class ThesisResponse {

    private final UUID id;
    private final String title;
    private final ThesisStatus status;
    private final int revisionCount;

    // Flat user info — no nested User entity (avoids circular serialization)
    private final UUID studentId;
    private final String studentName;

    private final UUID mentorId;
    private final String mentorName;

    private final String studentComment;
    private final String mentorComment;
    private final String archiveComment;
    private final String serviceComment;

    private final OffsetDateTime submissionDeadline;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;

    // Archive metadata — null until thesis is ARCHIVED
    private final String archiveRegistrationNumber;
    private final OffsetDateTime archiveDate;
    private final UUID archivedById;
    private final String archivedByName;
    private final String archiveNotes;

    // true once the application PDF has been generated and is downloadable
    private final boolean hasApplicationPdf;

    // Static factory method — keeps mapping logic in the DTO, not in the service
    public static ThesisResponse from(Thesis thesis) {
        return new ThesisResponse(thesis);
    }

    private ThesisResponse(Thesis thesis) {
        this.id = thesis.getId();
        this.title = thesis.getTitle();
        this.status = thesis.getStatus();
        this.revisionCount = thesis.getRevisionCount();

        this.studentId = thesis.getStudent().getId();
        this.studentName = thesis.getStudent().getFullName();

        this.mentorId = thesis.getMentor() != null ? thesis.getMentor().getId() : null;
        this.mentorName = thesis.getMentor() != null ? thesis.getMentor().getFullName() : null;

        this.studentComment = thesis.getStudentComment();
        this.mentorComment = thesis.getMentorComment();
        this.archiveComment = thesis.getArchiveComment();
        this.serviceComment = thesis.getServiceComment();

        this.submissionDeadline = thesis.getSubmissionDeadline();
        this.createdAt = thesis.getCreatedAt();
        this.updatedAt = thesis.getUpdatedAt();

        this.archiveRegistrationNumber = thesis.getArchiveRegistrationNumber();
        this.archiveDate = thesis.getArchiveDate();
        this.archivedById = thesis.getArchivedBy() != null ? thesis.getArchivedBy().getId() : null;
        this.archivedByName = thesis.getArchivedBy() != null ? thesis.getArchivedBy().getFullName() : null;
        this.archiveNotes = thesis.getArchiveNotes();
        this.hasApplicationPdf = thesis.getApplicationPdfPath() != null;
    }
}
