package com.praksa.service;

import com.praksa.dto.defense.DefenseRequestCreateRequest;
import com.praksa.dto.defense.DefenseRequestDecisionRequest;
import com.praksa.dto.defense.DefenseRequestResponse;
import com.praksa.exception.BadRequestException;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.CommitteeMember;
import com.praksa.model.Defense;
import com.praksa.model.DefenseRequest;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.DefenseRequestStatus;
import com.praksa.model.enums.MemberRole;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.CommitteeMemberRepository;
import com.praksa.repository.DefenseRepository;
import com.praksa.repository.DefenseRequestRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.security.ThesisReadAccessPolicy;
import com.praksa.service.impl.DefenseServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Student-proposed defense scheduling redesign — the student proposes room/date/time,
 * STUDENT_SERVICE approves or rejects. Covers the full letter checklist (A-U) from the
 * task spec. Pure Mockito unit tests — no Spring context, no database.
 *
 * <p>Decision-time tests (J, K, L, M, N, S) construct the PENDING {@link DefenseRequest}
 * fixture directly (rather than going through {@code createDefenseRequest}) so the
 * asserted 5-15 day window and room-conflict checks are driven by a controlled, known
 * {@code createdAt}/{@code scheduledAt} instead of the real wall clock.
 */
@ExtendWith(MockitoExtension.class)
class DefenseRequestWorkflowTest {

    @Mock private DefenseRepository defenseRepository;
    @Mock private DefenseRequestRepository defenseRequestRepository;
    @Mock private ThesisRepository thesisRepository;
    @Mock private ThesisStatusHistoryRepository statusHistoryRepository;
    @Mock private CommitteeMemberRepository committeeRepository;
    @Mock private SecurityUtils securityUtils;
    @Mock private NotificationService notificationService;
    @Mock private ThesisReadAccessPolicy thesisReadAccessPolicy;

    @InjectMocks private DefenseServiceImpl defenseService;

    private User user(Role role) {
        return User.builder().id(UUID.randomUUID()).email(role + "-" + UUID.randomUUID() + "@t.com")
                .fullName(role + " User").role(role).build();
    }

