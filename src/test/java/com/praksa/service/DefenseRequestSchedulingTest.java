package com.praksa.service;

import com.praksa.dto.defense.ScheduleDefenseRequest;
import com.praksa.exception.BadRequestException;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.CommitteeMember;
import com.praksa.model.Defense;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.MemberRole;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.CommitteeMemberRepository;
import com.praksa.repository.DefenseRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.service.impl.DefenseServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Roadmap Item #6 — Student-initiated defense request.
 *
 * Covers the security/workflow rules for the new two-step defense flow:
 *   STUDENT requests  → PENDING_DEFENSE_SCHEDULING
 *   STUDENT_SERVICE schedules → DEFENSE_SCHEDULED
 * and verifies the MENTOR can no longer schedule directly.
 *
 * Pure Mockito unit tests — no Spring context, no database.
 */
@ExtendWith(MockitoExtension.class)
class DefenseRequestSchedulingTest {

    @Mock private DefenseRepository defenseRepository;
    @Mock private ThesisRepository thesisRepository;
    @Mock private ThesisStatusHistoryRepository statusHistoryRepository;
    @Mock private CommitteeMemberRepository committeeRepository;
    @Mock private SecurityUtils securityUtils;
    @Mock private NotificationService notificationService;

    @InjectMocks private DefenseServiceImpl defenseService;

    private User user(Role role) {
        return User.builder().id(UUID.randomUUID()).email(role + "@t.com")
                .fullName(role + " User").role(role).build();
    }

