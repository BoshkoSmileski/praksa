package com.praksa.controller;

import com.praksa.dto.ApiResponse;
import com.praksa.dto.user.UpdateCreditsRequest;
import com.praksa.dto.user.UserDetailResponse;
import com.praksa.dto.user.UserSummaryResponse;
import com.praksa.model.enums.Role;
import com.praksa.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "Lookup users for pickers and dropdowns")
public class UserController {

    private final UserService userService;

    @Operation(summary = "List users by role",
            description = "Returns purpose-specific user summaries for pickers/lists. " +
                          "Authorization is enforced per requested role: role=STUDENT is " +
                          "STUDENT_SERVICE-only (credit list, full student fields); role=MENTOR " +
                          "is for the mentor picker (STUDENT/MENTOR/STUDENT_SERVICE, identity only, " +
                          "no email); any other role is denied (403).")
    @GetMapping
    public ResponseEntity<ApiResponse<List<UserSummaryResponse>>> getUsersByRole(
            @RequestParam Role role) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getUsersByRole(role)));
    }

    @Operation(summary = "Get current user",
            description = "Returns the authenticated user's own details, including credit balance (students).")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserDetailResponse>> getCurrentUser() {
        return ResponseEntity.ok(ApiResponse.ok(userService.getCurrentUser()));
    }

    @Operation(summary = "Update student credits",
            description = "Sets/updates a student's credit balance. Requires role: STUDENT_SERVICE. " +
                          "Target user must be a STUDENT.")
    @PatchMapping("/{id}/credits")
    public ResponseEntity<ApiResponse<UserDetailResponse>> updateCredits(
            @Parameter(description = "Target student's UUID") @PathVariable UUID id,
            @Valid @RequestBody UpdateCreditsRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(userService.updateCredits(id, request)));
    }
}
