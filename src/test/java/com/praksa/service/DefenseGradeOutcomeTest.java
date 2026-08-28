package com.praksa.service;

import com.praksa.dto.defense.RecordResultRequest;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.CommitteeMember;
import com.praksa.model.Defense;
import com.praksa.model.DefenseResult;
import com.praksa.model.Thesis;
import com.praksa.model.ThesisStatusHistory;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Grade-5-means-DEFENSE_FAILED task — the core outcome-branching behavior of
 * {@link DefenseResultServiceImpl#recordResult}.
 *
 * <p>Official faculty rule: the defense committee grades 5-10. Grade 5 means the diploma
 * thesis was NOT successfully defended — the thesis moves to {@code DEFENSE_FAILED}, receives
 * no archive metadata, and the student is notified with exactly one notification
 * ({@code DEFENSE_FAILED_CAN_REAPPLY}). Grades 6-10 keep the pre-existing successful-archive
 * behavior byte-for-byte (covered in depth by {@link ArchiveMetadataTest} and
 * {@link DefenseResultServiceNotificationTest}; this class re-proves the boundary case, grade 6,
 * to pin down the exact 5/6 cut line).
 */
@ExtendWith(MockitoExtension.class)
class DefenseGradeOutcomeTest {

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

    private Thesis scheduledThesis(User student, User mentor) {
        return Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).mentor(mentor).status(ThesisStatus.DEFENSE_SCHEDULED).build();
    }

    private Defense defenseFor(Thesis thesis) {
        return Defense.builder().id(UUID.randomUUID()).thesis(thesis)
                .room("A1").scheduledAt(OffsetDateTime.now().minusDays(1)).isCancelled(false).build();
    }

    private RecordResultRequest req(int grade) {
        RecordResultRequest r = new RecordResultRequest();
        r.setGrade(grade);
        return r;
    }

    /** A voting (non-external) committee seat for {@code recorder} on {@code thesis}. */
    private CommitteeMember votingSeat(Thesis thesis, User recorder) {
        return CommitteeMember.builder().id(UUID.randomUUID()).thesis(thesis).professor(recorder)
                .memberRole(MemberRole.FORMAL_MEMBER).isExternalNonVoting(false).build();
    }

    private void stubHappyPath(User recorder, Thesis thesis, Defense defense) {
        when(securityUtils.getCurrentUser()).thenReturn(recorder);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(committeeRepository.findByThesisAndProfessor(thesis, recorder))
                .thenReturn(Optional.of(votingSeat(thesis, recorder)));
        when(defenseRepository.findById(defense.getId())).thenReturn(Optional.of(defense));
        when(resultRepository.findByDefense(defense)).thenReturn(Optional.empty());
    }

    // =========================================================================
    // A. Grade 5 — DEFENSE_FAILED
    // =========================================================================

    @Test
    @DisplayName("Grade 5: status becomes DEFENSE_FAILED, not ARCHIVED")
    void grade5_transitionsToDefenseFailed() {
        User committee = user(Role.COMMITTEE);
        Thesis thesis = scheduledThesis(user(Role.STUDENT), user(Role.MENTOR));
        Defense defense = defenseFor(thesis);
        stubHappyPath(committee, thesis, defense);

        defenseResultService.recordResult(thesis.getId(), defense.getId(), req(5));

        assertEquals(ThesisStatus.DEFENSE_FAILED, thesis.getStatus());
    }

    @Test
    @DisplayName("Grade 5: NO archive metadata — registration number and archive date stay null")
    void grade5_noArchiveMetadata() {
        User committee = user(Role.COMMITTEE);
        Thesis thesis = scheduledThesis(user(Role.STUDENT), user(Role.MENTOR));
        Defense defense = defenseFor(thesis);
        stubHappyPath(committee, thesis, defense);

        defenseResultService.recordResult(thesis.getId(), defense.getId(), req(5));

        assertNull(thesis.getArchiveRegistrationNumber());
        assertNull(thesis.getArchiveDate());
        assertNull(thesis.getArchivedBy());
        assertNull(thesis.getArchiveNotes());
        // The registration-number generator must never even be consulted for a failed defense.
        verify(thesisRepository, never()).countByArchiveRegistrationNumberStartingWith(anyString());
    }

    @Test
    @DisplayName("Grade 5: the DefenseResult is still persisted with grade 5 (record preserved)")
    void grade5_defenseResultPersisted() {
        User committee = user(Role.COMMITTEE);
        Thesis thesis = scheduledThesis(user(Role.STUDENT), user(Role.MENTOR));
        Defense defense = defenseFor(thesis);
        stubHappyPath(committee, thesis, defense);

        defenseResultService.recordResult(thesis.getId(), defense.getId(), req(5));

        ArgumentCaptor<DefenseResult> captor = ArgumentCaptor.forClass(DefenseResult.class);
        verify(resultRepository, times(1)).save(captor.capture());
        assertEquals(5, captor.getValue().getGrade());
        assertEquals(defense, captor.getValue().getDefense());
    }

    @Test
    @DisplayName("Grade 5: exactly one ThesisStatusHistory row is written — DEFENSE_SCHEDULED -> DEFENSE_FAILED")
    void grade5_statusHistoryRecorded() {
        User committee = user(Role.COMMITTEE);
        Thesis thesis = scheduledThesis(user(Role.STUDENT), user(Role.MENTOR));
        Defense defense = defenseFor(thesis);
        stubHappyPath(committee, thesis, defense);

        defenseResultService.recordResult(thesis.getId(), defense.getId(), req(5));

        ArgumentCaptor<ThesisStatusHistory> captor = ArgumentCaptor.forClass(ThesisStatusHistory.class);
        verify(statusHistoryRepository, times(1)).save(captor.capture());
        assertEquals(ThesisStatus.DEFENSE_SCHEDULED, captor.getValue().getOldStatus());
        assertEquals(ThesisStatus.DEFENSE_FAILED, captor.getValue().getNewStatus());
        assertEquals(committee, captor.getValue().getChangedBy());
    }

    @Test
    @DisplayName("Grade 5: student receives exactly one notification — DEFENSE_FAILED_CAN_REAPPLY, no others (item G)")
    void grade5_sendsExactlyOneNotification() {
        User committee = user(Role.COMMITTEE);
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);
        Thesis thesis = scheduledThesis(student, mentor);
        Defense defense = defenseFor(thesis);
        stubHappyPath(committee, thesis, defense);

        defenseResultService.recordResult(thesis.getId(), defense.getId(), req(5));

        verify(notificationService, times(1)).notify(eq(student), eq(thesis),
                eq(NotificationType.DEFENSE_FAILED_CAN_REAPPLY), anyString());
        // No archive-success notifications for a failed defense.
        verify(notificationService, never()).notify(eq(student), eq(thesis),
                eq(NotificationType.THESIS_GRADED), anyString());
        verify(notificationService, never()).notify(eq(student), eq(thesis),
                eq(NotificationType.THESIS_ARCHIVED), anyString());
        // Exactly one notify call total (any overload), to any recipient, for this thesis.
        verify(notificationService, times(1)).notify(any(), any(), any(), anyString());
        verify(notificationService, never()).notify(any(), any(), any());
    }

    // =========================================================================
    // B. Grade 6 — boundary of the successful range, still ARCHIVED
    // =========================================================================

    @Test
    @DisplayName("Grade 6 (lower successful boundary): status becomes ARCHIVED with full archive metadata")
    void grade6_archivesWithMetadata() {
        User committee = user(Role.COMMITTEE);
        User student = user(Role.STUDENT);
        Thesis thesis = scheduledThesis(student, user(Role.MENTOR));
        Defense defense = defenseFor(thesis);
        stubHappyPath(committee, thesis, defense);
        when(thesisRepository.countByArchiveRegistrationNumberStartingWith(anyString())).thenReturn(0L);

        defenseResultService.recordResult(thesis.getId(), defense.getId(), req(6));

        assertEquals(ThesisStatus.ARCHIVED, thesis.getStatus());
        assertNotNull(thesis.getArchiveRegistrationNumber());
        assertTrue(thesis.getArchiveRegistrationNumber().startsWith("DT-" + OffsetDateTime.now().getYear() + "-"));
        assertNotNull(thesis.getArchiveDate());
        assertEquals(committee, thesis.getArchivedBy());
        verify(notificationService).notify(eq(student), eq(thesis), eq(NotificationType.THESIS_GRADED), anyString());
        verify(notificationService).notify(eq(student), eq(thesis), eq(NotificationType.THESIS_ARCHIVED), anyString());
        verify(notificationService, never()).notify(eq(student), eq(thesis),
                eq(NotificationType.DEFENSE_FAILED_CAN_REAPPLY), anyString());
    }

    // =========================================================================
    // C. Grade 10 — top of the successful range
    // =========================================================================

    @Test
    @DisplayName("Grade 10 (upper boundary): status becomes ARCHIVED with full archive metadata")
    void grade10_archivesWithMetadata() {
        User committee = user(Role.COMMITTEE);
        User student = user(Role.STUDENT);
        Thesis thesis = scheduledThesis(student, user(Role.MENTOR));
        Defense defense = defenseFor(thesis);
        stubHappyPath(committee, thesis, defense);
        when(thesisRepository.countByArchiveRegistrationNumberStartingWith(anyString())).thenReturn(0L);

        defenseResultService.recordResult(thesis.getId(), defense.getId(), req(10));

        assertEquals(ThesisStatus.ARCHIVED, thesis.getStatus());
        assertNotNull(thesis.getArchiveRegistrationNumber());
        assertNotNull(thesis.getArchiveDate());
        verify(notificationService).notify(eq(student), eq(thesis), eq(NotificationType.THESIS_GRADED), anyString());
        verify(notificationService).notify(eq(student), eq(thesis), eq(NotificationType.THESIS_ARCHIVED), anyString());
        verify(notificationService, never()).notify(eq(student), eq(thesis),
                eq(NotificationType.DEFENSE_FAILED_CAN_REAPPLY), anyString());
    }

    // =========================================================================
    // F. Authorization: the grade-based branch never bypasses the committee-seat guard
    // =========================================================================

    @Test
    @DisplayName("An unseated caller cannot record grade 5 either — authorization runs before the outcome branch")
    void grade5_unseatedCaller_rejected() {
        User outsider = user(Role.COMMITTEE);
        Thesis thesis = scheduledThesis(user(Role.STUDENT), user(Role.MENTOR));
        Defense defense = defenseFor(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(outsider);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(committeeRepository.findByThesisAndProfessor(thesis, outsider)).thenReturn(Optional.empty());

        assertThrows(UnauthorizedException.class,
                () -> defenseResultService.recordResult(thesis.getId(), defense.getId(), req(5)));

        assertEquals(ThesisStatus.DEFENSE_SCHEDULED, thesis.getStatus(), "status must stay unchanged");
        verify(resultRepository, never()).save(any());
        verify(statusHistoryRepository, never()).save(any());
        verify(notificationService, never()).notify(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("The STUDENT owner cannot record their own grade 5 (grading is committee-seat-scoped, not owner-scoped)")
    void grade5_studentOwner_rejected() {
        User student = user(Role.STUDENT);
        Thesis thesis = scheduledThesis(student, user(Role.MENTOR));
        Defense defense = defenseFor(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(committeeRepository.findByThesisAndProfessor(thesis, student)).thenReturn(Optional.empty());

        assertThrows(UnauthorizedException.class,
                () -> defenseResultService.recordResult(thesis.getId(), defense.getId(), req(5)));

        assertEquals(ThesisStatus.DEFENSE_SCHEDULED, thesis.getStatus());
        verify(resultRepository, never()).save(any());
    }
}
