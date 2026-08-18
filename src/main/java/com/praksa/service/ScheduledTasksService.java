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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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

    /**
     * Statuses that mean the formal thesis application has NOT yet been successfully
     * submitted. If a thesis is still in one of these when its 1-month submission
     * deadline passes, the reporting job below logs it. (PENDING_ARCHIVE_VALIDATION
     * and everything after it means the application was already submitted in time.)
     */
    private static final Set<ThesisStatus> PRE_APPLICATION_STATUSES = EnumSet.of(
            ThesisStatus.PENDING_ELIGIBILITY_CHECK,
            ThesisStatus.TOPIC_SELECTION,
            ThesisStatus.PENDING_MENTOR_APPROVAL,
            ThesisStatus.MENTOR_REQUESTED_CHANGES,
            ThesisStatus.MENTOR_REJECTED_TOPIC,
            ThesisStatus.APPLICATION_SUBMITTED,
            ThesisStatus.APPLICATION_REJECTED_BY_ARCHIVE,
            ThesisStatus.APPLICATION_REJECTED_BY_SERVICE);

    private final ThesisRepository thesisRepository;
    private final ThesisStatusHistoryRepository statusHistoryRepository;
    private final DefenseRepository defenseRepository;
    private final CommitteeMemberRepository committeeRepository;
    private final NotificationService notificationService;

    /**
     * Max unsent notifications processed per retry run (P2.6). Bounds the batch so a large
     * backlog can never fan out an unbounded number of async sends in one pass.
     */
    @Value("${notification.retry.batch-size:100}")
    private int retryBatchSize;

    /**
     * Minimum age (ms) a notification must reach before the retry job considers it (P2.6).
     * Skipping very new rows avoids racing the normal flow's original async send, which is
     * the smallest reasonable guard against a duplicate email on a freshly created row.
     */
    @Value("${notification.retry.min-age-ms:120000}")
    private long retryMinAgeMs;

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

            // Notify everyone involved that the review was auto-accepted after 5 business
            // days of silence. The mentor holds a MENTOR_MEMBER committee seat (a thesis can
            // only reach COMMITTEE_REVIEW via approveCommittee, which requires an approved
            // 3-member committee including the auto-added mentor), so iterating the committee
            // already notifies the mentor exactly once — mirroring the manual
            // acceptCommitteeReview flow and avoiding a duplicate mentor notification.
            notificationService.notify(thesis.getStudent(), thesis, NotificationType.COMMITTEE_REVIEW_AUTO_ADVANCED);
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
            // Committee — the mentor holds a MENTOR_MEMBER committee seat (a defense can only
            // exist after committee formation + review), so iterating the committee notifies the
            // mentor exactly once. This mirrors scheduleDefense/cancelDefense and avoids the
            // duplicate mentor reminder a separate explicit notify would produce.
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
    // JOB 3 — Report theses that blew past their 1-month submission deadline
    //
    // Runs every 30 minutes. READ-ONLY: it only logs. It does NOT delete theses
    // and does NOT change status — there is no "expired" terminal status in the
    // workflow, and inventing one is out of scope for Item #5. The authoritative
    // enforcement is at submission time (ThesisServiceImpl.submitApplication);
    // this job merely surfaces expired-but-still-pending applications for staff.
    // ─────────────────────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 30 * 60 * 1000, initialDelay = 120 * 1000)
    @Transactional(readOnly = true)
    public void reportExpiredPendingApplications() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Thesis> expired = thesisRepository.findExpiredPendingApplications(now, PRE_APPLICATION_STATUSES);

        if (expired.isEmpty()) {
            log.debug("[ScheduledTasks] No expired pending thesis applications");
            return;
        }

        log.warn("[ScheduledTasks] {} thesis application(s) past the submission deadline and not yet submitted:",
                expired.size());
        for (Thesis thesis : expired) {
            log.warn("[ScheduledTasks]   thesis {} (status {}, deadline {}) — student can no longer submit",
                    thesis.getId(), thesis.getStatus(), thesis.getSubmissionDeadline());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JOB 4 — Retry delivery of unsent notifications (P2.6)
    //
    // Notification rows are created is_sent=false and only flip to true when
    // EmailService actually delivers the email. If a send fails (SMTP down) or mail
    // is disabled when the row is created, it would otherwise stay unsent forever.
    // This job walks a bounded, oldest-first page of unsent rows old enough to have
    // cleared the normal flow's original async send, and re-dispatches each through
    // the SAME EmailService path. It creates NO new rows and marks NOTHING sent
    // itself — EmailService remains the single delivery authority (mail-enabled guard,
    // send, and the is_sent=true write on success only).
    //
    // Interval + batch size + min-age are configurable (notification.retry.*). The
    // default 15-min fixedDelay matches the project's unobtrusive scheduling style.
    // Because fixedDelay (not fixedRate) runs on Spring's single-threaded scheduler,
    // two retry runs never overlap within one instance. Cross-instance duplicate
    // protection would need a shared lock (e.g. ShedLock) — infrastructure not present
    // here — so it is a documented limitation, not a silent gap.
    // ─────────────────────────────────────────────────────────────────────────

    @Scheduled(fixedDelayString = "${notification.retry.interval-ms:900000}",
            initialDelayString = "${notification.retry.initial-delay-ms:150000}")
    public void retryUnsentNotifications() {
        try {
            OffsetDateTime cutoff = OffsetDateTime.now().minus(retryMinAgeMs, ChronoUnit.MILLIS);
            int dispatched = notificationService.retryUnsentNotifications(retryBatchSize, cutoff);
            if (dispatched > 0) {
                log.info("[ScheduledTasks] Notification retry re-dispatched {} unsent email(s)", dispatched);
            } else {
                log.debug("[ScheduledTasks] No unsent notifications eligible for retry");
            }
        } catch (Exception e) {
            // Never let a retry-run failure kill the scheduled thread — the next run should
            // still fire. Per-notification isolation lives in the service; this is a backstop.
            log.error("[ScheduledTasks] Notification retry job failed: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Subtracts N business days (Mon-Fri) from the given timestamp.
     * Simple loop — correct enough for academic deadlines without external library.
     * Weekends (Saturday, Sunday) do not count. Package-private so the working-day
     * math can be unit-tested directly (Item #9).
     */
    OffsetDateTime minusBusinessDays(OffsetDateTime from, long businessDays) {
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
