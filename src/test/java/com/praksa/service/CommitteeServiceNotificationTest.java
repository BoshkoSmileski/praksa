package com.praksa.service;

import com.praksa.dto.committee.CommitteeMemberResponse;
import com.praksa.exception.BadRequestException;
import com.praksa.model.CommitteeMember;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.MemberRole;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.CommitteeMemberRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.repository.UserRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.service.impl.CommitteeServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the committee-related notifications added in roadmap Item #4.
 *
 * Pure Mockito — no Spring context, no database. We only assert that the correct
 * NotificationService calls happen with the correct recipients and types, and that
 * a failed operation produces no notification.
 */
@ExtendWith(MockitoExtension.class)
class CommitteeServiceNotificationTest {

    @Mock private CommitteeMemberRepository committeeRepository;
    @Mock private ThesisRepository thesisRepository;
    @Mock private ThesisStatusHistoryRepository statusHistoryRepository;
    @Mock private UserRepository userRepository;
    @Mock private SecurityUtils securityUtils;
    @Mock private NotificationService notificationService;

    @InjectMocks private CommitteeServiceImpl committeeService;

    private User user(Role role) {
        return User.builder().id(UUID.randomUUID()).email(role + "@t.com")
                .fullName(role + " User").role(role).build();
    }

    @Test
    @DisplayName("approveCommittee notifies every seated professor with COMMITTEE_FORMED and the student with a custom message")
    void approveCommittee_sendsCommitteeFormed() {
        User admin = user(Role.STUDENT_SERVICE);
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);
        User prof1 = user(Role.MENTOR);
        User prof2 = user(Role.MENTOR);

        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).mentor(mentor).status(ThesisStatus.MENTOR_APPROVED).build();

        CommitteeMember m0 = CommitteeMember.builder().id(UUID.randomUUID())
                .thesis(thesis).professor(mentor).memberRole(MemberRole.MENTOR_MEMBER).build();
        CommitteeMember m1 = CommitteeMember.builder().id(UUID.randomUUID())
                .thesis(thesis).professor(prof1).memberRole(MemberRole.FORMAL_MEMBER).build();
        CommitteeMember m2 = CommitteeMember.builder().id(UUID.randomUUID())
                .thesis(thesis).professor(prof2).memberRole(MemberRole.FORMAL_MEMBER).build();

        when(securityUtils.getCurrentUser()).thenReturn(admin);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(committeeRepository.countByThesis(thesis)).thenReturn(3L);
        when(committeeRepository.findByThesis(thesis)).thenReturn(List.of(m0, m1, m2));

        List<CommitteeMemberResponse> result = committeeService.approveCommittee(thesis.getId());

        // Every professor (mentor + 2 formal) gets COMMITTEE_FORMED exactly once
        verify(notificationService).notify(mentor, thesis, NotificationType.COMMITTEE_FORMED);
        verify(notificationService).notify(prof1, thesis, NotificationType.COMMITTEE_FORMED);
        verify(notificationService).notify(prof2, thesis, NotificationType.COMMITTEE_FORMED);
        // Student gets a student-appropriate custom message
        verify(notificationService).notify(eq(student), eq(thesis),
                eq(NotificationType.COMMITTEE_FORMED), anyString());

        // Sanity: 3 members returned
        assert result.size() == 3;
    }

    @Test
    @DisplayName("approveCommittee with wrong member count throws and sends NO notification")
    void approveCommittee_wrongCount_noNotification() {
        User admin = user(Role.STUDENT_SERVICE);
        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(user(Role.STUDENT)).mentor(user(Role.MENTOR))
                .status(ThesisStatus.MENTOR_APPROVED).build();

        when(securityUtils.getCurrentUser()).thenReturn(admin);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(committeeRepository.countByThesis(thesis)).thenReturn(2L); // invalid

        assertThrows(BadRequestException.class, () -> committeeService.approveCommittee(thesis.getId()));

        verify(notificationService, never())
                .notify(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        verify(notificationService, never())
                .notify(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), anyString());
    }

    @Test
    @DisplayName("acceptCommitteeReview notifies student and every committee member with COMMITTEE_REVIEW_ACCEPTED")
    void acceptCommitteeReview_sendsReviewAccepted() {
        User admin = user(Role.STUDENT_SERVICE);
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);
        User prof1 = user(Role.MENTOR);
        User prof2 = user(Role.MENTOR);

        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).mentor(mentor).status(ThesisStatus.COMMITTEE_REVIEW).build();

        CommitteeMember m0 = CommitteeMember.builder().id(UUID.randomUUID())
                .thesis(thesis).professor(mentor).memberRole(MemberRole.MENTOR_MEMBER).build();
        CommitteeMember m1 = CommitteeMember.builder().id(UUID.randomUUID())
                .thesis(thesis).professor(prof1).memberRole(MemberRole.FORMAL_MEMBER).build();
        CommitteeMember m2 = CommitteeMember.builder().id(UUID.randomUUID())
                .thesis(thesis).professor(prof2).memberRole(MemberRole.FORMAL_MEMBER).build();

        when(securityUtils.getCurrentUser()).thenReturn(admin);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(committeeRepository.findByThesis(thesis)).thenReturn(List.of(m0, m1, m2));

        committeeService.acceptCommitteeReview(thesis.getId());

        verify(notificationService).notify(student, thesis, NotificationType.COMMITTEE_REVIEW_ACCEPTED);
        verify(notificationService).notify(mentor, thesis, NotificationType.COMMITTEE_REVIEW_ACCEPTED);
        verify(notificationService).notify(prof1, thesis, NotificationType.COMMITTEE_REVIEW_ACCEPTED);
        verify(notificationService).notify(prof2, thesis, NotificationType.COMMITTEE_REVIEW_ACCEPTED);
    }
}
