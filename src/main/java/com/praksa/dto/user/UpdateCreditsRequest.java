package com.praksa.dto.user;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Request body for STUDENT_SERVICE to set/update a student's credit balance.
 * Role authorization is enforced server-side in UserServiceImpl.updateCredits.
 */
@Getter
@Setter
public class UpdateCreditsRequest {

    @NotNull(message = "Credits are required")
    @Min(value = 0, message = "Credits cannot be negative")
    private Integer credits;
}
