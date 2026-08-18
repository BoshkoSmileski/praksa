package com.praksa.service;

import com.praksa.model.Notification;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.NotificationRepository;
import com.praksa.repository.UserRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.service.impl.NotificationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P2.6 — notification retry. Exercises {@link NotificationServiceImpl#retryUnsentNotifications}
 * with a REAL {@link EmailService} (only its collaborators are mocked) so the genuine
 * delivery semantics are proven, not stubbed:
 *   success  → EmailService marks the SAME row is_sent=true,
 *   failure  → row stays is_sent=false, batch continues,
 *   disabled → no SMTP touched, nothing marked sent.
 *
 * Pure Mockito (no Spring). Because @Async is inert without a Spring proxy, the real
 * {@code sendAsync} runs inline here — so the mark-sent write is observable synchronously,
 * which is exactly what lets these tests assert real outcomes rather than "a mock was called".
 *
 * The single shared {@link NotificationRepository} mock mirrors production, where both the
 * retry query and EmailService's mark-sent write hit the same bean.
 */
@ExtendWith(MockitoExtension.class)
class NotificationRetryServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private UserRepository userRepository;
    @Mock private JavaMailSender mailSender;
    @Mock private SecurityUtils securityUtils;

    private EmailService emailService;
    private NotificationServiceImpl notificationService;

    @BeforeEach
    void setUp() {
        // Real EmailService — same NotificationRepository mock it uses in production.
        emailService = new EmailService(mailSender, notificationRepository);
        ReflectionTestUtils.setField(emailService, "fromAddress", "no-reply@test.local");
        notificationService = new NotificationServiceImpl(
                notificationRepository, userRepository, emailService, securityUtils);
    }

    private void mailEnabled(boolean enabled) {
        ReflectionTestUtils.setField(emailService, "mailEnabled", enabled);
    }

    private User user(String email) {
        return User.builder().id(UUID.randomUUID()).email(email)
                .fullName("Full " + email).role(Role.STUDENT).build();
    }

    private Thesis thesis() {
        return Thesis.builder().id(UUID.randomUUID()).title("Some Thesis")
                .status(ThesisStatus.DEFENSE_SCHEDULED).build();
    }

    private Notification unsent(User recipient, Thesis thesis, NotificationType type) {
        return Notification.builder()
                .id(UUID.randomUUID())
                .user(recipient)
                .thesis(thesis)
                .type(type.name())
                .isSent(false)
                .createdAt(OffsetDateTime.now().minusMinutes(10))
                .build();
    }

    // ── Test 1 — an unsent notification is discovered and the email path is invoked ──
    @Test
    @DisplayName("Test 1/6 — an unsent notification is queried (bounded, age-filtered) and its email is dispatched")
    void unsentNotification_isDiscoveredAndDispatched() {
        mailEnabled(true);
        Notification n = unsent(user("s@test.local"), thesis(), NotificationType.DEFENSE_SCHEDULED);
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(2);
        when(notificationRepository.findByIsSentFalseAndCreatedAtBeforeOrderByCreatedAtAsc(eq(cutoff), any(Pageable.class)))
                .thenReturn(List.of(n));
        when(notificationRepository.findById(n.getId())).thenReturn(Optional.of(n));

        int dispatched = notificationService.retryUnsentNotifications(50, cutoff);

        // The bounded, age-filtered retry query was used (NOT findAll / findByIsSentFalse).
        verify(notificationRepository).findByIsSentFalseAndCreatedAtBeforeOrderByCreatedAtAsc(eq(cutoff), any(Pageable.class));
        // The real email path ran.
        verify(mailSender).send(any(SimpleMailMessage.class));
        assertThat(dispatched).isEqualTo(1);
    }

    // ── Test 2 — successful retry marks the SAME row sent via EmailService semantics ──
    @Test
    @DisplayName("Test 2 — successful send marks the notification is_sent=true (real EmailService), recipient/subject correct")
    void successfulRetry_marksNotificationSent() {
        mailEnabled(true);
        User recipient = user("student@test.local");
        Notification n = unsent(recipient, thesis(), NotificationType.THESIS_GRADED);
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(2);
        when(notificationRepository.findByIsSentFalseAndCreatedAtBeforeOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(n));
        when(notificationRepository.findById(n.getId())).thenReturn(Optional.of(n));

        notificationService.retryUnsentNotifications(50, cutoff);

        ArgumentCaptor<SimpleMailMessage> msg = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(msg.capture());
        assertThat(msg.getValue().getTo()).containsExactly("student@test.local");
        assertThat(msg.getValue().getSubject()).contains(NotificationType.THESIS_GRADED.getSubject());
        // Retry used the type's default body (custom message is not persisted).
        assertThat(msg.getValue().getText()).contains(NotificationType.THESIS_GRADED.getDefaultBody());

        // The SAME row is now sent — proven by the real EmailService write, not by a mock call.
        assertThat(n.isSent()).isTrue();
        assertThat(n.getSentAt()).isNotNull();
    }

    // ── Test 3 / Test 9 — one failure does not stop the rest of the batch ──
    @Test
    @DisplayName("Test 3/9 — an SMTP failure on one notification leaves it unsent and the next is still processed")
    void failedRetry_isIsolated_batchContinues() {
        mailEnabled(true);
        User r1 = user("first@test.local");
        User r2 = user("second@test.local");
        Notification n1 = unsent(r1, thesis(), NotificationType.DEFENSE_SCHEDULED);
        Notification n2 = unsent(r2, thesis(), NotificationType.THESIS_ARCHIVED);
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(2);
        when(notificationRepository.findByIsSentFalseAndCreatedAtBeforeOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(n1, n2));
        // First send blows up (SMTP), second succeeds.
        doThrow(new MailSendException("SMTP down")).doNothing()
                .when(mailSender).send(any(SimpleMailMessage.class));
        when(notificationRepository.findById(n2.getId())).thenReturn(Optional.of(n2));

        int dispatched = notificationService.retryUnsentNotifications(50, cutoff);

        // Both were attempted; the job did not crash.
        verify(mailSender, times(2)).send(any(SimpleMailMessage.class));
        assertThat(dispatched).isEqualTo(2);
        // Failed one stays unsent; successful one is marked sent.
        assertThat(n1.isSent()).isFalse();
        assertThat(n2.isSent()).isTrue();
        // The failed row was never looked up for a mark-sent write.
        verify(notificationRepository, never()).findById(n1.getId());
    }

    // ── Test 9 (loop-level) — a malformed row (unknown type) is skipped, others proceed ──
    @Test
    @DisplayName("Test 9 — a row with an unparseable type is skipped without aborting the batch")
    void malformedRow_isSkipped_batchContinues() {
        mailEnabled(true);
        User good = user("good@test.local");
        Notification bad = Notification.builder().id(UUID.randomUUID())
                .user(user("bad@test.local")).thesis(thesis())
                .type("NOT_A_REAL_TYPE").isSent(false)
                .createdAt(OffsetDateTime.now().minusMinutes(10)).build();
        Notification ok = unsent(good, thesis(), NotificationType.DEFENSE_SCHEDULED);
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(2);
        when(notificationRepository.findByIsSentFalseAndCreatedAtBeforeOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(bad, ok));
        when(notificationRepository.findById(ok.getId())).thenReturn(Optional.of(ok));

        int dispatched = notificationService.retryUnsentNotifications(50, cutoff);

        // Only the valid row was dispatched; the bad one was isolated.
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        assertThat(dispatched).isEqualTo(1);
        assertThat(ok.isSent()).isTrue();
    }

    // ── Test 4 — already-sent rows are excluded by construction (the query filters them) ──
    @Test
    @DisplayName("Test 4 — retry uses the is_sent=false query, so already-sent rows are never processed")
    void alreadySent_notRetried() {
        mailEnabled(true);
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(2);
        // The isSentFalse query returns only unsent rows — a sent one can never appear here.
        when(notificationRepository.findByIsSentFalseAndCreatedAtBeforeOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of());

        int dispatched = notificationService.retryUnsentNotifications(50, cutoff);

        verify(notificationRepository).findByIsSentFalseAndCreatedAtBeforeOrderByCreatedAtAsc(any(), any());
        // The unbounded / all-rows queries are never used by retry.
        verify(notificationRepository, never()).findByIsSentFalse();
        verify(notificationRepository, never()).findAll();
        assertThat(dispatched).isZero();
    }

    // ── Test 5 — empty queue → no email attempts, no exception ──
    @Test
    @DisplayName("Test 5 — no eligible unsent notifications → no-op (no send, nothing saved)")
    void emptyQueue_noOp() {
        mailEnabled(true);
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(2);
        when(notificationRepository.findByIsSentFalseAndCreatedAtBeforeOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of());

        int dispatched = notificationService.retryUnsentNotifications(50, cutoff);

        verifyNoInteractions(mailSender);
        verify(notificationRepository, never()).save(any());
        assertThat(dispatched).isZero();
    }

    // ── Test 6 — multiple notifications: each processed once, correct recipient, no dup rows ──
    @Test
    @DisplayName("Test 6 — multiple unsent notifications are each dispatched once to their correct recipient")
    void multipleNotifications_eachProcessedOnceToCorrectRecipient() {
        mailEnabled(true);
        User r1 = user("a@test.local");
        User r2 = user("b@test.local");
        User r3 = user("c@test.local");
        Notification n1 = unsent(r1, thesis(), NotificationType.DEFENSE_SCHEDULED);
        Notification n2 = unsent(r2, thesis(), NotificationType.THESIS_GRADED);
        Notification n3 = unsent(r3, thesis(), NotificationType.THESIS_ARCHIVED);
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(2);
        when(notificationRepository.findByIsSentFalseAndCreatedAtBeforeOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(n1, n2, n3));
        when(notificationRepository.findById(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            if (id.equals(n1.getId())) return Optional.of(n1);
            if (id.equals(n2.getId())) return Optional.of(n2);
            if (id.equals(n3.getId())) return Optional.of(n3);
            return Optional.empty();
        });

        int dispatched = notificationService.retryUnsentNotifications(50, cutoff);

        assertThat(dispatched).isEqualTo(3);
        ArgumentCaptor<SimpleMailMessage> msgs = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(3)).send(msgs.capture());
        assertThat(msgs.getAllValues().stream()
                .map(m -> m.getTo() == null ? null : m.getTo()[0]).toList())
                .containsExactlyInAnyOrder("a@test.local", "b@test.local", "c@test.local");
        // All three flipped to sent.
        assertThat(n1.isSent()).isTrue();
        assertThat(n2.isSent()).isTrue();
        assertThat(n3.isSent()).isTrue();
    }

    // ── Test 7 — mail disabled: nothing sent, nothing marked sent ──
    @Test
    @DisplayName("Test 7 — mail disabled: retry attempts NO SMTP and marks NOTHING sent (rows stay unsent)")
    void mailDisabled_neverMarksSent() {
        mailEnabled(false);
        User recipient = user("student@test.local");
        Notification n = unsent(recipient, thesis(), NotificationType.DEFENSE_SCHEDULED);
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(2);
        when(notificationRepository.findByIsSentFalseAndCreatedAtBeforeOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(n));

        notificationService.retryUnsentNotifications(50, cutoff);

        // Disabled path (real EmailService): no SMTP connection, no mark-sent write.
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        verify(notificationRepository, never()).save(any());
        assertThat(n.isSent()).isFalse();
    }

    // ── Test 8 — retry never creates a new Notification row ──
    @Test
    @DisplayName("Test 8 — retry never creates a new Notification row (the only save is EmailService's mark-sent of the SAME row)")
    void retry_neverCreatesNewNotificationRow() {
        mailEnabled(true);
        User recipient = user("student@test.local");
        Notification n = unsent(recipient, thesis(), NotificationType.DEFENSE_SCHEDULED);
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(2);
        when(notificationRepository.findByIsSentFalseAndCreatedAtBeforeOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(n));
        when(notificationRepository.findById(n.getId())).thenReturn(Optional.of(n));

        notificationService.retryUnsentNotifications(50, cutoff);

        // The only save is the mark-sent of the SAME persisted entity (same id) by EmailService —
        // never a brand-new Notification.
        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(n.getId());
        assertThat(saved.getValue()).isSameAs(n);
    }

    @Test
    @DisplayName("Test 8b — mail disabled: retry performs ZERO saves (no row created, none marked sent)")
    void retry_disabled_performsNoSaves() {
        mailEnabled(false);
        Notification n = unsent(user("s@test.local"), thesis(), NotificationType.DEFENSE_SCHEDULED);
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(2);
        when(notificationRepository.findByIsSentFalseAndCreatedAtBeforeOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(n));

        notificationService.retryUnsentNotifications(50, cutoff);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Bounded batch — the configured batch size is passed through as the page limit")
    void batchSize_isPassedAsPageLimit() {
        mailEnabled(true);
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(2);
        when(notificationRepository.findByIsSentFalseAndCreatedAtBeforeOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of());

        notificationService.retryUnsentNotifications(25, cutoff);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationRepository)
                .findByIsSentFalseAndCreatedAtBeforeOrderByCreatedAtAsc(eq(cutoff), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(25);
        assertThat(pageable.getValue().getPageNumber()).isZero();
    }
}
