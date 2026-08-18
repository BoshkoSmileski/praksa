package com.praksa.security;

import com.praksa.exception.UnauthorizedException;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.CommitteeMemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Read-side IDOR fix — the full authorization matrix for the shared thesis read policy
 * ({@link ThesisReadAccessPolicy}). This is the authoritative test of "who may read a
 * thesis"; the six affected read endpoints all delegate to this policy, so proving the
 * matrix here proves the behaviour for every one of them.
 *
 * Legitimate readers: owner STUDENT, assigned MENTOR, seated committee member (of any role),
 * STUDENT_SERVICE, ARCHIVE. Everyone else — including an unrelated STUDENT, an unassigned
 * MENTOR, and a COMMITTEE-role user with no seat on THIS thesis — is denied.
 */
@ExtendWith(MockitoExtension.class)
class ThesisReadAccessPolicyTest {

    @Mock private CommitteeMemberRepository committeeMemberRepository;

    private ThesisReadAccessPolicy policy() {
        return new ThesisReadAccessPolicy(committeeMemberRepository);
    }

    private User user(Role role) {
        return User.builder().id(UUID.randomUUID()).email(role + "-" + UUID.randomUUID() + "@t.com")
                .fullName(role + " User").role(role).build();
    }

    private Thesis thesis(User student, User mentor) {
        return Thesis.builder().id(UUID.randomUUID()).title("Тема")
                .student(student).mentor(mentor).status(ThesisStatus.IN_PROGRESS).build();
    }

    // ── ALLOWED ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Owner STUDENT is allowed (no committee lookup needed)")
    void ownerStudent_allowed() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesis(student, user(Role.MENTOR));

        assertTrue(policy().hasReadAccess(thesis, student));
        assertDoesNotThrow(() -> policy().requireReadAccess(thesis, student));
        verify(committeeMemberRepository, never()).existsByThesisAndProfessor(thesis, student);
    }

    @Test
    @DisplayName("Assigned MENTOR is allowed (no committee lookup needed)")
    void assignedMentor_allowed() {
        User mentor = user(Role.MENTOR);
        Thesis thesis = thesis(user(Role.STUDENT), mentor);

        assertTrue(policy().hasReadAccess(thesis, mentor));
        verify(committeeMemberRepository, never()).existsByThesisAndProfessor(thesis, mentor);
    }

    @Test
    @DisplayName("MENTOR-role professor holding a FORMAL seat on this thesis is allowed")
    void seatedMentorFormalMember_allowed() {
        User formalProfessor = user(Role.MENTOR);              // MENTOR role, but NOT the assigned mentor
        Thesis thesis = thesis(user(Role.STUDENT), user(Role.MENTOR));
        when(committeeMemberRepository.existsByThesisAndProfessor(thesis, formalProfessor)).thenReturn(true);

        assertTrue(policy().hasReadAccess(thesis, formalProfessor));
    }

    @Test
    @DisplayName("Seated COMMITTEE member is allowed")
    void seatedCommittee_allowed() {
        User committee = user(Role.COMMITTEE);
        Thesis thesis = thesis(user(Role.STUDENT), user(Role.MENTOR));
        when(committeeMemberRepository.existsByThesisAndProfessor(thesis, committee)).thenReturn(true);

        assertTrue(policy().hasReadAccess(thesis, committee));
    }

    @Test
    @DisplayName("STUDENT_SERVICE is allowed")
    void studentService_allowed() {
        User svc = user(Role.STUDENT_SERVICE);
        Thesis thesis = thesis(user(Role.STUDENT), user(Role.MENTOR));

        assertTrue(policy().hasReadAccess(thesis, svc));
        verify(committeeMemberRepository, never()).existsByThesisAndProfessor(thesis, svc);
    }

    @Test
    @DisplayName("ARCHIVE is allowed")
    void archive_allowed() {
        User archive = user(Role.ARCHIVE);
        Thesis thesis = thesis(user(Role.STUDENT), user(Role.MENTOR));

        assertTrue(policy().hasReadAccess(thesis, archive));
        verify(committeeMemberRepository, never()).existsByThesisAndProfessor(thesis, archive);
    }

    // ── REJECTED ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Unrelated STUDENT (not the owner) is rejected")
    void unrelatedStudent_rejected() {
        User outsider = user(Role.STUDENT);
        Thesis thesis = thesis(user(Role.STUDENT), user(Role.MENTOR));
        when(committeeMemberRepository.existsByThesisAndProfessor(thesis, outsider)).thenReturn(false);

        assertFalse(policy().hasReadAccess(thesis, outsider));
        assertThrows(UnauthorizedException.class, () -> policy().requireReadAccess(thesis, outsider));
    }

    @Test
    @DisplayName("MENTOR who is neither assigned nor seated is rejected")
    void unassignedMentor_rejected() {
        User otherMentor = user(Role.MENTOR);
        Thesis thesis = thesis(user(Role.STUDENT), user(Role.MENTOR));
        when(committeeMemberRepository.existsByThesisAndProfessor(thesis, otherMentor)).thenReturn(false);

        assertFalse(policy().hasReadAccess(thesis, otherMentor));
        assertThrows(UnauthorizedException.class, () -> policy().requireReadAccess(thesis, otherMentor));
    }

    @Test
    @DisplayName("COMMITTEE-role user with no seat on THIS thesis is rejected (role alone is not access)")
    void unrelatedCommittee_rejected() {
        User committee = user(Role.COMMITTEE);
        Thesis thesis = thesis(user(Role.STUDENT), user(Role.MENTOR));
        when(committeeMemberRepository.existsByThesisAndProfessor(thesis, committee)).thenReturn(false);

        assertFalse(policy().hasReadAccess(thesis, committee));
        assertThrows(UnauthorizedException.class, () -> policy().requireReadAccess(thesis, committee));
    }

    @Test
    @DisplayName("Committee membership is checked against the REQUESTED thesis, not any thesis")
    void committeeMembershipCheckedAgainstRequestedThesis() {
        User committee = user(Role.COMMITTEE);
        Thesis requested = thesis(user(Role.STUDENT), user(Role.MENTOR));
        // The user sits on some OTHER committee, but existsByThesisAndProfessor is queried with the
        // REQUESTED thesis and returns false — so access is denied for this thesis specifically.
        when(committeeMemberRepository.existsByThesisAndProfessor(requested, committee)).thenReturn(false);

        assertFalse(policy().hasReadAccess(requested, committee));
        verify(committeeMemberRepository).existsByThesisAndProfessor(requested, committee);
    }
}
