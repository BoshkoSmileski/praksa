package com.praksa.dto.defense;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RecordResultRequest {

    @NotNull(message = "Grade is required")
    @Min(value = 5, message = "Minimum grade is 5")
    @Max(value = 10, message = "Maximum grade is 10")
    private Integer grade;

    private String notes;
}
