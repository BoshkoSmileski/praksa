package com.praksa.service;

import com.praksa.dto.defense.ScheduleDefenseRequest;
import com.praksa.model.CommitteeMember;
import com.praksa.model.Defense;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.MemberRole;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.CommitteeMemberRepository;
import com.praksa.repository.DefenseRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.service.impl.DefenseServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the defense-related notifications added in roadmap Item #4.
 */
@ExtendWith(MockitoExtension.class)
class DefenseServiceNotificationTest {

    @Mock private DefenseRepository defenseRepository;
    @Mock private ThesisRepository thesisRepository;
    @Mock private ThesisStatusHistoryRepository statusHistoryRepository;
    @Mock private CommitteeMemberRepository committeeRepository;
    @Mock private SecurityUtils securityUtils;
    @Mock private NotificationService notificationService;

    @InjectMocks private DefenseServiceImpl defenseService;

    private User user(Role role) {
        return User.builder().id(UUID.randomUUID()).email(role + "@t.com")
                .fullName(role + " User").role(role).build();
    }

    @Test
    @DisplayName("scheduleDefense (by STUDENT_SERVICE) notifies student and every committee member (incl. mentor) with DEFENSE_SCHEDULED + custom message")
    void scheduleDefense_sendsDefenseScheduled() {
        User service = user(Role.STUDENT_SERVICE);
        User mentor = user(Role.MENTOR);
        User student = user(Role.STUDENT);
        User prof1 = user(Role.MENTOR);
        User prof2 = user(Role.MENTOR);

        // Scheduling now starts from PENDING_DEFENSE_SCHEDULING (after a student request).
        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).mentor(mentor).status(ThesisStatus.PENDING_DEFENSE_SCHEDULING).build();

        CommitteeMember m0 = CommitteeMember.builder().id(UUID.randomUUID())
                .thesis(thesis).professor(mentor).memberRole(MemberRole.MENTOR_MEMBER).build();
        CommitteeMember m1 = CommitteeMember.builder().id(UUID.randomUUID())
                .thesis(thesis).professor(prof1).memberRole(MemberRole.FORMAL_MEMBER).build();
        CommitteeMember m2 = CommitteeMember.builder().id(UUID.randomUUID())
                .thesis(thesis).professor(prof2).memberRole(MemberRole.FORMAL_MEMBER).build();

        ScheduleDefenseRequest req = new ScheduleDefenseRequest();
        req.setRoom("A1");
        req.setScheduledAt(OffsetDateTime.now().plusDays(10));

        when(securityUtils.getCurrentUser()).thenReturn(service);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(defenseRepository.findByThesisAndIsCancelledFalse(thesis)).thenReturn(Optional.empty());
        when(committeeRepository.findByThesis(thesis)).thenReturn(List.of(m0, m1, m2));

        defenseService.scheduleDefense(thesis.getId(), req);

        verify(notificationService).notify(eq(student), eq(thesis),
                eq(NotificationType.DEFENSE_SCHEDULED), anyString());
        verify(notificationService).notify(eq(mentor), eq(thesis),
                eq(NotificationType.DEFENSE_SCHEDULED), anyString());
        verify(notificationService).notify(eq(prof1), eq(thesis),
                eq(NotificationType.DEFENSE_SCHEDULED), anyString());
        verify(notificationService).notify(eq(prof2), eq(thesis),
                eq(NotificationType.DEFENSE_SCHEDULED), anyString());
    }

    @Test
    @DisplayName("cancelDefense notifies student and every committee member with DEFENSE_CANCELLED + custom message")
    void cancelDefense_sendsDefenseCancelled() {
        User mentor = user(Role.MENTOR);
        User student = user(Role.STUDENT);
        User prof1 = user(Role.MENTOR);

        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).mentor(mentor).status(ThesisStatus.DEFENSE_SCHEDULED).build();

        Defense defense = Defense.builder().id(UUID.randomUUID()).thesis(thesis)
                .room("A1").scheduledAt(OffsetDateTime.now().plusDays(5)).isCancelled(false).build();

        CommitteeMember m0 = CommitteeMember.builder().id(UUID.randomUUID())
                .thesis(thesis).professor(mentor).memberRole(MemberRole.MENTOR_MEMBER).build();
        CommitteeMember m1 = CommitteeMember.builder().id(UUID.randomUUID())
                .thesis(thesis).professor(prof1).memberRole(MemberRole.FORMAL_MEMBER).build();

        // Mentor cancels
        when(securityUtils.getCurrentUser()).thenReturn(mentor);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(defenseRepository.findByThesisAndIsCancelledFalse(thesis)).thenReturn(Optional.of(defense));
        when(committeeRepository.findByThesis(thesis)).thenReturn(List.of(m0, m1));

        defenseService.cancelDefense(thesis.getId());

        verify(notificationService).notify(eq(student), eq(thesis),
                eq(NotificationType.DEFENSE_CANCELLED), anyString());
        verify(notificationService).notify(eq(mentor), eq(thesis),
                eq(NotificationType.DEFENSE_CANCELLED), anyString());
        verify(notificationService).notify(eq(prof1), eq(thesis),
                eq(NotificationType.DEFENSE_CANCELLED), anyString());
    }

    @Test
    @DisplayName("scheduleDefense blocked by an existing active defense sends NO notification")
    void scheduleDefense_activeExists_noNotification() {
        User service = user(Role.STUDENT_SERVICE);
        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(user(Role.STUDENT)).mentor(user(Role.MENTOR))
                .status(ThesisStatus.DEFENSE_SCHEDULED).build();

        Defense existing = Defense.builder().id(UUID.randomUUID()).thesis(thesis)
                .room("A1").scheduledAt(OffsetDateTime.now().plusDays(5)).isCancelled(false).build();

        ScheduleDefenseRequest req = new ScheduleDefenseRequest();
        req.setRoom("B2");
        req.setScheduledAt(OffsetDateTime.now().plusDays(11));

        when(securityUtils.getCurrentUser()).thenReturn(service);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(defenseRepository.findByThesisAndIsCancelledFalse(thesis)).thenReturn(Optional.of(existing));

        org.junit.jupiter.api.Assertions.assertThrows(
                com.praksa.exception.BadRequestException.class,
                () -> defenseService.scheduleDefense(thesis.getId(), req));

        verify(notificationService, never()).notify(any(), any(), any());
        verify(notificationService, never()).notify(any(), any(), any(), anyString());
    }
}
