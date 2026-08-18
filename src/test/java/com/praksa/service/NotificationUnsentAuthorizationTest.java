package com.praksa.service;

import com.praksa.dto.notification.NotificationResponse;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUG-19 (P2) — {@link NotificationServiceImpl#getUnsentNotifications()} exposes the SYSTEM-WIDE
 * list of unsent notifications, so it is an operational/oversight view that must be restricted to
 * {@link Role#STUDENT_SERVICE}. Any other authenticated role gets a 403 ({@link UnauthorizedException}
 * is mapped to HTTP 403 by GlobalExceptionHandler), and authorization runs BEFORE the repository is
 * queried — an unauthorized caller must never reach {@code findByIsSentFalse()}.
 *
 * <p>Note on ADMIN: the {@link Role} enum has been standardized to exactly
 * {STUDENT, MENTOR, STUDENT_SERVICE, COMMITTEE, ARCHIVE}. There is no ADMIN role in this project, so
 * there is no ADMIN case to test; the deny check is exhaustive over every non-service role that exists.
 *
 * <p>(Unauthenticated callers are handled upstream by Spring Security, which rejects them with 401
 * before any controller/service method is reached — out of scope for this service-layer test.)
 */
@ExtendWith(MockitoExtension.class)
class NotificationUnsentAuthorizationTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;
    @Mock private SecurityUtils securityUtils;

    @InjectMocks private NotificationServiceImpl service;

    private User user(Role role) {
        return User.builder().id(UUID.randomUUID()).email(role + "@t.com")
                .fullName(role + " User").role(role).build();
    }

    // ─── ALLOWED: STUDENT_SERVICE gets the list and the repository IS queried ────────────

    @Test
    @DisplayName("STUDENT_SERVICE → allowed, repository queried, notifications returned unchanged")
    void studentService_allowed_queriesRepository() {
        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("Some Thesis").build();
        Notification withThesis = Notification.builder()
                .id(UUID.randomUUID())
                .user(user(Role.STUDENT))
                .thesis(thesis)
                .type(NotificationType.APPLICATION_PENDING_ARCHIVE.name())
                .isSent(false)
                .createdAt(OffsetDateTime.now())
                .build();
        Notification withoutThesis = Notification.builder()
                .id(UUID.randomUUID())
                .user(user(Role.MENTOR))
                .thesis(null)
                .type(NotificationType.MENTOR_REQUEST_RECEIVED.name())
                .isSent(false)
                .createdAt(OffsetDateTime.now())
                .build();

        when(securityUtils.getCurrentUser()).thenReturn(user(Role.STUDENT_SERVICE));
        when(notificationRepository.findByIsSentFalse()).thenReturn(List.of(withThesis, withoutThesis));

        List<NotificationResponse> result = service.getUnsentNotifications();

        // Repository was queried exactly once, after authorization passed.
        verify(notificationRepository, times(1)).findByIsSentFalse();

        // Returned notifications are an unchanged, faithful mapping of the repository rows.
        assertEquals(2, result.size());

        NotificationResponse first = result.get(0);
        assertEquals(withThesis.getId(), first.getId());
        assertEquals(thesis.getId(), first.getThesisId());
        assertEquals("Some Thesis", first.getThesisTitle());
        assertEquals(NotificationType.APPLICATION_PENDING_ARCHIVE.name(), first.getType());
        assertFalse(first.isSent());

        NotificationResponse second = result.get(1);
        assertEquals(withoutThesis.getId(), second.getId());
        assertEquals(null, second.getThesisId());
        assertEquals(null, second.getThesisTitle());
        assertEquals(NotificationType.MENTOR_REQUEST_RECEIVED.name(), second.getType());
        assertFalse(second.isSent());
    }

    // ─── DENIED: every other authenticated role → 403 and the repository is NEVER queried ─

    @Test
    @DisplayName("STUDENT → 403, repository never queried")
    void student_denied() {
        assertDenied(Role.STUDENT);
    }

    @Test
    @DisplayName("MENTOR → 403, repository never queried")
    void mentor_denied() {
        assertDenied(Role.MENTOR);
    }

    @Test
    @DisplayName("COMMITTEE → 403, repository never queried")
    void committee_denied() {
        assertDenied(Role.COMMITTEE);
    }

    @Test
    @DisplayName("ARCHIVE → 403, repository never queried")
    void archive_denied() {
        assertDenied(Role.ARCHIVE);
    }

    /**
     * Drives getUnsentNotifications for a non-STUDENT_SERVICE caller and asserts a 403 with the
     * notification repository never touched — authorization fails before any query runs.
     */
    private void assertDenied(Role role) {
        when(securityUtils.getCurrentUser()).thenReturn(user(role));

        assertThrows(UnauthorizedException.class, () -> service.getUnsentNotifications());

        verify(notificationRepository, never()).findByIsSentFalse();
    }
}
