package com.praksa.controller;

import com.praksa.dto.ApiResponse;
import com.praksa.dto.user.UserSummaryResponse;
import com.praksa.model.enums.Role;
import com.praksa.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "Lookup users for pickers and dropdowns")
public class UserController {

    private final UserRepository userRepository;

    @Operation(summary = "List users by role", description = "Returns slim user summaries filtered by role.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<UserSummaryResponse>>> getUsersByRole(
            @RequestParam Role role) {
        List<UserSummaryResponse> users = userRepository.findByRole(role)
                .stream()
                .map(UserSummaryResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(users));
    }
}
