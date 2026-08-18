package com.praksa.service;

import com.praksa.model.CommitteeMember;
import com.praksa.model.Defense;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.MemberRole;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;
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

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P2 — the defense reminder job ({@link ScheduledTasksService#sendDefenseReminders}) must
 * notify the mentor EXACTLY ONCE. The mentor holds a MENTOR_MEMBER committee seat (a defense
 * can only exist after committee formation + review), so the committee-member loop already
 * covers the mentor — a separate explicit mentor notify would double-notify them. These tests
 * prove the mentor gets a single DEFENSE_REMINDER through the committee path, that the student
 * and each other committee member each get exactly one, and that an empty window is a no-op.
 *
 * Pure Mockito (no Spring, no DB), mirroring {@link AutoAdvanceCommitteeReviewTest}. The reminder
 * time-window query {@code findUpcomingForReminder} is mocked to control which defenses the job
 * sees; the window/timing math is unchanged and not re-derived here.
 */
@ExtendWith(MockitoExtension.class)
class DefenseReminderNotificationTest {

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

    private Thesis scheduledThesis(User student, User mentor) {
        return Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).mentor(mentor)
                .build();
    }

    private Defense upcomingDefense(Thesis thesis) {
        return Defense.builder().id(UUID.randomUUID()).thesis(thesis)
                .room("A1").scheduledAt(OffsetDateTime.now().plusHours(24))
                .isCancelled(false).build();
    }

    private CommitteeMember member(Thesis thesis, User professor, MemberRole role) {
        return CommitteeMember.builder().id(UUID.randomUUID())
                .thesis(thesis).professor(professor).memberRole(role).build();
    }

    @Test
    @DisplayName("Reminder goes to the student, the mentor (once, via MENTOR_MEMBER seat), and each other committee member once")
    void reminder_notifiesStudentMentorAndCommitteeEachOnce() {
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);
        User prof1 = user(Role.MENTOR);
        User prof2 = user(Role.MENTOR);
        Thesis thesis = scheduledThesis(student, mentor);
        Defense defense = upcomingDefense(thesis);

        // The mentor is represented by the MENTOR_MEMBER seat — NOT a separate explicit notify.
        CommitteeMember mentorSeat = member(thesis, mentor, MemberRole.MENTOR_MEMBER);
        CommitteeMember formal1 = member(thesis, prof1, MemberRole.FORMAL_MEMBER);
        CommitteeMember formal2 = member(thesis, prof2, MemberRole.FORMAL_MEMBER);

        when(defenseRepository.findUpcomingForReminder(any(), any())).thenReturn(List.of(defense));
        when(committeeRepository.findByThesis(thesis))
                .thenReturn(List.of(mentorSeat, formal1, formal2));

        scheduledTasks.sendDefenseReminders();

        // Student once.
        verify(notificationService, times(1))
                .notify(student, thesis, NotificationType.DEFENSE_REMINDER);
        // Mentor EXACTLY once — no duplicate.
        verify(notificationService, times(1))
                .notify(mentor, thesis, NotificationType.DEFENSE_REMINDER);
        // Each other committee member once.
        verify(notificationService, times(1))
                .notify(prof1, thesis, NotificationType.DEFENSE_REMINDER);
        verify(notificationService, times(1))
                .notify(prof2, thesis, NotificationType.DEFENSE_REMINDER);
        // Total = student + 3 seated professors (mentor + 2 formal) = 4, no more.
        verify(notificationService, times(4))
                .notify(any(), eq(thesis), eq(NotificationType.DEFENSE_REMINDER));

        // The reminder is stamped so it is not sent again.
        assertNotNull(defense.getReminderSentAt());
        verify(defenseRepository, times(1)).save(defense);
    }

    @Test
    @DisplayName("The mentor is notified only through the committee loop — no second explicit mentor notify path")
    void mentor_notifiedOnlyViaCommitteeLoop() {
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);
        Thesis thesis = scheduledThesis(student, mentor);
        Defense defense = upcomingDefense(thesis);

        // Minimal committee: just the mentor's MENTOR_MEMBER seat.
        when(defenseRepository.findUpcomingForReminder(any(), any())).thenReturn(List.of(defense));
        when(committeeRepository.findByThesis(thesis))
                .thenReturn(List.of(member(thesis, mentor, MemberRole.MENTOR_MEMBER)));

        scheduledTasks.sendDefenseReminders();

        // Mentor exactly once, student exactly once = 2 total. If an explicit mentor notify
        // still existed, the mentor would be hit twice and this count would be 3.
        verify(notificationService, times(1))
                .notify(mentor, thesis, NotificationType.DEFENSE_REMINDER);
        verify(notificationService, times(2))
                .notify(any(), eq(thesis), eq(NotificationType.DEFENSE_REMINDER));
    }

    @Test
    @DisplayName("Reminder window is unchanged: the job queries the [now+23h, now+25h] window (2h span)")
    void reminderWindow_isUnchanged() {
        when(defenseRepository.findUpcomingForReminder(any(), any())).thenReturn(List.of());

        OffsetDateTime before = OffsetDateTime.now();
        scheduledTasks.sendDefenseReminders();
        OffsetDateTime after = OffsetDateTime.now();

        ArgumentCaptor<OffsetDateTime> startCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> endCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(defenseRepository).findUpcomingForReminder(startCaptor.capture(), endCaptor.capture());

        OffsetDateTime windowStart = startCaptor.getValue();
        OffsetDateTime windowEnd = endCaptor.getValue();

        // windowStart = now + 23h, windowEnd = now + 25h — bounded by the now() taken around the call.
        assertTrue(!windowStart.isBefore(before.plusHours(23)) && !windowStart.isAfter(after.plusHours(23)),
                "windowStart should be ~now+23h");
        assertTrue(!windowEnd.isBefore(before.plusHours(25)) && !windowEnd.isAfter(after.plusHours(25)),
                "windowEnd should be ~now+25h");
        // The span between the bounds is exactly 2 hours.
        assertEquals(Duration.ofHours(2), Duration.between(windowStart, windowEnd));
    }

    @Test
    @DisplayName("No defenses in the reminder window → no-op (no notifications, nothing saved)")
    void noUpcomingDefenses_noOp() {
        when(defenseRepository.findUpcomingForReminder(any(), any())).thenReturn(List.of());

        scheduledTasks.sendDefenseReminders();

        verify(defenseRepository, never()).save(any());
        verifyNoInteractions(committeeRepository, notificationService);
    }
}
