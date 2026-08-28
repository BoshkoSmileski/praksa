package com.praksa.service;

import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.CommitteeMemberRepository;
import com.praksa.repository.DefenseRepository;
import com.praksa.repository.NotificationRepository;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Official faculty procedure — the mentor must review a submitted thesis version within 45
 * days. These tests cover {@link ScheduledTasksService#remindMentorsOfOverdueReviews}: pure
 * Mockito (no Spring, no DB), mirroring {@link DefenseReminderNotificationTest} and
 * {@link AutoAdvanceCommitteeReviewTest}. {@code ThesisRepository.findOverdueMentorReviews} is
 * mocked to control which theses the job sees (the JPQL WHERE clause itself is not exercised
 * here — the project has no embedded test database); the defensive re-checks inside the loop,
 * the notification call, and the dedup logic are what's under test.
 */
@ExtendWith(MockitoExtension.class)
class MentorReviewDeadlineReminderTest {

    @Mock private ThesisRepository thesisRepository;
    @Mock private ThesisStatusHistoryRepository statusHistoryRepository;
    @Mock private DefenseRepository defenseRepository;
    @Mock private CommitteeMemberRepository committeeRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private NotificationService notificationService;

    @InjectMocks private ScheduledTasksService scheduledTasks;

    private User user(Role role) {
        return User.builder().id(UUID.randomUUID()).email(role + "@t.com")
                .fullName(role + " User").role(role).build();
    }

    private Thesis inProgressThesis(User student, User mentor, OffsetDateTime lastVersionSubmittedAt) {
        return Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).mentor(mentor)
                .status(ThesisStatus.IN_PROGRESS)
                .lastVersionSubmittedAt(lastVersionSubmittedAt)
                .build();
    }

    // ── A: no lastVersionSubmittedAt → ignored ──────────────────────────────

    @Test
    @DisplayName("A: thesis with no lastVersionSubmittedAt is ignored (defensive re-check, no crash)")
    void noLastVersionSubmittedAt_ignored() {
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);
        Thesis thesis = inProgressThesis(student, mentor, null);

        when(thesisRepository.findOverdueMentorReviews(any())).thenReturn(List.of(thesis));

        scheduledTasks.remindMentorsOfOverdueReviews();

        verify(notificationService, never()).notify(any(), any(), any());
        verifyNoInteractions(notificationRepository);
    }

    // ── B: less than 45 days ago → no notification ──────────────────────────

    @Test
    @DisplayName("B: no theses returned (submitted less than 45 days ago) → no-op, cutoff is ~45 days back")
    void lessThan45Days_noNotification() {
        when(thesisRepository.findOverdueMentorReviews(any())).thenReturn(List.of());

        OffsetDateTime before = OffsetDateTime.now().minusDays(45);
        scheduledTasks.remindMentorsOfOverdueReviews();
        OffsetDateTime after = OffsetDateTime.now().minusDays(45);

        ArgumentCaptor<OffsetDateTime> cutoffCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(thesisRepository).findOverdueMentorReviews(cutoffCaptor.capture());
        OffsetDateTime cutoff = cutoffCaptor.getValue();

        assertTrue(!cutoff.isBefore(before) && !cutoff.isAfter(after),
                "cutoff should be ~now-45days");
        verifyNoInteractions(notificationService, notificationRepository);
    }

    // ── C: more than 45 days ago → mentor notified ───────────────────────────

    @Test
    @DisplayName("C: IN_PROGRESS thesis whose last version is older than 45 days → mentor is notified")
    void overdueThesis_mentorNotified() {
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);
        Thesis thesis = inProgressThesis(student, mentor, OffsetDateTime.now().minusDays(46));

        when(thesisRepository.findOverdueMentorReviews(any())).thenReturn(List.of(thesis));
        when(notificationRepository.existsByThesisAndUserAndTypeAndCreatedAtAfter(
                eq(thesis), eq(mentor), anyString(), any())).thenReturn(false);

        scheduledTasks.remindMentorsOfOverdueReviews();

        verify(notificationService, times(1))
                .notify(mentor, thesis, NotificationType.MENTOR_REVIEW_DEADLINE_EXCEEDED);
    }

    // ── D: ARCHIVED thesis → ignored ─────────────────────────────────────────

    @Test
    @DisplayName("D: a thesis no longer IN_PROGRESS (e.g. ARCHIVED) is skipped by the defensive re-check")
    void archivedThesis_ignored() {
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);
        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).mentor(mentor)
                .status(ThesisStatus.ARCHIVED)
                .lastVersionSubmittedAt(OffsetDateTime.now().minusDays(90))
                .build();

        when(thesisRepository.findOverdueMentorReviews(any())).thenReturn(List.of(thesis));

        scheduledTasks.remindMentorsOfOverdueReviews();

        verify(notificationService, never()).notify(any(), any(), any());
        verifyNoInteractions(notificationRepository);
    }

    // ── F: scheduler runs twice → no duplicate notification ─────────────────

    @Test
    @DisplayName("F: running the job twice for the same submission cycle does not create a duplicate reminder")
    void secondRun_sameCycle_noDuplicate() {
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);
        Thesis thesis = inProgressThesis(student, mentor, OffsetDateTime.now().minusDays(46));

        when(thesisRepository.findOverdueMentorReviews(any())).thenReturn(List.of(thesis));
        // Run 1: nothing sent yet this cycle → dedup check returns false.
        // Run 2: the reminder from run 1 now exists for this cycle → dedup check returns true.
        when(notificationRepository.existsByThesisAndUserAndTypeAndCreatedAtAfter(
                eq(thesis), eq(mentor),
                eq(NotificationType.MENTOR_REVIEW_DEADLINE_EXCEEDED.name()),
                eq(thesis.getLastVersionSubmittedAt())))
                .thenReturn(false)
                .thenReturn(true);

        scheduledTasks.remindMentorsOfOverdueReviews(); // run 1 — sends
        scheduledTasks.remindMentorsOfOverdueReviews(); // run 2 — deduped, skipped

        verify(notificationService, times(1))
                .notify(mentor, thesis, NotificationType.MENTOR_REVIEW_DEADLINE_EXCEEDED);
        verify(notificationRepository, times(2)).existsByThesisAndUserAndTypeAndCreatedAtAfter(
                eq(thesis), eq(mentor),
                eq(NotificationType.MENTOR_REVIEW_DEADLINE_EXCEEDED.name()),
                eq(thesis.getLastVersionSubmittedAt()));
    }

    // ── G: new version after an old reminder → a future reminder is allowed ─

    @Test
    @DisplayName("G: dedup is scoped to the CURRENT submission cycle — a version reset uses the new cycle start")
    void newVersionAfterOldReminder_futureReminderAllowed() {
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);
        // Represents the thesis AFTER the student uploaded a new version (lastVersionSubmittedAt
        // was reset) and that new version has itself now sat unreviewed for 46 days.
        OffsetDateTime newCycleStart = OffsetDateTime.now().minusDays(46);
        Thesis thesis = inProgressThesis(student, mentor, newCycleStart);

        when(thesisRepository.findOverdueMentorReviews(any())).thenReturn(List.of(thesis));
        // No reminder exists created AFTER the NEW cycle start yet (the old reminder, if any,
        // was created before this newer timestamp and therefore does not count).
        when(notificationRepository.existsByThesisAndUserAndTypeAndCreatedAtAfter(
                eq(thesis), eq(mentor),
                eq(NotificationType.MENTOR_REVIEW_DEADLINE_EXCEEDED.name()),
                eq(newCycleStart)))
                .thenReturn(false);

        scheduledTasks.remindMentorsOfOverdueReviews();

        // The dedup check was performed against the CURRENT (new) cycle start, not a stale one.
        ArgumentCaptor<OffsetDateTime> cycleStartCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(notificationRepository).existsByThesisAndUserAndTypeAndCreatedAtAfter(
                eq(thesis), eq(mentor),
                eq(NotificationType.MENTOR_REVIEW_DEADLINE_EXCEEDED.name()),
                cycleStartCaptor.capture());
        assertTrue(cycleStartCaptor.getValue().isEqual(newCycleStart));

        verify(notificationService, times(1))
                .notify(mentor, thesis, NotificationType.MENTOR_REVIEW_DEADLINE_EXCEEDED);
    }

    // ── H: thesis without a mentor → handled safely ──────────────────────────

    @Test
    @DisplayName("H: thesis without an assigned mentor is skipped safely, no crash")
    void noMentor_handledSafely() {
        User student = user(Role.STUDENT);
        Thesis thesis = inProgressThesis(student, null, OffsetDateTime.now().minusDays(46));

        when(thesisRepository.findOverdueMentorReviews(any())).thenReturn(List.of(thesis));

        assertDoesNotThrow(() -> scheduledTasks.remindMentorsOfOverdueReviews());

        verify(notificationService, never()).notify(any(), any(), any());
        verifyNoInteractions(notificationRepository);
    }

    @Test
    @DisplayName("No overdue theses → no-op, nothing touched")
    void noOverdueTheses_noOp() {
        when(thesisRepository.findOverdueMentorReviews(any())).thenReturn(List.of());

        scheduledTasks.remindMentorsOfOverdueReviews();

        verifyNoInteractions(notificationService, notificationRepository);
    }
}
