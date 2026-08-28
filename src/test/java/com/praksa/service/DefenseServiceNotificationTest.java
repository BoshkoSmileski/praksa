package com.praksa.service;

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
import com.praksa.repository.DefenseRequestRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.security.ThesisReadAccessPolicy;
import com.praksa.service.impl.DefenseServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the defense-cancellation notifications (roadmap Item #4).
 *
 * The scheduling notifications (create-proposal / approve / reject) live in
 * {@code DefenseRequestWorkflowTest} alongside the rest of the defense-request redesign,
 * since cancelDefense's own logic and notifications are unchanged by that redesign.
 */
@ExtendWith(MockitoExtension.class)
class DefenseServiceNotificationTest {

    @Mock private DefenseRepository defenseRepository;
    @Mock private DefenseRequestRepository defenseRequestRepository;
    @Mock private ThesisRepository thesisRepository;
    @Mock private ThesisStatusHistoryRepository statusHistoryRepository;
    @Mock private CommitteeMemberRepository committeeRepository;
    @Mock private SecurityUtils securityUtils;
    @Mock private NotificationService notificationService;
    @Mock private ThesisReadAccessPolicy thesisReadAccessPolicy;

    @InjectMocks private DefenseServiceImpl defenseService;

    private User user(Role role) {
        return User.builder().id(UUID.randomUUID()).email(role + "@t.com")
                .fullName(role + " User").role(role).build();
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
        when(thesisRepository.findById(thesis.getId())).thenReturn(java.util.Optional.of(thesis));
        when(defenseRepository.findByThesisAndIsCancelledFalse(thesis)).thenReturn(java.util.Optional.of(defense));
        when(committeeRepository.findByThesis(thesis)).thenReturn(List.of(m0, m1));

        defenseService.cancelDefense(thesis.getId());

        verify(notificationService).notify(eq(student), eq(thesis),
                eq(NotificationType.DEFENSE_CANCELLED), anyString());
        verify(notificationService).notify(eq(mentor), eq(thesis),
                eq(NotificationType.DEFENSE_CANCELLED), anyString());
        verify(notificationService).notify(eq(prof1), eq(thesis),
                eq(NotificationType.DEFENSE_CANCELLED), anyString());
    }
}
