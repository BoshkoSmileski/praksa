package com.praksa.service;

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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2.6 — the scheduled retry job ({@link ScheduledTasksService#retryUnsentNotifications}) is a
 * thin, non-transactional delegator: it computes the age cutoff from the configured min-age and
 * hands the configured batch size to {@link NotificationService#retryUnsentNotifications}. These
 * tests prove the configuration is wired through and that a failure in the delegate does not
 * escape the scheduled method (so the next scheduled run still fires).
 *
 * Pure Mockito, mirroring {@link DefenseReminderNotificationTest}. Spring's scheduler internals
 * are intentionally NOT tested — only this method's own responsibility.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledTasksRetryTest {

    @Mock private ThesisRepository thesisRepository;
    @Mock private ThesisStatusHistoryRepository statusHistoryRepository;
    @Mock private DefenseRepository defenseRepository;
    @Mock private CommitteeMemberRepository committeeRepository;
    @Mock private NotificationService notificationService;

    @InjectMocks private ScheduledTasksService scheduledTasks;

    private void configure(int batchSize, long minAgeMs) {
        ReflectionTestUtils.setField(scheduledTasks, "retryBatchSize", batchSize);
        ReflectionTestUtils.setField(scheduledTasks, "retryMinAgeMs", minAgeMs);
    }

    // ── Test 10 — configured batch size + min-age cutoff are wired through ──
    @Test
    @DisplayName("Test 10 — job passes the configured batch size and a cutoff of ~now-minAge to the service")
    void retry_passesConfiguredBatchSizeAndCutoff() {
        configure(50, 120_000L); // 2 minutes
        when(notificationService.retryUnsentNotifications(anyInt(), any())).thenReturn(0);

        OffsetDateTime before = OffsetDateTime.now();
        scheduledTasks.retryUnsentNotifications();
        OffsetDateTime after = OffsetDateTime.now();

        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(notificationService, times(1)).retryUnsentNotifications(eq(50), cutoff.capture());

        // cutoff = now - 2min, bounded by the now() taken around the call.
        assertThat(cutoff.getValue()).isAfterOrEqualTo(before.minusMinutes(2).minusSeconds(1));
        assertThat(cutoff.getValue()).isBeforeOrEqualTo(after.minusMinutes(2).plusSeconds(1));
    }

    @Test
    @DisplayName("A different configured batch size flows straight through")
    void retry_honorsDifferentBatchSize() {
        configure(10, 60_000L);
        when(notificationService.retryUnsentNotifications(anyInt(), any())).thenReturn(3);

        scheduledTasks.retryUnsentNotifications();

        verify(notificationService).retryUnsentNotifications(eq(10), any());
    }

    // ── Scheduler failure isolation — a delegate failure never escapes the scheduled method ──
    @Test
    @DisplayName("A failure from the delegate is swallowed so the scheduled thread survives for the next run")
    void retry_swallowsDelegateFailure() {
        configure(50, 120_000L);
        when(notificationService.retryUnsentNotifications(anyInt(), any()))
                .thenThrow(new RuntimeException("unexpected"));

        assertThatCode(() -> scheduledTasks.retryUnsentNotifications()).doesNotThrowAnyException();
        verify(notificationService).retryUnsentNotifications(anyInt(), any());
    }
}
