package com.praksa.service;

import com.praksa.dto.notification.NotificationResponse;
import com.praksa.exception.ResourceNotFoundException;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.Notification;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;
import com.praksa.repository.NotificationRepository;
import com.praksa.repository.UserRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.service.impl.NotificationServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * P3.6 — read/unread service behavior for {@link NotificationServiceImpl}, in isolation
 * (pure Mockito). Covers: new notifications default to unread; owner mark-read (idempotent);
 * cross-user mark-read is denied server-side; unread-count and mark-all are user-scoped; and
 * — critically — the read flag is completely independent of the email/{@code isSent} path
 * (marking read never touches {@code isSent}/{@code sentAt} and never dispatches an email).
 *
 * <p>The end-to-end ownership check over the real security filter chain lives in
 * {@code NotificationReadIntegrationTest}; this class pins the service contract precisely.
 */
@ExtendWith(MockitoExtension.class)
class NotificationReadServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;
    @Mock private SecurityUtils securityUtils;

    @InjectMocks private NotificationServiceImpl service;

    private User user(Role role) {
        return User.builder().id(UUID.randomUUID()).email(role + "-" + UUID.randomUUID() + "@t.com")
                .fullName(role + " User").role(role).build();
    }

    private Notification notification(User owner, boolean read) {
        return Notification.builder()
                .id(UUID.randomUUID())
                .user(owner)
                .thesis(Thesis.builder().id(UUID.randomUUID()).title("Some Thesis").build())
                .type(NotificationType.ELIGIBILITY_APPROVED.name())
                .isSent(false)
                .isRead(read)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    // ─── Test 1 + Test 10: a newly created notification is unread, and creation still works ──

    @Test
    @DisplayName("Test 1/10: notify() creates an UNREAD notification and still dispatches the email")
    void notify_createsUnread_andDispatches() {
        User recipient = user(Role.STUDENT);
        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("T").status(null).build();

        service.notify(recipient, thesis, NotificationType.ELIGIBILITY_APPROVED);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(saved.capture());
        // New notification defaults to unread.
        assertFalse(saved.getValue().isRead(), "new notification must be unread");
        assertFalse(saved.getValue().isSent(), "new notification is not yet sent");
        // Existing behavior preserved: the async email is still dispatched.
        verify(emailService, times(1)).sendAsync(any(), eq(recipient.getEmail()), anyString(), anyString());
    }

    @Test
    @DisplayName("Test 1 (entity): Notification builder defaults isRead to false")
    void entityDefault_isUnread() {
        Notification n = Notification.builder().build();
        assertFalse(n.isRead());
    }

    // ─── Test 2 + Test 5 + Test 9: owner marks read; response reflects it; email untouched ──

    @Test
    @DisplayName("Test 2/5/9: owner marks unread notification read; response has isRead=true; no email, isSent untouched")
    void markAsRead_owner_setsRead() {
        User owner = user(Role.STUDENT);
        Notification n = notification(owner, false);
        when(securityUtils.getCurrentUser()).thenReturn(owner);
        when(notificationRepository.findById(n.getId())).thenReturn(Optional.of(n));

        NotificationResponse resp = service.markAsRead(n.getId());

        assertTrue(n.isRead(), "notification is now read");
        assertTrue(resp.isRead(), "response exposes read=true");
        verify(notificationRepository).save(n);
        // Read is independent of email: isSent/sentAt unchanged, no email dispatched.
        assertFalse(n.isSent());
        assertNull(n.getSentAt());
        verifyNoInteractions(emailService);
    }

    // ─── Test 3: idempotent mark-read ───────────────────────────────────────────────────

    @Test
    @DisplayName("Test 3: marking an already-read notification is a no-op (no error, stays read, no save, no email)")
    void markAsRead_idempotent() {
        User owner = user(Role.STUDENT);
        Notification n = notification(owner, true); // already read
        when(securityUtils.getCurrentUser()).thenReturn(owner);
        when(notificationRepository.findById(n.getId())).thenReturn(Optional.of(n));

        NotificationResponse resp = service.markAsRead(n.getId());

        assertTrue(n.isRead());
        assertTrue(resp.isRead());
        // No redundant write on an already-read row; no email; no new row.
        verify(notificationRepository, never()).save(any());
        verifyNoInteractions(emailService);
    }

    // ─── Test 4 + Test 7: cannot mark another user's notification (server-side ownership) ──

    @Test
    @DisplayName("Test 4/7: user B cannot mark user A's notification read → 403, stays unread, no save, no email")
    void markAsRead_notOwner_forbidden() {
        User owner = user(Role.STUDENT);       // user A
        User attacker = user(Role.STUDENT);    // user B
        Notification n = notification(owner, false);
        when(securityUtils.getCurrentUser()).thenReturn(attacker);
        when(notificationRepository.findById(n.getId())).thenReturn(Optional.of(n));

        assertThrows(UnauthorizedException.class, () -> service.markAsRead(n.getId()));

        assertFalse(n.isRead(), "A's notification remains unread after B's forbidden attempt");
        verify(notificationRepository, never()).save(any());
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("mark-read on a non-existent id → 404")
    void markAsRead_notFound() {
        User owner = user(Role.STUDENT);
        UUID missing = UUID.randomUUID();
        when(securityUtils.getCurrentUser()).thenReturn(owner);
        when(notificationRepository.findById(missing)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.markAsRead(missing));
        verify(notificationRepository, never()).save(any());
        verifyNoInteractions(emailService);
    }

    // ─── Test 6: unread count is user-scoped ─────────────────────────────────────────────

    @Test
    @DisplayName("Test 6: getUnreadCount counts only the CURRENT user's unread notifications")
    void getUnreadCount_userScoped() {
        User me = user(Role.MENTOR);
        when(securityUtils.getCurrentUser()).thenReturn(me);
        when(notificationRepository.countByUserAndIsReadFalse(me)).thenReturn(3L);

        long count = service.getUnreadCount();

        assertEquals(3L, count);
        // Scoped to the authenticated user — never a global count.
        verify(notificationRepository).countByUserAndIsReadFalse(me);
    }

    // ─── Test 8: mark-all is user-scoped ─────────────────────────────────────────────────

    @Test
    @DisplayName("Test 8: markAllAsRead updates only the CURRENT user's notifications")
    void markAllAsRead_userScoped() {
        User me = user(Role.STUDENT);
        when(securityUtils.getCurrentUser()).thenReturn(me);
        when(notificationRepository.markAllReadByUser(me)).thenReturn(4);

        int updated = service.markAllAsRead();

        assertEquals(4, updated);
        // The bulk update is scoped to exactly this user.
        verify(notificationRepository).markAllReadByUser(me);
        verifyNoInteractions(emailService);
    }
}
