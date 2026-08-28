package com.praksa.service;

import com.praksa.dto.defense.RecordResultRequest;
import com.praksa.exception.BadRequestException;
import com.praksa.model.CommitteeMember;
import com.praksa.model.Defense;
import com.praksa.model.DefenseResult;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.MemberRole;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Roadmap Item #7 — the authoritative backend guard that a defense grade is strictly 5–10.
 *
 * These are the service-level (defense-in-depth) tests: they call
 * {@link DefenseResultServiceImpl#recordResult} directly, bypassing the controller's
 * {@code @Valid}, and assert that an out-of-range or null grade is rejected before any
 * DefenseResult is created, before the thesis is transitioned/archived, and before any
 * notification is sent. The DTO-level bean-validation contract is covered separately by
 * {@code RecordResultRequestValidationTest}.
 */
@ExtendWith(MockitoExtension.class)
class DefenseResultGradeValidationTest {

    @Mock private DefenseResultRepository resultRepository;
    @Mock private DefenseRepository defenseRepository;
    @Mock private ThesisRepository thesisRepository;
    @Mock private ThesisStatusHistoryRepository statusHistoryRepository;
    @Mock private CommitteeMemberRepository committeeRepository;
    @Mock private SecurityUtils securityUtils;
    @Mock private NotificationService notificationService;

    @InjectMocks private DefenseResultServiceImpl defenseResultService;

    private User user(Role role) {
        return User.builder().id(UUID.randomUUID()).email(role + "@t.com")
                .fullName(role + " User").role(role).build();
    }

    private RecordResultRequest req(Integer grade) {
        RecordResultRequest r = new RecordResultRequest();
        r.setGrade(grade);
        return r;
    }

    /** A voting (non-external) committee seat for {@code recorder} on {@code thesis}. */
    private CommitteeMember votingSeat(Thesis thesis, User recorder) {
        return CommitteeMember.builder().id(UUID.randomUUID()).thesis(thesis).professor(recorder)
                .memberRole(MemberRole.FORMAL_MEMBER).isExternalNonVoting(false).build();
    }

    // ─── Invalid grades: rejected, no side effects ───────────────────────────
    // The guard runs before any repository or security interaction, so these
    // paths need no stubbing at all (strict Mockito would flag unused stubs).

    @Test
    @DisplayName("grade 4 is rejected — no result, no transition, no notification")
    void grade4_rejected() {
        assertThrows(BadRequestException.class,
                () -> defenseResultService.recordResult(UUID.randomUUID(), UUID.randomUUID(), req(4)));

        verify(resultRepository, never()).save(any());
        verify(statusHistoryRepository, never()).save(any());
        verify(notificationService, never()).notify(any(), any(), any());
        verify(notificationService, never()).notify(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("grade 11 is rejected — no result, no transition, no notification")
    void grade11_rejected() {
        assertThrows(BadRequestException.class,
                () -> defenseResultService.recordResult(UUID.randomUUID(), UUID.randomUUID(), req(11)));

        verify(resultRepository, never()).save(any());
        verify(statusHistoryRepository, never()).save(any());
        verify(notificationService, never()).notify(any(), any(), any());
        verify(notificationService, never()).notify(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("null grade is rejected — no result, no transition, no notification")
    void nullGrade_rejected() {
        assertThrows(BadRequestException.class,
                () -> defenseResultService.recordResult(UUID.randomUUID(), UUID.randomUUID(), req(null)));

        verify(resultRepository, never()).save(any());
        verify(statusHistoryRepository, never()).save(any());
        verify(notificationService, never()).notify(any(), any(), any());
        verify(notificationService, never()).notify(any(), any(), any(), anyString());
    }

    // ─── Boundary valid grades: accepted, full workflow runs ─────────────────
    //
    // Grade 5 is a VALID grade (accepted by validateGrade) but, per the official faculty
    // outcome rule added alongside this test, it means the defense was NOT passed — it
    // produces DEFENSE_FAILED, not ARCHIVED. The full grade-5-vs-6-10 outcome branching
    // (archive metadata, notifications) is covered in depth by DefenseGradeOutcomeTest;
    // this class stays focused on the 5-10 RANGE validation itself, so grade 5 here only
    // asserts it is accepted (no BadRequestException) and a DefenseResult is saved.

    @Test
    @DisplayName("grade 5 (lower bound) is accepted — result saved, thesis moves to DEFENSE_FAILED (not passed)")
    void grade5_accepted() {
        User committee = user(Role.COMMITTEE);
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);

        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).mentor(mentor).status(ThesisStatus.DEFENSE_SCHEDULED).build();

        Defense defense = Defense.builder().id(UUID.randomUUID()).thesis(thesis)
                .room("A1").scheduledAt(OffsetDateTime.now().minusDays(1)).isCancelled(false).build();

        when(securityUtils.getCurrentUser()).thenReturn(committee);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(committeeRepository.findByThesisAndProfessor(thesis, committee))
                .thenReturn(Optional.of(votingSeat(thesis, committee)));
        when(defenseRepository.findById(defense.getId())).thenReturn(Optional.of(defense));
        when(resultRepository.findByDefense(defense)).thenReturn(Optional.empty());

        defenseResultService.recordResult(thesis.getId(), defense.getId(), req(5));

        verify(resultRepository, times(1)).save(any(DefenseResult.class));
        assertEquals(ThesisStatus.DEFENSE_FAILED, thesis.getStatus());
        org.junit.jupiter.api.Assertions.assertNull(thesis.getArchiveRegistrationNumber());
        verify(notificationService).notify(any(), any(), org.mockito.ArgumentMatchers.eq(
                NotificationType.DEFENSE_FAILED_CAN_REAPPLY), anyString());
        verify(notificationService, never()).notify(any(), any(), org.mockito.ArgumentMatchers.eq(
                NotificationType.THESIS_ARCHIVED), anyString());
    }

    @Test
    @DisplayName("grade 10 (upper bound) is accepted — result saved, thesis archived, notifications sent")
    void grade10_accepted() {
        assertValidGradeArchives(10);
    }

    /**
     * Drives the full happy path for a valid SUCCESSFUL grade (6-10) and asserts the existing
     * workflow is unchanged: DefenseResult persisted, thesis transitioned to ARCHIVED with a
     * registration number, and both grading/archive notifications sent to the student.
     */
    private void assertValidGradeArchives(int grade) {
        User committee = user(Role.COMMITTEE);
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);

        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).mentor(mentor).status(ThesisStatus.DEFENSE_SCHEDULED).build();

        Defense defense = Defense.builder().id(UUID.randomUUID()).thesis(thesis)
                .room("A1").scheduledAt(OffsetDateTime.now().minusDays(1)).isCancelled(false).build();

        when(securityUtils.getCurrentUser()).thenReturn(committee);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        // Recorder is a seated VOTING committee member of THIS thesis (write-side grading authorization).
        when(committeeRepository.findByThesisAndProfessor(thesis, committee))
                .thenReturn(Optional.of(votingSeat(thesis, committee)));
        when(defenseRepository.findById(defense.getId())).thenReturn(Optional.of(defense));
        when(resultRepository.findByDefense(defense)).thenReturn(Optional.empty());
        when(thesisRepository.countByArchiveRegistrationNumberStartingWith(anyString())).thenReturn(0L);

        defenseResultService.recordResult(thesis.getId(), defense.getId(), req(grade));

        verify(resultRepository, times(1)).save(any(DefenseResult.class));
        assertEquals(ThesisStatus.ARCHIVED, thesis.getStatus());
        org.junit.jupiter.api.Assertions.assertNotNull(thesis.getArchiveRegistrationNumber());
        verify(notificationService).notify(any(), any(), org.mockito.ArgumentMatchers.eq(
                NotificationType.THESIS_GRADED), anyString());
        verify(notificationService).notify(any(), any(), org.mockito.ArgumentMatchers.eq(
                NotificationType.THESIS_ARCHIVED), anyString());
    }
}
