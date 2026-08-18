package com.praksa.service;

import com.praksa.dto.thesis.DefenseEligibilityRequest;
import com.praksa.dto.thesis.ThesisResponse;
import com.praksa.exception.BadRequestException;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.repository.UserRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.service.impl.ThesisServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Roadmap Item #8 — explicit defense-condition verification.
 *
 * Student Service must confirm BOTH conditions (exams + documentation) before the
 * thesis can leave PENDING_DEFENSE_CHECK. Only then does it advance to
 * PENDING_DEFENSE_SCHEDULING (from which the student may request a defense, Item #6).
 *
 * Pure Mockito unit tests — no Spring context, no database.
 */
@ExtendWith(MockitoExtension.class)
class DefenseEligibilityVerificationTest {

    @Mock private ThesisRepository thesisRepository;
    @Mock private ThesisStatusHistoryRepository statusHistoryRepository;
    @Mock private UserRepository userRepository;
    @Mock private SecurityUtils securityUtils;
    @Mock private NotificationService notificationService;
    @Mock private ApplicationPdfService applicationPdfService;

    @InjectMocks private ThesisServiceImpl thesisService;

    private User user(Role role) {
        return User.builder().id(UUID.randomUUID()).email(role + "@t.com")
                .fullName(role + " User").role(role).build();
    }

    private Thesis thesis(User student, ThesisStatus status) {
        return Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).status(status).build();
    }

    private DefenseEligibilityRequest req(Boolean exams, Boolean docs) {
        DefenseEligibilityRequest r = new DefenseEligibilityRequest();
        r.setExamsCompleted(exams);
        r.setDocumentationComplete(docs);
        return r;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Happy path — both conditions true → advance + history + notification
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1 — STUDENT_SERVICE + both true → PENDING_DEFENSE_SCHEDULING, history row, student notified")
    void bothTrue_accepted() {
        User service = user(Role.STUDENT_SERVICE);
        User student = user(Role.STUDENT);
        Thesis thesis = thesis(student, ThesisStatus.PENDING_DEFENSE_CHECK);

        when(securityUtils.getCurrentUser()).thenReturn(service);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));

        ThesisResponse res = thesisService.verifyDefenseEligibility(thesis.getId(), req(true, true));

        assertEquals(ThesisStatus.PENDING_DEFENSE_SCHEDULING, thesis.getStatus());
        assertEquals(ThesisStatus.PENDING_DEFENSE_SCHEDULING, res.getStatus());
        verify(thesisRepository).save(thesis);
        verify(statusHistoryRepository).save(any());
        verify(notificationService).notify(
                eq(student), eq(thesis), eq(NotificationType.DEFENSE_ELIGIBILITY_VERIFIED));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2/3/4. A failed condition → 400, status unchanged, no history, no notification
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("2 — examsCompleted=false → 400, no transition, no history, no notification")
    void examsFalse_rejected() {
        User service = user(Role.STUDENT_SERVICE);
        Thesis thesis = thesis(user(Role.STUDENT), ThesisStatus.PENDING_DEFENSE_CHECK);

        when(securityUtils.getCurrentUser()).thenReturn(service);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));

        assertThrows(BadRequestException.class,
                () -> thesisService.verifyDefenseEligibility(thesis.getId(), req(false, true)));

        assertEquals(ThesisStatus.PENDING_DEFENSE_CHECK, thesis.getStatus());
        verify(statusHistoryRepository, never()).save(any());
        verify(notificationService, never()).notify(any(), any(), any());
    }

    @Test
    @DisplayName("3 — documentationComplete=false → 400, no transition, no history")
    void docsFalse_rejected() {
        User service = user(Role.STUDENT_SERVICE);
        Thesis thesis = thesis(user(Role.STUDENT), ThesisStatus.PENDING_DEFENSE_CHECK);

        when(securityUtils.getCurrentUser()).thenReturn(service);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));

        assertThrows(BadRequestException.class,
                () -> thesisService.verifyDefenseEligibility(thesis.getId(), req(true, false)));

        assertEquals(ThesisStatus.PENDING_DEFENSE_CHECK, thesis.getStatus());
        verify(statusHistoryRepository, never()).save(any());
        verify(notificationService, never()).notify(any(), any(), any());
    }

    @Test
    @DisplayName("4 — both false → 400, status unchanged")
    void bothFalse_rejected() {
        User service = user(Role.STUDENT_SERVICE);
        Thesis thesis = thesis(user(Role.STUDENT), ThesisStatus.PENDING_DEFENSE_CHECK);

        when(securityUtils.getCurrentUser()).thenReturn(service);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));

        assertThrows(BadRequestException.class,
                () -> thesisService.verifyDefenseEligibility(thesis.getId(), req(false, false)));

        assertEquals(ThesisStatus.PENDING_DEFENSE_CHECK, thesis.getStatus());
        verify(statusHistoryRepository, never()).save(any());
        verify(notificationService, never()).notify(any(), any(), any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5/6. Only STUDENT_SERVICE may verify — STUDENT and MENTOR are rejected (403)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("5 — STUDENT attempting verification → 403")
    void studentCannotVerify() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesis(student, ThesisStatus.PENDING_DEFENSE_CHECK);

        when(securityUtils.getCurrentUser()).thenReturn(student);

        assertThrows(UnauthorizedException.class,
                () -> thesisService.verifyDefenseEligibility(thesis.getId(), req(true, true)));

        assertEquals(ThesisStatus.PENDING_DEFENSE_CHECK, thesis.getStatus());
        verify(statusHistoryRepository, never()).save(any());
        verify(notificationService, never()).notify(any(), any(), any());
    }

    @Test
    @DisplayName("6 — MENTOR attempting verification → 403")
    void mentorCannotVerify() {
        User mentor = user(Role.MENTOR);
        Thesis thesis = thesis(user(Role.STUDENT), ThesisStatus.PENDING_DEFENSE_CHECK);

        when(securityUtils.getCurrentUser()).thenReturn(mentor);

        assertThrows(UnauthorizedException.class,
                () -> thesisService.verifyDefenseEligibility(thesis.getId(), req(true, true)));

        verify(statusHistoryRepository, never()).save(any());
        verify(notificationService, never()).notify(any(), any(), any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. Verification from the wrong thesis status → 400
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("7 — verification from the wrong status (COMMITTEE_ACCEPTED) → 400, unchanged")
    void wrongStatus_rejected() {
        User service = user(Role.STUDENT_SERVICE);
        Thesis thesis = thesis(user(Role.STUDENT), ThesisStatus.COMMITTEE_ACCEPTED);

        when(securityUtils.getCurrentUser()).thenReturn(service);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));

        assertThrows(BadRequestException.class,
                () -> thesisService.verifyDefenseEligibility(thesis.getId(), req(true, true)));

        assertEquals(ThesisStatus.COMMITTEE_ACCEPTED, thesis.getStatus());
        verify(statusHistoryRepository, never()).save(any());
        verify(notificationService, never()).notify(any(), any(), any());
    }
}
