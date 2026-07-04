package com.praksa.dto.thesis;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Student-side revision of a thesis proposal after the mentor requested changes.
 * Same thesis record is reused; only the title (and optionally idea description)
 * change. On submit, status returns to PENDING_MENTOR_APPROVAL with the same mentor.
 */
@Getter
@Setter
public class ReviseProposalRequest {

    @NotBlank(message = "Title is required")
    @Size(min = 5, max = 255, message = "Title must be between 5 and 255 characters")
    private String title;

    @Size(max = 2000, message = "Description cannot exceed 2000 characters")
    private String studentComment;
}
