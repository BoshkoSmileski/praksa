package com.praksa.service;

import com.praksa.dto.auth.AuthResponse;
import com.praksa.dto.auth.RegisterRequest;
import com.praksa.exception.BadRequestException;
import com.praksa.model.User;
import com.praksa.model.enums.Role;
import com.praksa.repository.UserRepository;
import com.praksa.security.JwtUtil;
import com.praksa.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUG-2 / P0.2 — public registration privilege-escalation guard.
 *
 * These service-level tests call {@link AuthServiceImpl#register} directly (bypassing the
 * controller's {@code @Valid}) and assert the authoritative security rule: NO MATTER what
 * role the caller requests, the persisted account is always a {@link Role#STUDENT}. This is
 * the security boundary — the frontend hiding the role selector is NOT authorization.
 *
 * DTO-shape/bean-validation (e.g. blank email) is covered by the controller layer and the
 * existing {@code AuthIntegrationTest}; here we focus on the role-coercion invariant.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceRegistrationTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtUtil jwtUtil;
    @Mock private AuthenticationManager authenticationManager; // unused by register(), needed for constructor

    @InjectMocks private AuthServiceImpl authService;

    private RegisterRequest request(Role requestedRole) {
        RegisterRequest r = new RegisterRequest();
        r.setEmail("applicant@test.com");
        r.setPassword("password123");
        r.setFullName("Applicant");
        r.setRole(requestedRole);
        r.setIndexNumber("2024/500");
        return r;
    }

    private void stubHappyPath() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.existsByIndexNumber(anyString())).thenReturn(false);
        lenient().when(passwordEncoder.encode(anyString())).thenReturn("HASHED");
        lenient().when(jwtUtil.generateToken(anyString(), anyString())).thenReturn("jwt-token");
    }

    @Test
    @DisplayName("role=STUDENT → creates a STUDENT successfully")
    void register_student_success() {
        stubHappyPath();

        AuthResponse response = authService.register(request(Role.STUDENT));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertEquals(Role.STUDENT, saved.getValue().getRole());
        assertEquals("2024/500", saved.getValue().getIndexNumber());
        assertEquals(Role.STUDENT, response.getRole());
        // The JWT is minted for STUDENT, never the requested privileged role.
        verify(jwtUtil).generateToken("applicant@test.com", "STUDENT");
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"STUDENT_SERVICE", "ARCHIVE", "COMMITTEE", "MENTOR"})
    @DisplayName("any privileged requested role is coerced to STUDENT (never escalates)")
    void register_privilegedRole_isCoercedToStudent(Role requestedPrivilegedRole) {
        stubHappyPath();

        AuthResponse response = authService.register(request(requestedPrivilegedRole));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        // The persisted account MUST be a STUDENT, regardless of what was requested.
        assertEquals(Role.STUDENT, saved.getValue().getRole(),
                "Requested role " + requestedPrivilegedRole + " must NOT create a privileged account");
        assertEquals(Role.STUDENT, response.getRole());
        // The token must be issued for STUDENT, not the requested role.
        verify(jwtUtil).generateToken(anyString(), eq("STUDENT"));
        verify(jwtUtil, never()).generateToken(anyString(), eq(requestedPrivilegedRole.name()));
    }

    @Test
    @DisplayName("duplicate email is rejected and nothing is persisted")
    void register_duplicateEmail_rejected() {
        when(userRepository.existsByEmail(anyString())).thenReturn(true);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> authService.register(request(Role.STUDENT)));
        assertEquals("Email already in use", ex.getMessage());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("missing index number is rejected (public registration is always STUDENT)")
    void register_missingIndex_rejected() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        RegisterRequest noIndex = request(Role.STUDENT);
        noIndex.setIndexNumber(null);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> authService.register(noIndex));
        assertEquals("Index number is required for students", ex.getMessage());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("a privileged request that omits the index number is still rejected (no escalation via missing index)")
    void register_privilegedRole_missingIndex_rejected() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        RegisterRequest noIndex = request(Role.STUDENT_SERVICE);
        noIndex.setIndexNumber("   ");

        assertThrows(BadRequestException.class, () -> authService.register(noIndex));
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("duplicate index number is rejected")
    void register_duplicateIndex_rejected() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.existsByIndexNumber(anyString())).thenReturn(true);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> authService.register(request(Role.STUDENT)));
        assertEquals("Index number already in use", ex.getMessage());
        verify(userRepository, never()).save(any());
    }
}