    private Thesis thesis(User student, User mentor, ThesisStatus status) {
        return Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).mentor(mentor).status(status).build();
    }

    private ScheduleDefenseRequest scheduleReq(String room, OffsetDateTime at) {
        ScheduleDefenseRequest req = new ScheduleDefenseRequest();
        req.setRoom(room);
        req.setScheduledAt(at);
        return req;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Student can request a defense AFTER eligibility verification (Item #8)
    //    i.e. once the thesis is PENDING_DEFENSE_SCHEDULING. The request signals
    //    STUDENT_SERVICE but does NOT itself change the status.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1 — student requests own PENDING_DEFENSE_SCHEDULING thesis → notify STUDENT_SERVICE, status unchanged, no history, no Defense row")
    void studentRequestsAfterVerification() {
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);
        Thesis thesis = thesis(student, mentor, ThesisStatus.PENDING_DEFENSE_SCHEDULING);

        when(securityUtils.getCurrentUser()).thenReturn(student);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));

        defenseService.requestDefense(thesis.getId());

        // The request does not move the status — eligibility verification already did.
        assertEquals(ThesisStatus.PENDING_DEFENSE_SCHEDULING, thesis.getStatus());
        verify(notificationService).notifyRole(
                eq(Role.STUDENT_SERVICE), eq(thesis), eq(NotificationType.DEFENSE_REQUESTED));
        // No status transition (no new history row) and no Defense row on a request
        verify(statusHistoryRepository, never()).save(any());
        verify(defenseRepository, never()).save(any());
    }

    @Test
    @DisplayName("1b — student CANNOT request a defense before verification (still PENDING_DEFENSE_CHECK → 400)")
    void studentCannotRequestBeforeVerification() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesis(student, user(Role.MENTOR), ThesisStatus.PENDING_DEFENSE_CHECK);

        when(securityUtils.getCurrentUser()).thenReturn(student);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));

        assertThrows(BadRequestException.class, () -> defenseService.requestDefense(thesis.getId()));

        assertEquals(ThesisStatus.PENDING_DEFENSE_CHECK, thesis.getStatus());
        verify(notificationService, never()).notifyRole(any(), any(), any());
        verify(defenseRepository, never()).save(any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Student cannot request a defense for someone else's thesis
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("2 — student cannot request a defense on another student's thesis (403, no transition)")
    void studentCannotRequestOthersThesis() {
        User owner = user(Role.STUDENT);
        User intruder = user(Role.STUDENT);
        Thesis thesis = thesis(owner, user(Role.MENTOR), ThesisStatus.PENDING_DEFENSE_CHECK);

        when(securityUtils.getCurrentUser()).thenReturn(intruder);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));

        assertThrows(UnauthorizedException.class, () -> defenseService.requestDefense(thesis.getId()));

        assertEquals(ThesisStatus.PENDING_DEFENSE_CHECK, thesis.getStatus());
        verify(notificationService, never()).notifyRole(any(), any(), any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Student cannot request a defense from an invalid status
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("3 — student cannot request a defense when thesis is IN_PROGRESS (400, no transition)")
    void studentCannotRequestFromInvalidStatus() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesis(student, user(Role.MENTOR), ThesisStatus.IN_PROGRESS);

        when(securityUtils.getCurrentUser()).thenReturn(student);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));

        assertThrows(BadRequestException.class, () -> defenseService.requestDefense(thesis.getId()));

        assertEquals(ThesisStatus.IN_PROGRESS, thesis.getStatus());
        verify(notificationService, never()).notifyRole(any(), any(), any());
    }

    @Test
    @DisplayName("3b — a MENTOR cannot call requestDefense at all (403)")
    void mentorCannotRequestDefense() {
        User mentor = user(Role.MENTOR);
        Thesis thesis = thesis(user(Role.STUDENT), mentor, ThesisStatus.PENDING_DEFENSE_CHECK);

        when(securityUtils.getCurrentUser()).thenReturn(mentor);

        assertThrows(UnauthorizedException.class, () -> defenseService.requestDefense(thesis.getId()));
        verify(notificationService, never()).notifyRole(any(), any(), any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Mentor cannot directly schedule a defense
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("4 — MENTOR cannot schedule a defense directly (403, no Defense created)")
    void mentorCannotScheduleDefense() {
        User mentor = user(Role.MENTOR);
        Thesis thesis = thesis(user(Role.STUDENT), mentor, ThesisStatus.PENDING_DEFENSE_SCHEDULING);

        when(securityUtils.getCurrentUser()).thenReturn(mentor);

        ScheduleDefenseRequest req = scheduleReq("A1", OffsetDateTime.now().plusDays(7));

        assertThrows(UnauthorizedException.class,
                () -> defenseService.scheduleDefense(thesis.getId(), req));

        verify(defenseRepository, never()).save(any());
        verify(notificationService, never()).notify(any(), any(), any(), anyString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5 + 8 + 9 + 10. Student Service can schedule a pending request
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("5/8/9/10 — STUDENT_SERVICE schedules a pending request: Defense created, status DEFENSE_SCHEDULED, notifications sent")
    void studentServiceSchedulesPendingRequest() {
        User service = user(Role.STUDENT_SERVICE);
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);
        User prof = user(Role.MENTOR);
        Thesis thesis = thesis(student, mentor, ThesisStatus.PENDING_DEFENSE_SCHEDULING);

        CommitteeMember m0 = CommitteeMember.builder().id(UUID.randomUUID())
                .thesis(thesis).professor(mentor).memberRole(MemberRole.MENTOR_MEMBER).build();
        CommitteeMember m1 = CommitteeMember.builder().id(UUID.randomUUID())
                .thesis(thesis).professor(prof).memberRole(MemberRole.FORMAL_MEMBER).build();

        OffsetDateTime when = OffsetDateTime.now().plusDays(9);
        ScheduleDefenseRequest req = scheduleReq("Hall 204", when);

        when(securityUtils.getCurrentUser()).thenReturn(service);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(defenseRepository.findByThesisAndIsCancelledFalse(thesis)).thenReturn(Optional.empty());
        when(committeeRepository.findByThesis(thesis)).thenReturn(List.of(m0, m1));

        defenseService.scheduleDefense(thesis.getId(), req);

        // 8 — Defense created with the requested room + time
        ArgumentCaptor<Defense> captor = ArgumentCaptor.forClass(Defense.class);
        verify(defenseRepository).save(captor.capture());
        Defense saved = captor.getValue();
        assertEquals("Hall 204", saved.getRoom());
        assertEquals(when, saved.getScheduledAt());
        assertEquals(thesis, saved.getThesis());

        // 9 — transition to DEFENSE_SCHEDULED (+ history row)
        assertEquals(ThesisStatus.DEFENSE_SCHEDULED, thesis.getStatus());
        verify(statusHistoryRepository).save(any());

        // 10 — DEFENSE_SCHEDULED notifications to student + each committee member (incl. mentor)
        verify(notificationService).notify(eq(student), eq(thesis),
                eq(NotificationType.DEFENSE_SCHEDULED), anyString());
        verify(notificationService).notify(eq(mentor), eq(thesis),
                eq(NotificationType.DEFENSE_SCHEDULED), anyString());
        verify(notificationService).notify(eq(prof), eq(thesis),
                eq(NotificationType.DEFENSE_SCHEDULED), anyString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Student Service cannot schedule a thesis that has no pending request
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("6 — STUDENT_SERVICE cannot schedule a thesis still in PENDING_DEFENSE_CHECK (no request yet → 400)")
    void serviceCannotScheduleWithoutRequest() {
        User service = user(Role.STUDENT_SERVICE);
        Thesis thesis = thesis(user(Role.STUDENT), user(Role.MENTOR), ThesisStatus.PENDING_DEFENSE_CHECK);

        when(securityUtils.getCurrentUser()).thenReturn(service);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));

        ScheduleDefenseRequest req = scheduleReq("A1", OffsetDateTime.now().plusDays(7));

        assertThrows(BadRequestException.class,
                () -> defenseService.scheduleDefense(thesis.getId(), req));

        assertEquals(ThesisStatus.PENDING_DEFENSE_CHECK, thesis.getStatus());
        verify(defenseRepository, never()).save(any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. Student Service cannot schedule an unrelated / invalid thesis
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("7 — STUDENT_SERVICE cannot schedule an unrelated thesis (e.g. MENTOR_APPROVED → 400)")
    void serviceCannotScheduleUnrelatedThesis() {
        User service = user(Role.STUDENT_SERVICE);
        Thesis thesis = thesis(user(Role.STUDENT), user(Role.MENTOR), ThesisStatus.MENTOR_APPROVED);

        when(securityUtils.getCurrentUser()).thenReturn(service);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));

        ScheduleDefenseRequest req = scheduleReq("A1", OffsetDateTime.now().plusDays(7));

        assertThrows(BadRequestException.class,
                () -> defenseService.scheduleDefense(thesis.getId(), req));

        assertEquals(ThesisStatus.MENTOR_APPROVED, thesis.getStatus());
        verify(defenseRepository, never()).save(any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 11. Existing cancellation / rescheduling still respects authorization
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("11a — student owner can cancel their active defense")
    void studentOwnerCanCancel() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesis(student, user(Role.MENTOR), ThesisStatus.DEFENSE_SCHEDULED);
        Defense defense = Defense.builder().id(UUID.randomUUID()).thesis(thesis)
                .room("A1").scheduledAt(OffsetDateTime.now().plusDays(3)).isCancelled(false).build();

        when(securityUtils.getCurrentUser()).thenReturn(student);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(defenseRepository.findByThesisAndIsCancelledFalse(thesis)).thenReturn(Optional.of(defense));
        when(committeeRepository.findByThesis(thesis)).thenReturn(List.of());

        defenseService.cancelDefense(thesis.getId());

        verify(defenseRepository, atLeastOnce()).save(defense);
        assertEquals(true, defense.isCancelled());
        // Thesis status does NOT go backwards
        assertEquals(ThesisStatus.DEFENSE_SCHEDULED, thesis.getStatus());
    }

    @Test
    @DisplayName("11b — mentor can still cancel (a legitimate mentor defense action is preserved)")
    void mentorCanCancel() {
        User mentor = user(Role.MENTOR);
        Thesis thesis = thesis(user(Role.STUDENT), mentor, ThesisStatus.DEFENSE_SCHEDULED);
        Defense defense = Defense.builder().id(UUID.randomUUID()).thesis(thesis)
                .room("A1").scheduledAt(OffsetDateTime.now().plusDays(3)).isCancelled(false).build();

        when(securityUtils.getCurrentUser()).thenReturn(mentor);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(defenseRepository.findByThesisAndIsCancelledFalse(thesis)).thenReturn(Optional.of(defense));
        when(committeeRepository.findByThesis(thesis)).thenReturn(List.of());

        defenseService.cancelDefense(thesis.getId());

        assertEquals(true, defense.isCancelled());
    }

    @Test
    @DisplayName("11c — an unrelated user (COMMITTEE role) cannot cancel a defense (403)")
    void unrelatedUserCannotCancel() {
        User committee = user(Role.COMMITTEE);
        Thesis thesis = thesis(user(Role.STUDENT), user(Role.MENTOR), ThesisStatus.DEFENSE_SCHEDULED);

        when(securityUtils.getCurrentUser()).thenReturn(committee);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));

        assertThrows(UnauthorizedException.class, () -> defenseService.cancelDefense(thesis.getId()));
        verify(defenseRepository, never()).save(any());
    }

    @Test
    @DisplayName("11d — reschedule path (from DEFENSE_SCHEDULED, active defense cancelled) requires STUDENT_SERVICE; MENTOR is blocked")
    void rescheduleRequiresStudentService() {
        User mentor = user(Role.MENTOR);
        Thesis thesis = thesis(user(Role.STUDENT), mentor, ThesisStatus.DEFENSE_SCHEDULED);

        when(securityUtils.getCurrentUser()).thenReturn(mentor);

        ScheduleDefenseRequest req = scheduleReq("B2", OffsetDateTime.now().plusDays(8));

        // Even on the reschedule path the mentor is rejected on the role check — this is what
        // stops a mentor from creating the first defense through the reschedule route.
        assertThrows(UnauthorizedException.class,
                () -> defenseService.scheduleDefense(thesis.getId(), req));
        verify(defenseRepository, never()).save(any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. The Item #6 workflow still works AFTER the Item #8 eligibility verification.
    //    Starting from PENDING_DEFENSE_SCHEDULING (the state verification produces),
    //    the student can request and STUDENT_SERVICE can then schedule → DEFENSE_SCHEDULED.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("8 — post-verification: student requests then STUDENT_SERVICE schedules → DEFENSE_SCHEDULED")
    void item6FlowWorksAfterVerification() {
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);
        User service = user(Role.STUDENT_SERVICE);
        // Thesis is in the post-verification state produced by Item #8.
        Thesis thesis = thesis(student, mentor, ThesisStatus.PENDING_DEFENSE_SCHEDULING);

        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));

        // Student requests — signals STUDENT_SERVICE, no status change.
        when(securityUtils.getCurrentUser()).thenReturn(student);
        defenseService.requestDefense(thesis.getId());
        assertEquals(ThesisStatus.PENDING_DEFENSE_SCHEDULING, thesis.getStatus());
        verify(notificationService).notifyRole(
                eq(Role.STUDENT_SERVICE), eq(thesis), eq(NotificationType.DEFENSE_REQUESTED));

        // Student Service schedules → DEFENSE_SCHEDULED.
        when(securityUtils.getCurrentUser()).thenReturn(service);
        when(defenseRepository.findByThesisAndIsCancelledFalse(thesis)).thenReturn(Optional.empty());
        when(committeeRepository.findByThesis(thesis)).thenReturn(List.of());
        OffsetDateTime at = OffsetDateTime.now().plusDays(10);

        defenseService.scheduleDefense(thesis.getId(), scheduleReq("Room 1", at));

        assertEquals(ThesisStatus.DEFENSE_SCHEDULED, thesis.getStatus());
        verify(defenseRepository).save(any(Defense.class));
        verify(notificationService).notify(
                eq(student), eq(thesis), eq(NotificationType.DEFENSE_SCHEDULED), anyString());
    }
}
