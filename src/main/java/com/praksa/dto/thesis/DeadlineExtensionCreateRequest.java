package com.praksa.dto.thesis;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * STUDENT-submitted request to extend the thesis's defense deadline.
 *
 * <p>{@code reason} is the student's written explanation — required and bounded so a blank or
 * absurdly long value never reaches the service layer. {@code requestedDays} is the explicit
 * number of additional days being asked for; the official faculty rule caps a single request at
 * 15 — enforced here via bean validation AND re-checked in the service (defense-in-depth,
 * matching this project's "the service layer is authoritative" convention).
 */
@Getter
@Setter
public class DeadlineExtensionCreateRequest {

    @NotBlank(message = "A reason is required")
    @Size(max = 2000, message = "Reason cannot exceed 2000 characters")
    private String reason;

    @NotNull(message = "requestedDays is required")
    @Min(value = 1, message = "requestedDays must be at least 1")
    @Max(value = 15, message = "requestedDays cannot exceed 15")
    private Integer requestedDays;
}
