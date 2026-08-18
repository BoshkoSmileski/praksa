package com.praksa.service;

import com.praksa.repository.CommitteeMemberRepository;
import com.praksa.repository.DefenseRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Item #9 — the CORE requirement: the auto-advance job must count 5 WORKING DAYS
 * (Monday–Friday), never counting Saturday/Sunday.
 *
 * These tests exercise {@link ScheduledTasksService#minusBusinessDays} directly (the
 * single place the working-day math lives) and then reproduce the EXACT staleness
 * decision the scheduled job makes: a review is stale (→ auto-accepted) when
 * {@code committeeReviewStartedAt <= now - 5 business days}. That comparison is the
 * same one performed by {@code ThesisRepository.findStaleCommitteeReviews} (which uses
 * {@code <=}); asserting it here proves the 5-working-day rule without a database.
 *
 * All fixed dates below are real 2026 calendar dates:
 *   Fri 2026-08-07, Mon 2026-08-10 … Fri 2026-08-14, Mon 2026-08-17.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledTasksBusinessDaysTest {

    private static final long BUSINESS_DAYS = 5;

    @Mock private ThesisRepository thesisRepository;
    @Mock private ThesisStatusHistoryRepository statusHistoryRepository;
    @Mock private DefenseRepository defenseRepository;
    @Mock private CommitteeMemberRepository committeeRepository;
    @Mock private NotificationService notificationService;

    @InjectMocks private ScheduledTasksService scheduledTasks;

    private OffsetDateTime at(int year, int month, int day, int hour, int minute) {
        return OffsetDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC);
    }

    /** Reproduces the job's decision: stale (auto-accept) iff startedAt <= now - 5 business days. */
    private boolean isStale(OffsetDateTime startedAt, OffsetDateTime now) {
        OffsetDateTime cutoff = scheduledTasks.minusBusinessDays(now, BUSINESS_DAYS);
        return !startedAt.isAfter(cutoff); // startedAt <= cutoff
    }

    // ─── The working-day math ────────────────────────────────────────────────

    @Test
    @DisplayName("Friday example: review starts Fri, the 5th working day is the next Fri (Mon..Fri = 1..5)")
    void fridayStart_fifthWorkingDayIsNextFriday() {
        OffsetDateTime friStart = at(2026, 8, 7, 10, 0);   // Friday
        OffsetDateTime nextFriday = at(2026, 8, 14, 10, 0); // Friday, 5 working days later

        assertEquals(DayOfWeek.FRIDAY, friStart.getDayOfWeek());
        assertEquals(DayOfWeek.FRIDAY, nextFriday.getDayOfWeek());

        // now - 5 business days from the next Friday lands exactly back on the start Friday
        assertEquals(friStart, scheduledTasks.minusBusinessDays(nextFriday, 5));
    }

    @Test
    @DisplayName("Weekends do not count: Monday minus 1 working day is the previous Friday")
    void weekendDoesNotCount_mondayMinusOneIsFriday() {
        OffsetDateTime monday = at(2026, 8, 17, 9, 30);
        OffsetDateTime previousFriday = at(2026, 8, 14, 9, 30);

        assertEquals(DayOfWeek.MONDAY, monday.getDayOfWeek());
        // Friday + 1 working day = Monday  ⇔  Monday - 1 working day = Friday
        assertEquals(previousFriday, scheduledTasks.minusBusinessDays(monday, 1));
    }

    @Test
    @DisplayName("minusBusinessDays never lands on a weekend for any 1..10 day offset")
    void minusBusinessDays_neverLandsOnWeekend() {
        OffsetDateTime now = at(2026, 8, 17, 12, 0); // Monday
        for (long d = 1; d <= 10; d++) {
            DayOfWeek dow = scheduledTasks.minusBusinessDays(now, d).getDayOfWeek();
            assertTrue(dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY,
                    "offset " + d + " landed on " + dow);
        }
    }

    // ─── The staleness decision (same comparison as the scheduled job) ────────

    @Test
    @DisplayName("Fewer than 5 working days elapsed → NOT stale (not auto-accepted)")
    void fewerThanFiveWorkingDays_notStale() {
        // Started Mon 08-10; evaluated Fri 08-14 → Tue,Wed,Thu,Fri = 4 working days
        OffsetDateTime started = at(2026, 8, 10, 10, 0);
        OffsetDateTime now = at(2026, 8, 14, 10, 0);
        assertFalse(isStale(started, now));
    }

    @Test
    @DisplayName("Exactly 5 working days elapsed → stale (auto-accepted) — inclusive boundary")
    void exactlyFiveWorkingDays_stale() {
        // Started Fri 08-07; evaluated the next Fri 08-14 at the same time → exactly 5 working days
        OffsetDateTime started = at(2026, 8, 7, 10, 0);
        OffsetDateTime now = at(2026, 8, 14, 10, 0);
        assertTrue(isStale(started, now));
    }

    @Test
    @DisplayName("More than 5 working days elapsed → stale (auto-accepted)")
    void moreThanFiveWorkingDays_stale() {
        // Started Thu 08-06 (a day before the 5-working-day cutoff) → more than 5 working days by 08-14
        OffsetDateTime started = at(2026, 8, 6, 10, 0);
        OffsetDateTime now = at(2026, 8, 14, 10, 0);
        assertTrue(isStale(started, now));
    }

    @Test
    @DisplayName("Weekend in the middle does not shorten the wait: 3 working days across a weekend → NOT stale")
    void weekendInMiddle_stillNotStale() {
        // Started Fri 08-07; evaluated Wed 08-12 → Mon,Tue,Wed = 3 working days (Sat/Sun skipped)
        OffsetDateTime started = at(2026, 8, 7, 10, 0);
        OffsetDateTime now = at(2026, 8, 12, 10, 0);
        assertFalse(isStale(started, now));
    }

    @Test
    @DisplayName("A review that started just after the cutoff instant has not yet waited 5 full working days")
    void oneMinuteAfterCutoff_notStale() {
        OffsetDateTime started = at(2026, 8, 7, 10, 1); // one minute after the 5-working-day cutoff
        OffsetDateTime now = at(2026, 8, 14, 10, 0);
        assertFalse(isStale(started, now));
    }
}
