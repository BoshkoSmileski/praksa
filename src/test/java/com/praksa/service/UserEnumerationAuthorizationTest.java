package com.praksa.service;

import com.praksa.dto.user.UserSummaryResponse;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.User;
import com.praksa.model.enums.Role;
import com.praksa.repository.UserRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.service.impl.UserServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 — user enumeration / PII leak via {@code GET /api/users?role=}.
 *
 * <p>{@link UserServiceImpl#getUsersByRole(Role)} is the security boundary for the two
 * legitimate consumers of the endpoint. Each requested role is a distinct, separately
 * authorized use case with its own minimal response shape:</p>
 * <ul>
 *   <li>{@code role=STUDENT} — STUDENT_SERVICE only (credit-management list); full
 *       student fields (id, fullName, role, email, indexNumber, credits).</li>
 *   <li>{@code role=MENTOR} — mentor picker (STUDENT / MENTOR / STUDENT_SERVICE);
 *       identity only (id, fullName, role) — email/indexNumber/credits NOT exposed.</li>
 *   <li>any other requested role — 403 for everyone (no legitimate enumeration path).</li>
 * </ul>
 *
 * <p>Authorization runs BEFORE the repository is queried: a denied caller must never
 * reach {@code findByRole}. {@link UnauthorizedException} is mapped to HTTP 403 by
 * GlobalExceptionHandler. There is no ADMIN role in this project, so the deny checks
 * are exhaustive over every role the enum actually has.</p>
 */
@ExtendWith(MockitoExtension.class)
class UserEnumerationAuthorizationTest {

    @Mock private UserRepository userRepository;
    @Mock private SecurityUtils securityUtils;

    @InjectMocks private UserServiceImpl service;

    private User user(Role role) {
        return User.builder()
                .id(UUID.randomUUID())
                .email(role + "@t.com")
                .fullName(role + " User")
                .role(role)
                .build();
    }

    private User student() {
        return User.builder()
                .id(UUID.randomUUID())
                .email("ana@student.test")
                .fullName("Ana Student")
                .role(Role.STUDENT)
                .indexNumber("2024/123")
                .credits(210)
                .build();
    }

    // ─── ALLOWED: STUDENT_SERVICE lists STUDENTs with the full credit fields ──────────────

    @Test
    @DisplayName("STUDENT_SERVICE requesting STUDENT → allowed, repository queried, full student fields present")
    void studentService_listsStudents_withCreditFields() {
        User target = student();
        when(securityUtils.getCurrentUser()).thenReturn(user(Role.STUDENT_SERVICE));
        when(userRepository.findByRole(Role.STUDENT)).thenReturn(List.of(target));

        List<UserSummaryResponse> result = service.getUsersByRole(Role.STUDENT);

        // Repository was queried exactly once, for the STUDENT role, after authorization passed.
        verify(userRepository, times(1)).findByRole(Role.STUDENT);

        assertEquals(1, result.size());
        UserSummaryResponse dto = result.get(0);
        assertEquals(target.getId(), dto.getId());
        assertEquals("Ana Student", dto.getFullName());
        assertEquals(Role.STUDENT, dto.getRole());
        assertEquals("ana@student.test", dto.getEmail());
        assertEquals("2024/123", dto.getIndexNumber());
        assertEquals(210, dto.getCredits());
    }

    // ─── ALLOWED: mentor picker (STUDENT / MENTOR / STUDENT_SERVICE) → identity only, no PII ─

    @Test
    @DisplayName("STUDENT requesting MENTOR → allowed (mentor picker), identity only, no email/index/credits")
    void student_listsMentors_identityOnly() {
        assertMentorPickerAllowed(Role.STUDENT);
    }

    @Test
    @DisplayName("MENTOR requesting MENTOR → allowed (propose committee), identity only, no email/index/credits")
    void mentor_listsMentors_identityOnly() {
        assertMentorPickerAllowed(Role.MENTOR);
    }

    @Test
    @DisplayName("STUDENT_SERVICE requesting MENTOR → allowed, identity only, no email/index/credits")
    void studentService_listsMentors_identityOnly() {
        assertMentorPickerAllowed(Role.STUDENT_SERVICE);
    }

    private void assertMentorPickerAllowed(Role callerRole) {
        User mentor = User.builder()
                .id(UUID.randomUUID())
                .email("prof@uni.test")   // present on the entity…
                .fullName("Prof Mentor")
                .role(Role.MENTOR)
                .build();
        when(securityUtils.getCurrentUser()).thenReturn(user(callerRole));
        when(userRepository.findByRole(Role.MENTOR)).thenReturn(List.of(mentor));

        List<UserSummaryResponse> result = service.getUsersByRole(Role.MENTOR);

        verify(userRepository, times(1)).findByRole(Role.MENTOR);
        assertEquals(1, result.size());

        UserSummaryResponse dto = result.get(0);
        assertEquals(mentor.getId(), dto.getId());
        assertEquals("Prof Mentor", dto.getFullName());
        assertEquals(Role.MENTOR, dto.getRole());
        // …but the mentor-picker projection must NOT carry PII.
        assertNull(dto.getEmail(), "mentor picker must not expose email");
        assertNull(dto.getIndexNumber(), "mentor picker must not expose index number");
        assertNull(dto.getCredits(), "mentor picker must not expose credits");
    }

    // ─── DENIED: STUDENT cannot enumerate arbitrary users ─────────────────────────────────

    @Test
    @DisplayName("STUDENT requesting STUDENT → 403, repository never queried")
    void student_cannotListStudents() {
        assertDenied(Role.STUDENT, Role.STUDENT);
    }

    @Test
    @DisplayName("STUDENT requesting STUDENT_SERVICE → 403, repository never queried")
    void student_cannotListStaff() {
        assertDenied(Role.STUDENT, Role.STUDENT_SERVICE);
    }

    // ─── DENIED: MENTOR may only use the mentor picker, nothing else ──────────────────────

    @Test
    @DisplayName("MENTOR requesting STUDENT → 403, repository never queried")
    void mentor_cannotListStudents() {
        assertDenied(Role.MENTOR, Role.STUDENT);
    }

    // ─── DENIED: COMMITTEE cannot enumerate anyone ────────────────────────────────────────

    @Test
    @DisplayName("COMMITTEE requesting STUDENT → 403, repository never queried")
    void committee_cannotListStudents() {
        assertDenied(Role.COMMITTEE, Role.STUDENT);
    }

    @Test
    @DisplayName("COMMITTEE requesting MENTOR → 403, repository never queried")
    void committee_cannotListMentors() {
        assertDenied(Role.COMMITTEE, Role.MENTOR);
    }

    // ─── DENIED: ARCHIVE cannot enumerate anyone ──────────────────────────────────────────

    @Test
    @DisplayName("ARCHIVE requesting STUDENT → 403, repository never queried")
    void archive_cannotListStudents() {
        assertDenied(Role.ARCHIVE, Role.STUDENT);
    }

    @Test
    @DisplayName("ARCHIVE requesting MENTOR → 403, repository never queried")
    void archive_cannotListMentors() {
        assertDenied(Role.ARCHIVE, Role.MENTOR);
    }

    // ─── DENIED: swapping role= to a non-picker role cannot enumerate, even for staff ─────

    @Test
    @DisplayName("STUDENT_SERVICE requesting COMMITTEE → 403 (no enumeration of non-picker roles), repository never queried")
    void studentService_cannotEnumerateCommittee() {
        assertDenied(Role.STUDENT_SERVICE, Role.COMMITTEE);
    }

    @Test
    @DisplayName("STUDENT_SERVICE requesting ARCHIVE → 403 (no enumeration of non-picker roles), repository never queried")
    void studentService_cannotEnumerateArchive() {
        assertDenied(Role.STUDENT_SERVICE, Role.ARCHIVE);
    }

    /**
     * Drives getUsersByRole for a caller who is not authorized for the requested role and
     * asserts a 403 with the user repository never touched — authorization fails before any
     * query runs.
     */
    private void assertDenied(Role callerRole, Role requestedRole) {
        when(securityUtils.getCurrentUser()).thenReturn(user(callerRole));

        assertThrows(UnauthorizedException.class, () -> service.getUsersByRole(requestedRole));

        verify(userRepository, never()).findByRole(any());
    }
}
