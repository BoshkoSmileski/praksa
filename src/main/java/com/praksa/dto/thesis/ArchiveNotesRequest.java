package com.praksa.dto.thesis;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * P2.2 — used by the ARCHIVE role to add or edit the free-text archive notes
 * ({@code Thesis.archiveNotes}) on an already-archived thesis.
 *
 * <p>{@code notes} is optional: a null or blank value clears the notes. Editing the
 * notes never changes the thesis status and never emits a notification — it only
 * updates the archive record's free-text annotation.
 */
@Getter
@Setter
public class ArchiveNotesRequest {

    @Size(max = 5000, message = "Archive notes cannot exceed 5000 characters")
    private String notes;
}
