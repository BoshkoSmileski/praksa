package com.praksa.service;

import com.praksa.model.CommitteeMember;
import com.praksa.model.Thesis;
import com.praksa.model.ThesisStatusHistory;
import com.praksa.model.User;
import com.praksa.model.enums.MemberRole;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.CommitteeMemberRepository;
import com.praksa.repository.DefenseRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Item #9 — orchestration of {@link ScheduledTasksService#autoAdvanceStaleCommitteeReviews}.
 *
 * Pure Mockito (no Spring, no DB). {@code findStaleCommitteeReviews} is mocked to control
 * which theses the job sees; the working-day math itself is covered separately in
 * {@link ScheduledTasksBusinessDaysTest}. Here we assert the transition, the status-history
 * rows, the notifications (exactly once per recipient — no duplicate mentor), and idempotency.
 */
@ExtendWith(MockitoExtension.class)
class AutoAdvanceCommitteeReviewTest {

    @Mock private ThesisRepository thesisRepository;
    @Mock private ThesisStatusHistoryRepository statusHistoryRepository;
    @Mock private DefenseRepository defenseRepository;
    @Mock private CommitteeMemberRepository committeeRepository;
    @Mock private NotificationService notificationService;

    @InjectMocks private ScheduledTasksService scheduledTasks;

    private User user(Role role) {
        return User.builder().id(UUID.randomUUID()).email(role + "@t.com")
                .fullName(role + " User").role(role).build();
    }

