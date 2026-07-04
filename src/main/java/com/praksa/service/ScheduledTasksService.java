package com.praksa.service;

import com.praksa.model.CommitteeMember;
import com.praksa.model.Defense;
import com.praksa.model.Thesis;
import com.praksa.model.ThesisStatusHistory;
import com.praksa.model.User;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.CommitteeMemberRepository;
import com.praksa.repository.DefenseRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Background tasks that run on a fixed schedule.
 *
 * Each task is its own @Transactional method so a failure in one doesn't roll back the others.
 * Runs are logged at INFO level — easy to see in the application log.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledTasksService {

    private static final long COMMITTEE_REVIEW_BUSINESS_DAYS = 5;
    private static final long DEFENSE_REMINDER_WINDOW_HOURS_MIN = 23;
    private static final long DEFENSE_REMINDER_WINDOW_HOURS_MAX = 25;

    private final ThesisRepository thesisRepository;
    private final ThesisStatusHistoryRepository statusHistoryRepository;
    private final DefenseRepository defenseRepository;
    private final CommitteeMemberRepository committeeRepository;
    private final NotificationService notificationService;

    // ─────────────────────────────────────────────────────────────────────────
    // JOB 1 — Auto-advance committee review after 5 business days of silence
    //
    // Runs every 30 minutes. Idempotent: a stale review found twice
    // simply gets advanced once (the second pass finds status != COMMITTEE_REVIEW).
    // ─────────────────────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 30 * 60 * 1000, initialDelay = 60 * 1000)
    @Transactional
    public void autoAdvanceStaleCommitteeReviews() {
        OffsetDateTime cutoff = minusBusinessDays(OffsetDateTime.now(), COMMITTEE_REVIEW_BUSINESS_DAYS);
        List<Thesis> stale = thesisRepository.findStaleCommitteeReviews(cutoff);

        if (stale.isEmpty()) {
            log.debug("[ScheduledTasks] No stale committee reviews");
            return;
        }

        log.info("[ScheduledTasks] Auto-advancing {} stale committee review(s) (cutoff: {})",
                stale.size(), cutoff);

        for (Thesis thesis : stale) {
            // Defensive re-check inside the loop
            if (thesis.getStatus() != ThesisStatus.COMMITTEE_REVIEW) continue;

            // Advance through COMMITTEE_ACCEPTED → PENDING_DEFENSE_CHECK
            // changedBy is null here — the system did this automatically
            recordTransition(thesis, ThesisStatus.COMMITTEE_ACCEPTED);
            recordTransition(thesis, ThesisStatus.PENDING_DEFENSE_CHECK);

            // Notify everyone involved
            notificationService.notify(thesis.getStudent(), thesis, NotificationType.COMMITTEE_REVIEW_AUTO_ADVANCED);
            if (thesis.getMentor() != null) {
                notificationService.notify(thesis.getMentor(), thesis, NotificationType.COMMITTEE_REVIEW_AUTO_ADVANCED);
            }
            for (CommitteeMember m : committeeRepository.findByThesis(thesis)) {
                notificationService.notify(m.getProfessor(), thesis, NotificationType.COMMITTEE_REVIEW_AUTO_ADVANCED);
            }

            log.info("[ScheduledTasks] Thesis {} auto-advanced past committee review", thesis.getId());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JOB 2 — Send defense reminder 24h before scheduledAt
    //
    // Runs every 30 minutes. Looks at the window [now+23h, now+25h] for
    // defenses without a reminder yet. The 2h window plus the 30-min job
    // interval guarantees every upcoming defense gets exactly one reminder.
    // ─────────────────────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 30 * 60 * 1000, initialDelay = 90 * 1000)
    @Transactional
    public void sendDefenseReminders() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime windowStart = now.plusHours(DEFENSE_REMINDER_WINDOW_HOURS_MIN);
        OffsetDateTime windowEnd = now.plusHours(DEFENSE_REMINDER_WINDOW_HOURS_MAX);

        List<Defense> upcoming = defenseRepository.findUpcomingForReminder(windowStart, windowEnd);

        if (upcoming.isEmpty()) {
            log.debug("[ScheduledTasks] No defenses needing reminders");
            return;
        }

        log.info("[ScheduledTasks] Sending reminders for {} upcoming defense(s)", upcoming.size());

        for (Defense defense : upcoming) {
            Thesis thesis = defense.getThesis();

            // Student
            notificationService.notify(thesis.getStudent(), thesis, NotificationType.DEFENSE_REMINDER);
            // Mentor
            if (thesis.getMentor() != null) {
                notificationService.notify(thesis.getMentor(), thesis, NotificationType.DEFENSE_REMINDER);
            }
            // Committee
            for (CommitteeMember m : committeeRepository.findByThesis(thesis)) {
                notificationService.notify(m.getProfessor(), thesis, NotificationType.DEFENSE_REMINDER);
            }

            defense.setReminderSentAt(now);
            defenseRepository.save(defense);

            log.info("[ScheduledTasks] Reminder sent for defense {} of thesis {}",
                    defense.getId(), thesis.getId());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Subtracts N business days (Mon-Fri) from the given timestamp.
     * Simple loop — correct enough for academic deadlines without external library.
     */
    private OffsetDateTime minusBusinessDays(OffsetDateTime from, long businessDays) {
        OffsetDateTime result = from;
        long remaining = businessDays;
        while (remaining > 0) {
            result = result.minusDays(1);
            DayOfWeek dow = result.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                remaining--;
            }
        }
        return result;
    }

    /**
     * Local helper to record a status change without going through ThesisServiceImpl
     * (would create a circular dependency). System-triggered changes have changedBy = null.
     */
    private void recordTransition(Thesis thesis, ThesisStatus newStatus) {
        ThesisStatus oldStatus = thesis.getStatus();
        thesis.setStatus(newStatus);
        thesisRepository.save(thesis);

        ThesisStatusHistory history = ThesisStatusHistory.builder()
                .thesis(thesis)
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .changedBy((User) null)  // null = system action
                .build();
        statusHistoryRepository.save(history);
    }
}
