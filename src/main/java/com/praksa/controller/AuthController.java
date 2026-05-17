package com.praksa.controller;

import com.praksa.dto.ApiResponse;
import com.praksa.dto.auth.AuthResponse;
import com.praksa.dto.auth.LoginRequest;
import com.praksa.dto.auth.RegisterRequest;
import com.praksa.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Register and login — no token required")
@SecurityRequirements  // overrides the global Bearer requirement for this whole controller
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Register a new user", description = "Creates a user with the specified role. Students must provide an index number.")
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.ok(ApiResponse.ok("Registered successfully", response));
    }

    @Operation(summary = "Login", description = "Returns a JWT token. Paste the token value into the Authorize button above.")
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.ok("Login successful", response));
    }
}