    private Thesis thesis(User student, User mentor, ThesisStatus status) {
        // applicationSubmittedAt defaults to well over 14 days ago so these pre-existing
        // proposal/decision tests (none of which are about the 14-day rule itself) are
        // unaffected by it. The rule itself is covered separately in
        // DefenseRequestApplicationAgeTest.
        return Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).mentor(mentor).status(status)
                .applicationSubmittedAt(OffsetDateTime.now().minusDays(30))
                .build();
    }

    private DefenseRequestCreateRequest createReq(String room, OffsetDateTime at) {
        DefenseRequestCreateRequest r = new DefenseRequestCreateRequest();
        r.setRoom(room);
        r.setScheduledAt(at);
        return r;
    }

    private DefenseRequestDecisionRequest decision(boolean approved, String reason) {
        DefenseRequestDecisionRequest r = new DefenseRequestDecisionRequest();
        r.setApproved(approved);
        r.setReason(reason);
        return r;
    }

    /** A PENDING request fixture with a controlled createdAt, for decision-time tests. */
    private DefenseRequest pendingRequest(Thesis thesis, User student, String room,
                                           OffsetDateTime createdAt, OffsetDateTime scheduledAt) {
        return DefenseRequest.builder().id(UUID.randomUUID())
                .thesis(thesis).requestedBy(student).room(room)
                .scheduledAt(scheduledAt).status(DefenseRequestStatus.PENDING)
                .createdAt(createdAt).build();
    }

    private void mockThesis(Thesis thesis) {
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
    }

    // =========================================================================
    // A — valid creation
    // =========================================================================

    @Test
    @DisplayName("A: student creates a valid proposal — PENDING, no Defense created, thesis status unchanged")
    void a_validRequest_createsPending() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesis(student, user(Role.MENTOR), ThesisStatus.PENDING_DEFENSE_SCHEDULING);
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);
        when(defenseRequestRepository.findByThesisAndStatus(thesis, DefenseRequestStatus.PENDING))
                .thenReturn(Optional.empty());

        OffsetDateTime at = OffsetDateTime.now().plusDays(9);
        DefenseRequestResponse resp = defenseService.createDefenseRequest(thesis.getId(), createReq("Hall 1", at));

        ArgumentCaptor<DefenseRequest> captor = ArgumentCaptor.forClass(DefenseRequest.class);
        verify(defenseRequestRepository).save(captor.capture());
        DefenseRequest saved = captor.getValue();
        assertEquals(DefenseRequestStatus.PENDING, saved.getStatus());
        assertEquals("Hall 1", saved.getRoom());
        assertEquals(at, saved.getScheduledAt());
        assertEquals(student, saved.getRequestedBy());
        assertEquals(DefenseRequestStatus.PENDING, resp.getStatus());

        assertEquals(ThesisStatus.PENDING_DEFENSE_SCHEDULING, thesis.getStatus(), "creating a request never changes thesis status");
        verify(defenseRepository, never()).save(any());
        verify(statusHistoryRepository, never()).save(any());
        verify(notificationService).notifyRole(eq(Role.STUDENT_SERVICE), eq(thesis), eq(NotificationType.DEFENSE_REQUESTED));
    }

    // =========================================================================
    // B — cannot request another student's thesis
    // =========================================================================

    @Test
    @DisplayName("B: student cannot propose a defense on another student's thesis (403, no request created)")
    void b_cannotRequestOthersThesis() {
        User owner = user(Role.STUDENT);
        User intruder = user(Role.STUDENT);
        Thesis thesis = thesis(owner, user(Role.MENTOR), ThesisStatus.PENDING_DEFENSE_SCHEDULING);
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(intruder);

        assertThrows(UnauthorizedException.class, () ->
                defenseService.createDefenseRequest(thesis.getId(), createReq("X", OffsetDateTime.now().plusDays(7))));

        verify(defenseRequestRepository, never()).save(any());
    }

    // =========================================================================
    // C / D — missing room / missing scheduledAt (service-level defense-in-depth,
    // independent of the DTO's own bean validation which a unit test bypasses)
    // =========================================================================

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("C: blank room is rejected server-side (400, no request created)")
    void c_blankRoom_rejected(String blankRoom) {
        User student = user(Role.STUDENT);
        Thesis thesis = thesis(student, user(Role.MENTOR), ThesisStatus.PENDING_DEFENSE_SCHEDULING);
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);

        assertThrows(BadRequestException.class, () ->
                defenseService.createDefenseRequest(thesis.getId(), createReq(blankRoom, OffsetDateTime.now().plusDays(7))));

        verify(defenseRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("D: missing scheduledAt is rejected server-side (400, no request created)")
    void d_missingScheduledAt_rejected() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesis(student, user(Role.MENTOR), ThesisStatus.PENDING_DEFENSE_SCHEDULING);
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);

        assertThrows(BadRequestException.class, () ->
                defenseService.createDefenseRequest(thesis.getId(), createReq("X1", null)));

        verify(defenseRequestRepository, never()).save(any());
    }

    // =========================================================================
    // E / F — outside the 5-15 day window
    // =========================================================================

    @Test
    @DisplayName("E: a date less than 5 days away is rejected (400, no request created)")
    void e_tooSoon_rejected() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesis(student, user(Role.MENTOR), ThesisStatus.PENDING_DEFENSE_SCHEDULING);
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);

        OffsetDateTime tooSoon = OffsetDateTime.now().plusDays(5).minusHours(2);
        assertThrows(BadRequestException.class, () ->
                defenseService.createDefenseRequest(thesis.getId(), createReq("X1", tooSoon)));

        verify(defenseRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("F: a date more than 15 days away is rejected (400, no request created)")
    void f_tooFar_rejected() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesis(student, user(Role.MENTOR), ThesisStatus.PENDING_DEFENSE_SCHEDULING);
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);

        OffsetDateTime tooFar = OffsetDateTime.now().plusDays(15).plusHours(2);
        assertThrows(BadRequestException.class, () ->
                defenseService.createDefenseRequest(thesis.getId(), createReq("X1", tooFar)));

        verify(defenseRequestRepository, never()).save(any());
    }

    // =========================================================================
    // G / H — inclusive boundaries (small safety margin to absorb test-execution
    // jitter between the test's "now" and the service's internal now(); the margin is
    // far too small to cross into the next/previous day bucket for a 5/15-DAY rule)
    // =========================================================================

    @Test
    @DisplayName("G: a date just past the 5-day floor is accepted (inclusive lower boundary)")
    void g_fiveDayBoundary_accepted() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesis(student, user(Role.MENTOR), ThesisStatus.PENDING_DEFENSE_SCHEDULING);
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);
        when(defenseRequestRepository.findByThesisAndStatus(thesis, DefenseRequestStatus.PENDING))
                .thenReturn(Optional.empty());

        OffsetDateTime atFloor = OffsetDateTime.now().plusDays(5).plusSeconds(5);
        defenseService.createDefenseRequest(thesis.getId(), createReq("X1", atFloor));

        verify(defenseRequestRepository).save(any());
    }

    @Test
    @DisplayName("H: a date just before the 15-day ceiling is accepted (inclusive upper boundary)")
    void h_fifteenDayBoundary_accepted() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesis(student, user(Role.MENTOR), ThesisStatus.PENDING_DEFENSE_SCHEDULING);
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);
        when(defenseRequestRepository.findByThesisAndStatus(thesis, DefenseRequestStatus.PENDING))
                .thenReturn(Optional.empty());

        OffsetDateTime atCeiling = OffsetDateTime.now().plusDays(15).minusSeconds(5);
        defenseService.createDefenseRequest(thesis.getId(), createReq("X1", atCeiling));

        verify(defenseRequestRepository).save(any());
    }

    // =========================================================================
    // I — an existing PENDING request blocks a second one
    // =========================================================================

    @Test
    @DisplayName("I: a second request while one is PENDING is rejected (400)")
    void i_secondPendingRequest_rejected() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesis(student, user(Role.MENTOR), ThesisStatus.PENDING_DEFENSE_SCHEDULING);
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);

        DefenseRequest existing = pendingRequest(thesis, student, "A1",
                OffsetDateTime.now(), OffsetDateTime.now().plusDays(8));
        when(defenseRequestRepository.findByThesisAndStatus(thesis, DefenseRequestStatus.PENDING))
                .thenReturn(Optional.of(existing));

        assertThrows(BadRequestException.class, () ->
                defenseService.createDefenseRequest(thesis.getId(), createReq("B2", OffsetDateTime.now().plusDays(9))));

        verify(defenseRequestRepository, never()).save(any());
    }

    // =========================================================================
    // J — approval: Defense created, room/time copied, thesis transitioned, notified
    // =========================================================================

    @Test
    @DisplayName("J: STUDENT_SERVICE approves — exactly one Defense created (room/time copied), thesis DEFENSE_SCHEDULED, history + notifications")
    void j_approve_createsDefenseAndSchedules() {
        User service = user(Role.STUDENT_SERVICE);
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);
        User prof = user(Role.MENTOR);
        Thesis thesis = thesis(student, mentor, ThesisStatus.PENDING_DEFENSE_SCHEDULING);

        OffsetDateTime createdAt = OffsetDateTime.now().minusHours(3);
        OffsetDateTime scheduledAt = createdAt.plusDays(9);
        DefenseRequest pending = pendingRequest(thesis, student, "Hall 204", createdAt, scheduledAt);

        CommitteeMember m0 = CommitteeMember.builder().id(UUID.randomUUID())
                .thesis(thesis).professor(mentor).memberRole(MemberRole.MENTOR_MEMBER).build();
        CommitteeMember m1 = CommitteeMember.builder().id(UUID.randomUUID())
                .thesis(thesis).professor(prof).memberRole(MemberRole.FORMAL_MEMBER).build();

        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(service);
        when(defenseRequestRepository.findByThesisAndStatus(thesis, DefenseRequestStatus.PENDING))
                .thenReturn(Optional.of(pending));
        when(defenseRepository.findByRoomAndIsCancelledFalse("Hall 204")).thenReturn(List.of());
        when(committeeRepository.findByThesis(thesis)).thenReturn(List.of(m0, m1));

        defenseService.decideDefenseRequest(thesis.getId(), decision(true, null));

        // Exactly one Defense created, room + time copied from the request
        ArgumentCaptor<Defense> defenseCaptor = ArgumentCaptor.forClass(Defense.class);
        verify(defenseRepository, times(1)).save(defenseCaptor.capture());
        Defense saved = defenseCaptor.getValue();
        assertEquals("Hall 204", saved.getRoom());
        assertEquals(scheduledAt, saved.getScheduledAt());
        assertEquals(thesis, saved.getThesis());
        assertTrue(!saved.isCancelled());

        // The request itself is marked APPROVED with a decider + timestamp
        assertEquals(DefenseRequestStatus.APPROVED, pending.getStatus());
        assertEquals(service, pending.getDecidedBy());
        assertNotNull(pending.getDecidedAt());

        // Thesis transitioned via transitionStatus (status + history row)
        assertEquals(ThesisStatus.DEFENSE_SCHEDULED, thesis.getStatus());
        verify(statusHistoryRepository).save(any());

        // Student + every committee member (mentor included once via the seat) notified
        verify(notificationService).notify(eq(student), eq(thesis), eq(NotificationType.DEFENSE_SCHEDULED), anyString());
        verify(notificationService).notify(eq(mentor), eq(thesis), eq(NotificationType.DEFENSE_SCHEDULED), anyString());
        verify(notificationService).notify(eq(prof), eq(thesis), eq(NotificationType.DEFENSE_SCHEDULED), anyString());
    }

    // =========================================================================
    // K / L — rejection
    // =========================================================================

    @Test
    @DisplayName("K: STUDENT_SERVICE rejects — REJECTED with reason persisted, no Defense, thesis unchanged, student notified")
    void k_reject_persistsReasonNoDefense() {
        User service = user(Role.STUDENT_SERVICE);
        User student = user(Role.STUDENT);
        Thesis thesis = thesis(student, user(Role.MENTOR), ThesisStatus.PENDING_DEFENSE_SCHEDULING);

        DefenseRequest pending = pendingRequest(thesis, student, "A1",
                OffsetDateTime.now(), OffsetDateTime.now().plusDays(8));

        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(service);
        when(defenseRequestRepository.findByThesisAndStatus(thesis, DefenseRequestStatus.PENDING))
                .thenReturn(Optional.of(pending));

        defenseService.decideDefenseRequest(thesis.getId(), decision(false, "Просторијата е зафатена."));

        assertEquals(DefenseRequestStatus.REJECTED, pending.getStatus());
        assertEquals("Просторијата е зафатена.", pending.getReason());
        assertEquals(service, pending.getDecidedBy());
        assertNotNull(pending.getDecidedAt());

        assertEquals(ThesisStatus.PENDING_DEFENSE_SCHEDULING, thesis.getStatus(), "rejection never transitions the thesis");
        verify(defenseRepository, never()).save(any());
        verify(statusHistoryRepository, never()).save(any());
        verify(notificationService).notify(eq(student), eq(thesis),
                eq(NotificationType.DEFENSE_REQUEST_REJECTED), eq("Просторијата е зафатена."));
    }

    @Test
    @DisplayName("L: rejecting without a reason is refused (400, request stays PENDING)")
    void l_rejectWithoutReason_refused() {
        User service = user(Role.STUDENT_SERVICE);
        User student = user(Role.STUDENT);
        Thesis thesis = thesis(student, user(Role.MENTOR), ThesisStatus.PENDING_DEFENSE_SCHEDULING);

        DefenseRequest pending = pendingRequest(thesis, student, "A1",
                OffsetDateTime.now(), OffsetDateTime.now().plusDays(8));

        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(service);
        when(defenseRequestRepository.findByThesisAndStatus(thesis, DefenseRequestStatus.PENDING))
                .thenReturn(Optional.of(pending));

        assertThrows(BadRequestException.class, () ->
                defenseService.decideDefenseRequest(thesis.getId(), decision(false, "   ")));

        assertEquals(DefenseRequestStatus.PENDING, pending.getStatus());
        verify(defenseRequestRepository, never()).save(any());
        verify(notificationService, never()).notify(any(), any(), any(), anyString());
    }

    // =========================================================================
    // M — room conflict discovered at approval time
    // =========================================================================

    @Test
    @DisplayName("M: an existing Defense occupies the room — approval creates no Defense, request is REJECTED with a conflict reason, student notified")
    void m_roomConflictAtApproval_rejectsRequest() {
        User service = user(Role.STUDENT_SERVICE);
        User student = user(Role.STUDENT);
        Thesis thesis = thesis(student, user(Role.MENTOR), ThesisStatus.PENDING_DEFENSE_SCHEDULING);

        OffsetDateTime scheduledAt = OffsetDateTime.now().plusDays(8);
        DefenseRequest pending = pendingRequest(thesis, student, "Hall 1", OffsetDateTime.now(), scheduledAt);

        Defense occupying = Defense.builder().id(UUID.randomUUID())
                .thesis(thesis(user(Role.STUDENT), user(Role.MENTOR), ThesisStatus.DEFENSE_SCHEDULED))
                .room("Hall 1").scheduledAt(scheduledAt).isCancelled(false).build();

        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(service);
        when(defenseRequestRepository.findByThesisAndStatus(thesis, DefenseRequestStatus.PENDING))
                .thenReturn(Optional.of(pending));
        when(defenseRepository.findByRoomAndIsCancelledFalse("Hall 1")).thenReturn(List.of(occupying));

        defenseService.decideDefenseRequest(thesis.getId(), decision(true, null));

        assertEquals(DefenseRequestStatus.REJECTED, pending.getStatus());
        assertNotNull(pending.getReason());
        assertEquals(ThesisStatus.PENDING_DEFENSE_SCHEDULING, thesis.getStatus());
        // Only the one pre-existing Defense was ever saved via its own flow — none created here.
        verify(defenseRepository, never()).save(any());
        verify(notificationService).notify(eq(student), eq(thesis),
                eq(NotificationType.DEFENSE_REQUEST_REJECTED), anyString());
    }

    // =========================================================================
    // N — two PENDING requests for the same room/time; first approved occupies it,
    // second is rejected for conflict when its approval is attempted
    // =========================================================================

    @Test
    @DisplayName("N: two theses propose the same room/time; approving the first succeeds, approving the second is rejected as a conflict")
    void n_twoPendingSameRoomTime_secondRejectedOnApproval() {
        User service = user(Role.STUDENT_SERVICE);
        User studentA = user(Role.STUDENT);
        User studentB = user(Role.STUDENT);
        Thesis thesisA = thesis(studentA, user(Role.MENTOR), ThesisStatus.PENDING_DEFENSE_SCHEDULING);
        Thesis thesisB = thesis(studentB, user(Role.MENTOR), ThesisStatus.PENDING_DEFENSE_SCHEDULING);

        OffsetDateTime scheduledAt = OffsetDateTime.now().plusDays(9);
        DefenseRequest requestA = pendingRequest(thesisA, studentA, "Room A", OffsetDateTime.now(), scheduledAt);
        DefenseRequest requestB = pendingRequest(thesisB, studentB, "Room A", OffsetDateTime.now(), scheduledAt);

        when(thesisRepository.findById(thesisA.getId())).thenReturn(Optional.of(thesisA));
        when(thesisRepository.findById(thesisB.getId())).thenReturn(Optional.of(thesisB));
        when(defenseRequestRepository.findByThesisAndStatus(thesisA, DefenseRequestStatus.PENDING))
                .thenReturn(Optional.of(requestA));
        when(defenseRequestRepository.findByThesisAndStatus(thesisB, DefenseRequestStatus.PENDING))
                .thenReturn(Optional.of(requestB));
        when(committeeRepository.findByThesis(any())).thenReturn(List.of());

        // Approve A: room is free.
        when(securityUtils.getCurrentUser()).thenReturn(service);
        when(defenseRepository.findByRoomAndIsCancelledFalse("Room A")).thenReturn(List.of());
        defenseService.decideDefenseRequest(thesisA.getId(), decision(true, null));

        ArgumentCaptor<Defense> defenseCaptor = ArgumentCaptor.forClass(Defense.class);
        verify(defenseRepository, times(1)).save(defenseCaptor.capture());
        Defense defenseA = defenseCaptor.getValue();
        assertEquals(DefenseRequestStatus.APPROVED, requestA.getStatus());
        assertEquals(ThesisStatus.DEFENSE_SCHEDULED, thesisA.getStatus());

        // Approve B: the room is now occupied by the freshly-created Defense A — the
        // service re-queries the CURRENT DB state, so this must now detect the conflict.
        when(defenseRepository.findByRoomAndIsCancelledFalse("Room A")).thenReturn(List.of(defenseA));
        defenseService.decideDefenseRequest(thesisB.getId(), decision(true, null));

        assertEquals(DefenseRequestStatus.REJECTED, requestB.getStatus());
        assertNotNull(requestB.getReason());
        assertEquals(ThesisStatus.PENDING_DEFENSE_SCHEDULING, thesisB.getStatus(), "B never gets scheduled");
        // Still exactly ONE Defense ever created across both decisions.
        verify(defenseRepository, times(1)).save(any());
        verify(notificationService).notify(eq(studentB), eq(thesisB),
                eq(NotificationType.DEFENSE_REQUEST_REJECTED), anyString());
    }

    // =========================================================================
    // O / P / Q — cancel, then rebook with a brand-new request; cancelled defenses
    // never block room availability
    // =========================================================================

    @Test
    @DisplayName("O/P: after a cancellation (DEFENSE_SCHEDULED, no active Defense) the student can submit a brand-new request")
    void op_afterCancellation_newRequestAllowed() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesis(student, user(Role.MENTOR), ThesisStatus.DEFENSE_SCHEDULED);
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);
        // No active (non-cancelled) defense remains — the prior one was cancelled.
        when(defenseRepository.findByThesisAndIsCancelledFalse(thesis)).thenReturn(Optional.empty());
        // No PENDING request exists (the old one, if any, is long APPROVED/terminal).
        when(defenseRequestRepository.findByThesisAndStatus(thesis, DefenseRequestStatus.PENDING))
                .thenReturn(Optional.empty());

        OffsetDateTime at = OffsetDateTime.now().plusDays(10);
        defenseService.createDefenseRequest(thesis.getId(), createReq("New Room", at));

        ArgumentCaptor<DefenseRequest> captor = ArgumentCaptor.forClass(DefenseRequest.class);
        verify(defenseRequestRepository).save(captor.capture());
        assertEquals(DefenseRequestStatus.PENDING, captor.getValue().getStatus());
        assertEquals("New Room", captor.getValue().getRoom());
        verify(defenseRepository, never()).save(any());
    }

    @Test
    @DisplayName("Q: a cancelled defense in the same room/time does not block approval (cancelled defenses never conflict)")
    void q_cancelledDefense_doesNotBlockApproval() {
        User service = user(Role.STUDENT_SERVICE);
        User student = user(Role.STUDENT);
        Thesis thesis = thesis(student, user(Role.MENTOR), ThesisStatus.DEFENSE_SCHEDULED);

        OffsetDateTime scheduledAt = OffsetDateTime.now().plusDays(9);
        DefenseRequest pending = pendingRequest(thesis, student, "Room Z", OffsetDateTime.now(), scheduledAt);

        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(service);
        when(defenseRequestRepository.findByThesisAndStatus(thesis, DefenseRequestStatus.PENDING))
                .thenReturn(Optional.of(pending));
        // The repository query itself excludes cancelled rows — simulated by returning an
        // empty list even though a cancelled Defense for "Room Z" exists in the DB.
        when(defenseRepository.findByRoomAndIsCancelledFalse("Room Z")).thenReturn(List.of());
        when(committeeRepository.findByThesis(thesis)).thenReturn(List.of());

        defenseService.decideDefenseRequest(thesis.getId(), decision(true, null));

        assertEquals(DefenseRequestStatus.APPROVED, pending.getStatus());
        verify(defenseRepository).save(any());
        verify(defenseRepository).findByRoomAndIsCancelledFalse("Room Z");
    }

    // =========================================================================
    // R — other roles cannot submit a student request
    // =========================================================================

    @ParameterizedTest
    @ValueSource(strings = {"MENTOR", "STUDENT_SERVICE", "ARCHIVE", "COMMITTEE"})
    @DisplayName("R: non-STUDENT roles cannot create a defense request (403)")
    void r_nonStudentRoles_cannotCreateRequest(String roleName) {
        User caller = user(Role.valueOf(roleName));
        when(securityUtils.getCurrentUser()).thenReturn(caller);

        assertThrows(UnauthorizedException.class, () ->
                defenseService.createDefenseRequest(UUID.randomUUID(),
                        createReq("X1", OffsetDateTime.now().plusDays(7))));

        verify(defenseRequestRepository, never()).save(any());
    }

    // =========================================================================
    // S — deciding when nothing is PENDING
    // =========================================================================

    @Test
    @DisplayName("S: STUDENT_SERVICE cannot decide when no PENDING request exists (400, no Defense, no status change, no notification)")
    void s_decideWithoutPendingRequest_rejected() {
        User service = user(Role.STUDENT_SERVICE);
        Thesis thesis = thesis(user(Role.STUDENT), user(Role.MENTOR), ThesisStatus.PENDING_DEFENSE_SCHEDULING);
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(service);
        when(defenseRequestRepository.findByThesisAndStatus(thesis, DefenseRequestStatus.PENDING))
                .thenReturn(Optional.empty());

        assertThrows(BadRequestException.class, () ->
                defenseService.decideDefenseRequest(thesis.getId(), decision(true, null)));

        assertEquals(ThesisStatus.PENDING_DEFENSE_SCHEDULING, thesis.getStatus());
        verify(defenseRepository, never()).save(any());
        verify(statusHistoryRepository, never()).save(any());
        verify(notificationService, never()).notify(any(), any(), any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MENTOR", "STUDENT", "ARCHIVE", "COMMITTEE"})
    @DisplayName("Non-STUDENT_SERVICE roles cannot decide a defense request (403)")
    void nonServiceRoles_cannotDecide(String roleName) {
        User caller = user(Role.valueOf(roleName));
        when(securityUtils.getCurrentUser()).thenReturn(caller);

        assertThrows(UnauthorizedException.class, () ->
                defenseService.decideDefenseRequest(UUID.randomUUID(), decision(true, null)));

        verify(defenseRequestRepository, never()).save(any());
    }

    // =========================================================================
    // T — historical rejected requests remain queryable/auditable
    // =========================================================================

    @Test
    @DisplayName("T: getDefenseRequests returns the full history (rejected + pending), thesis-scoped")
    void t_historyIncludesRejectedRequests() {
        User student = user(Role.STUDENT);
        User caller = user(Role.STUDENT_SERVICE);
        Thesis thesis = thesis(student, user(Role.MENTOR), ThesisStatus.PENDING_DEFENSE_SCHEDULING);

        DefenseRequest rejected = DefenseRequest.builder().id(UUID.randomUUID())
                .thesis(thesis).requestedBy(student).room("A1")
                .scheduledAt(OffsetDateTime.now().plusDays(6)).status(DefenseRequestStatus.REJECTED)
                .reason("Просторијата е зафатена.").createdAt(OffsetDateTime.now().minusDays(2))
                .decidedAt(OffsetDateTime.now().minusDays(1)).build();
        DefenseRequest current = pendingRequest(thesis, student, "B2",
                OffsetDateTime.now(), OffsetDateTime.now().plusDays(8));

        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(caller);
        when(defenseRequestRepository.findByThesisOrderByCreatedAtDesc(thesis))
                .thenReturn(List.of(current, rejected));

        List<DefenseRequestResponse> history = defenseService.getDefenseRequests(thesis.getId());

        assertEquals(2, history.size());
        assertTrue(history.stream().anyMatch(r -> r.getStatus() == DefenseRequestStatus.REJECTED
                && "Просторијата е зафатена.".equals(r.getReason())));
        assertTrue(history.stream().anyMatch(r -> r.getStatus() == DefenseRequestStatus.PENDING));
        verify(thesisReadAccessPolicy).requireReadAccess(eq(thesis), eq(caller));
    }

    // =========================================================================
    // U — no duplicate notifications on approval
    // =========================================================================

    @Test
    @DisplayName("U: approval sends exactly one DEFENSE_SCHEDULED notification per recipient — no duplicates")
    void u_approval_noDuplicateNotifications() {
        User service = user(Role.STUDENT_SERVICE);
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);
        Thesis thesis = thesis(student, mentor, ThesisStatus.PENDING_DEFENSE_SCHEDULING);

        OffsetDateTime scheduledAt = OffsetDateTime.now().plusDays(8);
        DefenseRequest pending = pendingRequest(thesis, student, "Room X", OffsetDateTime.now(), scheduledAt);

        // Mentor holds the only committee seat — must be notified exactly once, not twice.
        CommitteeMember mentorSeat = CommitteeMember.builder().id(UUID.randomUUID())
                .thesis(thesis).professor(mentor).memberRole(MemberRole.MENTOR_MEMBER).build();

        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(service);
        when(defenseRequestRepository.findByThesisAndStatus(thesis, DefenseRequestStatus.PENDING))
                .thenReturn(Optional.of(pending));
        when(defenseRepository.findByRoomAndIsCancelledFalse("Room X")).thenReturn(List.of());
        when(committeeRepository.findByThesis(thesis)).thenReturn(List.of(mentorSeat));

        defenseService.decideDefenseRequest(thesis.getId(), decision(true, null));

        verify(notificationService, times(1)).notify(eq(student), eq(thesis), eq(NotificationType.DEFENSE_SCHEDULED), anyString());
        verify(notificationService, times(1)).notify(eq(mentor), eq(thesis), eq(NotificationType.DEFENSE_SCHEDULED), anyString());
        verify(notificationService, never()).notify(any(), any(), eq(NotificationType.DEFENSE_REQUEST_REJECTED), anyString());
    }
}
