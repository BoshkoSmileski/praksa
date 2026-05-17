package com.praksa.dto.thesis;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EligibilityDecisionRequest {

    @NotNull(message = "Decision is required")
    private Boolean approved;
}