    private Thesis staleReviewThesis(User student, User mentor) {
        return Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).mentor(mentor)
                .status(ThesisStatus.COMMITTEE_REVIEW)
                .committeeReviewStartedAt(OffsetDateTime.now().minusDays(30))
                .build();
    }

    @Test
    @DisplayName("Stale review is advanced COMMITTEE_REVIEW → COMMITTEE_ACCEPTED → PENDING_DEFENSE_CHECK with two system history rows")
    void staleReview_isAdvancedWithHistory() {
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);
        Thesis thesis = staleReviewThesis(student, mentor);

        CommitteeMember m0 = CommitteeMember.builder().id(UUID.randomUUID())
                .thesis(thesis).professor(mentor).memberRole(MemberRole.MENTOR_MEMBER).build();

        when(thesisRepository.findStaleCommitteeReviews(any())).thenReturn(List.of(thesis));
        when(committeeRepository.findByThesis(thesis)).thenReturn(List.of(m0));

        scheduledTasks.autoAdvanceStaleCommitteeReviews();

        assertEquals(ThesisStatus.PENDING_DEFENSE_CHECK, thesis.getStatus());

        // Two history rows, in order, both with changedBy == null (system action)
        ArgumentCaptor<ThesisStatusHistory> captor = ArgumentCaptor.forClass(ThesisStatusHistory.class);
        verify(statusHistoryRepository, times(2)).save(captor.capture());
        List<ThesisStatusHistory> rows = captor.getAllValues();

        assertEquals(ThesisStatus.COMMITTEE_REVIEW, rows.get(0).getOldStatus());
        assertEquals(ThesisStatus.COMMITTEE_ACCEPTED, rows.get(0).getNewStatus());
        assertNull(rows.get(0).getChangedBy());

        assertEquals(ThesisStatus.COMMITTEE_ACCEPTED, rows.get(1).getOldStatus());
        assertEquals(ThesisStatus.PENDING_DEFENSE_CHECK, rows.get(1).getNewStatus());
        assertNull(rows.get(1).getChangedBy());
    }

    @Test
    @DisplayName("Auto-advance notifies student + each committee member exactly once — mentor is NOT double-notified")
    void staleReview_notifiesEachRecipientOnce() {
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);
        User prof1 = user(Role.MENTOR);
        User prof2 = user(Role.MENTOR);
        Thesis thesis = staleReviewThesis(student, mentor);

        CommitteeMember m0 = CommitteeMember.builder().id(UUID.randomUUID())
                .thesis(thesis).professor(mentor).memberRole(MemberRole.MENTOR_MEMBER).build();
        CommitteeMember m1 = CommitteeMember.builder().id(UUID.randomUUID())
                .thesis(thesis).professor(prof1).memberRole(MemberRole.FORMAL_MEMBER).build();
        CommitteeMember m2 = CommitteeMember.builder().id(UUID.randomUUID())
                .thesis(thesis).professor(prof2).memberRole(MemberRole.FORMAL_MEMBER).build();

        when(thesisRepository.findStaleCommitteeReviews(any())).thenReturn(List.of(thesis));
        when(committeeRepository.findByThesis(thesis)).thenReturn(List.of(m0, m1, m2));

        scheduledTasks.autoAdvanceStaleCommitteeReviews();

        // Student + 3 committee professors = 4 notifications total, one each.
        verify(notificationService, times(1))
                .notify(student, thesis, NotificationType.COMMITTEE_REVIEW_AUTO_ADVANCED);
        verify(notificationService, times(1))
                .notify(mentor, thesis, NotificationType.COMMITTEE_REVIEW_AUTO_ADVANCED); // exactly once
        verify(notificationService, times(1))
                .notify(prof1, thesis, NotificationType.COMMITTEE_REVIEW_AUTO_ADVANCED);
        verify(notificationService, times(1))
                .notify(prof2, thesis, NotificationType.COMMITTEE_REVIEW_AUTO_ADVANCED);
        verify(notificationService, times(4))
                .notify(any(), eq(thesis), eq(NotificationType.COMMITTEE_REVIEW_AUTO_ADVANCED));
    }

    @Test
    @DisplayName("No stale reviews → nothing is touched")
    void noStaleReviews_noOp() {
        when(thesisRepository.findStaleCommitteeReviews(any())).thenReturn(List.of());

        scheduledTasks.autoAdvanceStaleCommitteeReviews();

        verifyNoInteractions(committeeRepository, notificationService, statusHistoryRepository);
    }

    @Test
    @DisplayName("Defensive re-check: a returned thesis no longer in COMMITTEE_REVIEW is skipped (no history, no notification)")
    void alreadyAdvancedThesis_isSkipped() {
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);
        // Query erroneously returns a thesis that is already past review
        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).mentor(mentor)
                .status(ThesisStatus.PENDING_DEFENSE_CHECK)
                .committeeReviewStartedAt(OffsetDateTime.now().minusDays(30))
                .build();

        when(thesisRepository.findStaleCommitteeReviews(any())).thenReturn(List.of(thesis));

        scheduledTasks.autoAdvanceStaleCommitteeReviews();

        verify(statusHistoryRepository, never()).save(any());
        verify(notificationService, never()).notify(any(), any(), any());
        verify(committeeRepository, never()).findByThesis(any());
    }

    @Test
    @DisplayName("Idempotent: after acceptance the thesis leaves COMMITTEE_REVIEW, so a second run does nothing more")
    void secondRunAfterAcceptance_doesNothingMore() {
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);
        Thesis thesis = staleReviewThesis(student, mentor);

        CommitteeMember m0 = CommitteeMember.builder().id(UUID.randomUUID())
                .thesis(thesis).professor(mentor).memberRole(MemberRole.MENTOR_MEMBER).build();

        // First pass sees it (COMMITTEE_REVIEW); after processing status changes, so the
        // second pass's query no longer returns it (status = 'COMMITTEE_REVIEW' filter).
        when(thesisRepository.findStaleCommitteeReviews(any()))
                .thenReturn(List.of(thesis))
                .thenReturn(List.of());
        when(committeeRepository.findByThesis(thesis)).thenReturn(List.of(m0));

        scheduledTasks.autoAdvanceStaleCommitteeReviews(); // run 1
        scheduledTasks.autoAdvanceStaleCommitteeReviews(); // run 2

        assertEquals(ThesisStatus.PENDING_DEFENSE_CHECK, thesis.getStatus());
        // Still only the first run's effects: 2 history rows, 2 notifications (student + mentor).
        verify(statusHistoryRepository, times(2)).save(any());
        verify(notificationService, times(2))
                .notify(any(), eq(thesis), eq(NotificationType.COMMITTEE_REVIEW_AUTO_ADVANCED));
    }
}
