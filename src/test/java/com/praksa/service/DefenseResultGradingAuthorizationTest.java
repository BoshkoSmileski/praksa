package com.praksa.service;

import com.praksa.dto.defense.RecordResultRequest;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.Defense;
import com.praksa.model.DefenseResult;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.CommitteeMemberRepository;
import com.praksa.repository.DefenseRepository;
import com.praksa.repository.DefenseResultRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.service.impl.DefenseResultServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1 write-side grading IDOR (superset of BUG-13) — {@link DefenseResultServiceImpl#recordResult}
 * must scope grading to the SPECIFIC thesis's committee. Knowing the thesis/defense UUID, holding
 * the COMMITTEE role globally, or sitting on some OTHER thesis's committee must NOT authorize a
 * grade. Recording a grade also archives the thesis, so authorization runs before ANY mutation.
 *
 * <p>Authorization matrix (all with a VALID grade so the grade check is never the reason):
 * <ul>
 *   <li>seated committee member (COMMITTEE- or MENTOR-role) on THIS thesis → ALLOWED;</li>
 *   <li>COMMITTEE-role user not seated on this thesis → 403;</li>
 *   <li>user seated only on ANOTHER thesis → 403 (checked against the requested thesis);</li>
 *   <li>STUDENT owner → 403 (ownership never confers grading);</li>
 *   <li>unseated MENTOR → 403;</li>
 *   <li>STUDENT_SERVICE → 403 (documented policy: only the committee grades — resolves BUG-13);</li>
 *   <li>ARCHIVE → 403.</li>
 * </ul>
 * Every 403 path asserts ZERO workflow mutations: no DefenseResult save, no status transition,
 * no history row, no archive metadata, no notification.
 */
@ExtendWith(MockitoExtension.class)
class DefenseResultGradingAuthorizationTest {

    @Mock private DefenseResultRepository resultRepository;
    @Mock private DefenseRepository defenseRepository;
    @Mock private ThesisRepository thesisRepository;
    @Mock private ThesisStatusHistoryRepository statusHistoryRepository;
    @Mock private CommitteeMemberRepository committeeRepository;
    @Mock private SecurityUtils securityUtils;
    @Mock private NotificationService notificationService;

    @InjectMocks private DefenseResultServiceImpl service;

    private User user(Role role) {
        return User.builder().id(UUID.randomUUID()).email(role + "@t.com")
                .fullName(role + " User").role(role).build();
    }

    private Thesis scheduledThesis(User student, User mentor) {
        return Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).mentor(mentor).status(ThesisStatus.DEFENSE_SCHEDULED).build();
    }

    private Defense defenseFor(Thesis thesis) {
        return Defense.builder().id(UUID.randomUUID()).thesis(thesis)
                .room("A1").scheduledAt(OffsetDateTime.now().minusDays(1)).isCancelled(false).build();
    }

    private RecordResultRequest validGrade() {
        RecordResultRequest r = new RecordResultRequest();
        r.setGrade(9);
        r.setNotes("ok");
        return r;
    }

    // ─── ALLOWED: seated committee member grades and the full workflow runs ──────────────

    @Test
    @DisplayName("1. Seated COMMITTEE member on THIS thesis → grade saved, thesis archived, notifications sent")
    void seatedCommittee_allowed_runsWorkflow() {
        User committee = user(Role.COMMITTEE);
        User student = user(Role.STUDENT);
        Thesis thesis = scheduledThesis(student, user(Role.MENTOR));
        Defense defense = defenseFor(thesis);

        when(securityUtils.getCurrentUser()).thenReturn(committee);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(committeeRepository.existsByThesisAndProfessor(thesis, committee)).thenReturn(true);
        when(defenseRepository.findById(defense.getId())).thenReturn(Optional.of(defense));
        when(resultRepository.findByDefense(defense)).thenReturn(Optional.empty());
        when(thesisRepository.countByArchiveRegistrationNumberStartingWith(anyString())).thenReturn(0L);

        service.recordResult(thesis.getId(), defense.getId(), validGrade());

        verify(resultRepository, times(1)).save(any(DefenseResult.class));
        assertEquals(ThesisStatus.ARCHIVED, thesis.getStatus());
        org.junit.jupiter.api.Assertions.assertNotNull(thesis.getArchiveRegistrationNumber());
        verify(notificationService).notify(eq(student), eq(thesis), eq(NotificationType.THESIS_GRADED), anyString());
        verify(notificationService).notify(eq(student), eq(thesis), eq(NotificationType.THESIS_ARCHIVED), anyString());
    }

    @Test
    @DisplayName("5a. Assigned MENTOR who holds a committee seat on THIS thesis → allowed (part of the committee)")
    void seatedMentor_allowed_runsWorkflow() {
        User mentor = user(Role.MENTOR);
        User student = user(Role.STUDENT);
        Thesis thesis = scheduledThesis(student, mentor);
        Defense defense = defenseFor(thesis);

        when(securityUtils.getCurrentUser()).thenReturn(mentor);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        // The mentor holds a MENTOR_MEMBER seat on this thesis's committee.
        when(committeeRepository.existsByThesisAndProfessor(thesis, mentor)).thenReturn(true);
        when(defenseRepository.findById(defense.getId())).thenReturn(Optional.of(defense));
        when(resultRepository.findByDefense(defense)).thenReturn(Optional.empty());
        when(thesisRepository.countByArchiveRegistrationNumberStartingWith(anyString())).thenReturn(0L);

        service.recordResult(thesis.getId(), defense.getId(), validGrade());

        verify(resultRepository, times(1)).save(any(DefenseResult.class));
        assertEquals(ThesisStatus.ARCHIVED, thesis.getStatus());
    }

    // ─── DENIED: 403 with ZERO workflow mutations ───────────────────────────────────────

    @Test
    @DisplayName("2. COMMITTEE-role user NOT seated on this thesis → 403, no mutations")
    void unseatedCommittee_denied() {
        assertDenied(user(Role.COMMITTEE), /* seatedOnThisThesis */ false);
    }

    @Test
    @DisplayName("3. User seated only on ANOTHER thesis → 403 (membership checked against the requested thesis)")
    void seatedOnAnotherThesis_denied() {
        // existsByThesisAndProfessor(THIS thesis, user) is false: the seat is on a different thesis.
        assertDenied(user(Role.COMMITTEE), false);
    }

    @Test
    @DisplayName("4. STUDENT owner → 403 (ownership never confers grading rights), no mutations")
    void studentOwner_denied() {
        User student = user(Role.STUDENT);
        Thesis thesis = scheduledThesis(student, user(Role.MENTOR)); // student IS the owner
        Defense defense = defenseFor(thesis);

        when(securityUtils.getCurrentUser()).thenReturn(student);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(committeeRepository.existsByThesisAndProfessor(thesis, student)).thenReturn(false);

        assertThrows(UnauthorizedException.class,
                () -> service.recordResult(thesis.getId(), defense.getId(), validGrade()));

        assertNoMutations(thesis);
    }

    @Test
    @DisplayName("5b. MENTOR not seated on this thesis → 403, no mutations")
    void unseatedMentor_denied() {
        assertDenied(user(Role.MENTOR), false);
    }

    @Test
    @DisplayName("6. STUDENT_SERVICE → 403 (documented policy: only the committee grades — BUG-13), no mutations")
    void studentService_denied() {
        assertDenied(user(Role.STUDENT_SERVICE), false);
    }

    @Test
    @DisplayName("7. ARCHIVE → 403 (never holds a seat; does not grade), no mutations")
    void archive_denied() {
        assertDenied(user(Role.ARCHIVE), false);
    }

    /**
     * Drives recordResult for a caller who is NOT seated on the thesis and asserts a 403 with no
     * workflow side effects. The defense is never even looked up — authorization fails first.
     */
    private void assertDenied(User caller, boolean seated) {
        Thesis thesis = scheduledThesis(user(Role.STUDENT), user(Role.MENTOR));
        Defense defense = defenseFor(thesis);

        when(securityUtils.getCurrentUser()).thenReturn(caller);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(committeeRepository.existsByThesisAndProfessor(thesis, caller)).thenReturn(seated);

        assertThrows(UnauthorizedException.class,
                () -> service.recordResult(thesis.getId(), defense.getId(), validGrade()));

        assertNoMutations(thesis);
        // Authorization runs before the defense is resolved, so the defense repo is never touched.
        verify(defenseRepository, never()).findById(any());
    }

    /** Asserts recordResult mutated nothing: no result, no transition, no history, no metadata, no notification. */
    private void assertNoMutations(Thesis thesis) {
        verify(resultRepository, never()).save(any());
        verify(statusHistoryRepository, never()).save(any());
        verify(notificationService, never()).notify(any(), any(), any());
        verify(notificationService, never()).notify(any(), any(), any(), anyString());
        assertEquals(ThesisStatus.DEFENSE_SCHEDULED, thesis.getStatus());
        assertNull(thesis.getArchiveRegistrationNumber());
        assertNull(thesis.getArchiveDate());
        assertNull(thesis.getArchivedBy());
    }
}
