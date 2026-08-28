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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the grading/archive notifications added in roadmap Item #4.
 */
@ExtendWith(MockitoExtension.class)
class DefenseResultServiceNotificationTest {

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

    /** A voting (non-external) committee seat for {@code recorder} on {@code thesis}. */
    private CommitteeMember votingSeat(Thesis thesis, User recorder) {
        return CommitteeMember.builder().id(UUID.randomUUID()).thesis(thesis).professor(recorder)
                .memberRole(MemberRole.FORMAL_MEMBER).isExternalNonVoting(false).build();
    }

    @Test
    @DisplayName("recordResult notifies the student with THESIS_GRADED (incl. grade) and THESIS_ARCHIVED")
    void recordResult_sendsGradedAndArchived() {
        User committee = user(Role.COMMITTEE);
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);

        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).mentor(mentor).status(ThesisStatus.DEFENSE_SCHEDULED).build();

        Defense defense = Defense.builder().id(UUID.randomUUID()).thesis(thesis)
                .room("A1").scheduledAt(OffsetDateTime.now().minusDays(1)).isCancelled(false).build();

        RecordResultRequest req = new RecordResultRequest();
        req.setGrade(9);
        req.setNotes("Well done");

        when(securityUtils.getCurrentUser()).thenReturn(committee);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        // Recorder is a seated VOTING committee member of THIS thesis (write-side grading authorization).
        when(committeeRepository.findByThesisAndProfessor(thesis, committee))
                .thenReturn(Optional.of(votingSeat(thesis, committee)));
        when(defenseRepository.findById(defense.getId())).thenReturn(Optional.of(defense));
        when(resultRepository.findByDefense(defense)).thenReturn(Optional.empty());
        when(thesisRepository.countByArchiveRegistrationNumberStartingWith(anyString())).thenReturn(0L);

        defenseResultService.recordResult(thesis.getId(), defense.getId(), req);

        verify(notificationService).notify(eq(student), eq(thesis),
                eq(NotificationType.THESIS_GRADED), anyString());
        verify(notificationService).notify(eq(student), eq(thesis),
                eq(NotificationType.THESIS_ARCHIVED), anyString());
        // Thesis is archived by the time the notifications go out
        assert thesis.getStatus() == ThesisStatus.ARCHIVED;
        assert thesis.getArchiveRegistrationNumber() != null;
    }

    @Test
    @DisplayName("recordResult on a cancelled defense throws and sends NO notification")
    void recordResult_cancelledDefense_noNotification() {
        User committee = user(Role.COMMITTEE);
        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(user(Role.STUDENT)).mentor(user(Role.MENTOR))
                .status(ThesisStatus.DEFENSE_SCHEDULED).build();

        Defense cancelled = Defense.builder().id(UUID.randomUUID()).thesis(thesis)
                .room("A1").scheduledAt(OffsetDateTime.now().minusDays(1)).isCancelled(true).build();

        RecordResultRequest req = new RecordResultRequest();
        req.setGrade(8);

        when(securityUtils.getCurrentUser()).thenReturn(committee);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        // Seated voting committee member: authorization passes, so the cancelled-defense rule
        // is what rejects.
        when(committeeRepository.findByThesisAndProfessor(thesis, committee))
                .thenReturn(Optional.of(votingSeat(thesis, committee)));
        when(defenseRepository.findById(cancelled.getId())).thenReturn(Optional.of(cancelled));

        assertThrows(BadRequestException.class,
                () -> defenseResultService.recordResult(thesis.getId(), cancelled.getId(), req));

        verify(notificationService, never()).notify(any(), any(), any());
        verify(notificationService, never()).notify(any(), any(), any(), anyString());
    }
}
