package com.praksa.dto.committee;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SubmitReviewNotesRequest {

    // Notes are optional — a committee member can confirm with no remarks
    @Size(max = 3000, message = "Notes cannot exceed 3000 characters")
    private String notes;
}
