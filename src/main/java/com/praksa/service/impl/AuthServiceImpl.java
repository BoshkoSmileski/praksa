package com.praksa.service.impl;

import com.praksa.dto.auth.AuthResponse;
import com.praksa.dto.auth.LoginRequest;
import com.praksa.dto.auth.RegisterRequest;
import com.praksa.exception.BadRequestException;
import com.praksa.model.User;
import com.praksa.model.enums.Role;
import com.praksa.repository.UserRepository;
import com.praksa.security.JwtUtil;
import com.praksa.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;

    @Override
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("Оваа е-пошта веќе се користи.");
        }

        // ─── SECURITY: BUG-2 / P0.2 — public registration is STUDENT-only ───────
        // This endpoint is publicly reachable (SecurityConfig permits /api/auth/**),
        // so we MUST NEVER trust the role supplied by the caller. Every account created
        // through public registration is forced to STUDENT. Privileged roles
        // (MENTOR, STUDENT_SERVICE, COMMITTEE, ARCHIVE) are provisioned exclusively via
        // DataInitializer / administrative mechanisms — never through this path.
        // request.getRole() is deliberately ignored (kept on the DTO only for backward
        // compatibility with existing clients); it can no longer escalate privilege.
        final Role role = Role.STUDENT;

        // A STUDENT must have a unique, non-blank index number. Because every public
        // registration is now a STUDENT, this check always applies.
        if (request.getIndexNumber() == null || request.getIndexNumber().isBlank()) {
            throw new BadRequestException("Индексниот број е задолжителен за студенти.");
        }
        if (userRepository.existsByIndexNumber(request.getIndexNumber())) {
            throw new BadRequestException("Овој индексен број веќе се користи.");
        }

        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .role(role)
                .indexNumber(request.getIndexNumber())
                .build();

        userRepository.save(user);

        String token = jwtUtil.generateToken(user.getEmail(), user.getRole().name());
        return new AuthResponse(token, user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        // This throws an exception automatically if credentials are wrong
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BadRequestException("Корисникот не е пронајден."));

        String token = jwtUtil.generateToken(user.getEmail(), user.getRole().name());
        return new AuthResponse(token, user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }
}
