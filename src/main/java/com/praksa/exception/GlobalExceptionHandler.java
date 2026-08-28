package com.praksa.exception;

import com.praksa.dto.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // Handles @Valid validation failures (e.g. blank email, short password)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(
            MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("Валидацијата не успеа."));
    }

    // Handles "user not found", "thesis not found", etc.
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage()));
    }

    // Handles business rule violations (e.g. mentor already has 15 theses)
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(BadRequestException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ex.getMessage()));
    }

    // Handles unauthorized access attempts
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(UnauthorizedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ex.getMessage()));
    }

    // Handles Spring Security authentication failures raised by
    // AuthenticationManager.authenticate() during login (e.g. BadCredentialsException
    // for a wrong password OR an unknown email — DaoAuthenticationProvider hides the
    // difference by default, so both surface identically here). Without this, the
    // exception fell through to the generic handler below and produced HTTP 500.
    //
    // We return 403 FORBIDDEN to match this application's established auth-failure
    // contract (Spring Security's filter chain already returns 403 for unauthenticated
    // access to protected endpoints — see AuthIntegrationTest.protectedEndpoint_noToken).
    // A single generic message is used deliberately: it must NOT reveal whether the
    // email exists, preventing user enumeration.
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("Погрешна е-пошта или лозинка."));
    }

    // Catches anything else unexpected
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Настана неочекувана грешка."));
    }
}
