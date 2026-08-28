package com.praksa.service;

import com.praksa.dto.defense.DefenseRequestCreateRequest;
import com.praksa.dto.defense.DefenseRequestDecisionRequest;
import com.praksa.dto.defense.DefenseRequestResponse;
import com.praksa.exception.BadRequestException;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.DefenseRequest;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.DefenseRequestStatus;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
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
 * Official faculty procedure: the request for forming the committee / requesting a defense
 * may only be submitted at least 14 FULL days after the formal thesis application was
 * submitted ({@link Thesis#getApplicationSubmittedAt()}, stamped by
 * {@code ThesisServiceImpl#submitApplication}). In this codebase the only student-initiated
 * "request" downstream of committee formation/review and defense-eligibility verification is
 * {@code DefenseServiceImpl#createDefenseRequest} — the gate lives there
 * (see {@code requireApplicationAgeMet}).
 *
 * <p>Pure Mockito unit tests, no Spring context, no database. Complements
 * {@link DefenseRequestWorkflowTest}, whose thesis fixtures are backdated 30 days precisely so
 * they remain unaffected by this rule; this class tests the rule itself with exact day/hour
 * boundaries.
 */
@ExtendWith(MockitoExtension.class)
class DefenseRequestApplicationAgeTest {

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

    private Thesis thesisWithSubmission(User student, User mentor, OffsetDateTime applicationSubmittedAt) {
        return Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).mentor(mentor).status(ThesisStatus.PENDING_DEFENSE_SCHEDULING)
                .applicationSubmittedAt(applicationSubmittedAt)
                .build();
    }

    private DefenseRequestCreateRequest createReq(String room, OffsetDateTime at) {
        DefenseRequestCreateRequest r = new DefenseRequestCreateRequest();
        r.setRoom(room);
        r.setScheduledAt(at);
        return r;
    }

    private void mockThesis(Thesis thesis) {
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
    }

    // =========================================================================
    // A: exactly 13 days elapsed — rejected
    // =========================================================================

    @Test
    @DisplayName("A: exactly 13 days since application submission — rejected")
    void exactlyThirteenDays_rejected() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesisWithSubmission(student, user(Role.MENTOR), OffsetDateTime.now().minusDays(13));
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);

        assertThrows(BadRequestException.class, () ->
                defenseService.createDefenseRequest(thesis.getId(), createReq("X1", OffsetDateTime.now().plusDays(7))));

        verify(defenseRequestRepository, never()).save(any());
    }

    // =========================================================================
    // B: 13 days + 23 hours — rejected
    // =========================================================================

    @Test
    @DisplayName("B: 13 days and 23 hours since application submission — rejected")
    void thirteenDaysTwentyThreeHours_rejected() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesisWithSubmission(student, user(Role.MENTOR),
                OffsetDateTime.now().minusDays(13).minusHours(23));
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);

        assertThrows(BadRequestException.class, () ->
                defenseService.createDefenseRequest(thesis.getId(), createReq("X1", OffsetDateTime.now().plusDays(7))));

        verify(defenseRequestRepository, never()).save(any());
    }

    // =========================================================================
    // C: exactly 14 days elapsed — accepted
    // =========================================================================

    @Test
    @DisplayName("C: exactly 14 days since application submission — accepted")
    void exactlyFourteenDays_accepted() {
        User student = user(Role.STUDENT);
        // A tiny safety margin (a couple of seconds past exactly 14 days) absorbs the few
        // milliseconds of real wall-clock time between building this fixture and the
        // service's own OffsetDateTime.now() call — without it, "exactly" 14 days could
        // occasionally land a few ms short due to test-execution jitter. Mirrors the
        // boundary-test style already used in DefenseRequestWorkflowTest (G/H).
        Thesis thesis = thesisWithSubmission(student, user(Role.MENTOR),
                OffsetDateTime.now().minusDays(14).minusSeconds(2));
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);
        when(defenseRequestRepository.findByThesisAndStatus(thesis, DefenseRequestStatus.PENDING))
                .thenReturn(Optional.empty());

        defenseService.createDefenseRequest(thesis.getId(), createReq("X1", OffsetDateTime.now().plusDays(7)));

        verify(defenseRequestRepository).save(any());
    }

    // =========================================================================
    // D: more than 14 days — accepted
    // =========================================================================

    @Test
    @DisplayName("D: more than 14 days since application submission — accepted")
    void moreThanFourteenDays_accepted() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesisWithSubmission(student, user(Role.MENTOR), OffsetDateTime.now().minusDays(30));
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);
        when(defenseRequestRepository.findByThesisAndStatus(thesis, DefenseRequestStatus.PENDING))
                .thenReturn(Optional.empty());

        defenseService.createDefenseRequest(thesis.getId(), createReq("X1", OffsetDateTime.now().plusDays(7)));

        verify(defenseRequestRepository).save(any());
    }

    // =========================================================================
    // E: applicationSubmittedAt is null — rejected, never silently bypassed
    // =========================================================================

    @Test
    @DisplayName("E: null applicationSubmittedAt is rejected, not silently bypassed")
    void nullApplicationSubmittedAt_rejected() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesisWithSubmission(student, user(Role.MENTOR), null);
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);

        assertThrows(BadRequestException.class, () ->
                defenseService.createDefenseRequest(thesis.getId(), createReq("X1", OffsetDateTime.now().plusDays(7))));

        verify(defenseRequestRepository, never()).save(any());
    }

    // =========================================================================
    // F: unauthorized/non-owner student — existing authorization preserved,
    // the 14-day rule must never turn an authorization failure into a data leak
    // =========================================================================

    @Test
    @DisplayName("F: a non-owner student is rejected by ownership BEFORE the 14-day check runs")
    void nonOwnerStudent_rejectedByOwnership_notByAgeCheck() {
        User owner = user(Role.STUDENT);
        User intruder = user(Role.STUDENT);
        // Deliberately still WITHIN the 14-day window — if the ownership guard were ever
        // bypassed or reordered after the age check, this would incorrectly surface the
        // age-check's BadRequestException instead of an authorization failure.
        Thesis thesis = thesisWithSubmission(owner, user(Role.MENTOR), OffsetDateTime.now().minusDays(2));
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(intruder);

        assertThrows(UnauthorizedException.class, () ->
                defenseService.createDefenseRequest(thesis.getId(), createReq("X1", OffsetDateTime.now().plusDays(7))));

        verify(defenseRequestRepository, never()).save(any());
    }

    // =========================================================================
    // H: once satisfied, the existing defense-request workflow is unaffected —
    // still just a PENDING request, no Defense, no status change
    // =========================================================================

    @Test
    @DisplayName("H: once the 14-day requirement is satisfied, requesting still only creates a PENDING request")
    void afterFourteenDays_existingRequestWorkflowUnaffected() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesisWithSubmission(student, user(Role.MENTOR), OffsetDateTime.now().minusDays(20));
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);
        when(defenseRequestRepository.findByThesisAndStatus(thesis, DefenseRequestStatus.PENDING))
                .thenReturn(Optional.empty());

        OffsetDateTime at = OffsetDateTime.now().plusDays(9);
        DefenseRequestResponse resp = defenseService.createDefenseRequest(thesis.getId(), createReq("Hall 1", at));

        assertEquals(DefenseRequestStatus.PENDING, resp.getStatus());
        assertEquals(ThesisStatus.PENDING_DEFENSE_SCHEDULING, thesis.getStatus(),
                "creating a request never changes thesis status");
        verify(defenseRepository, never()).save(any());
        verify(statusHistoryRepository, never()).save(any());
        verify(notificationService).notifyRole(eq(Role.STUDENT_SERVICE), eq(thesis), eq(NotificationType.DEFENSE_REQUESTED));
    }

    // =========================================================================
    // I: a valid post-14-day request can still be rejected by Student Service,
    // requiring the student to submit a new proposal exactly as before
    // =========================================================================

    @Test
    @DisplayName("I: a request submitted after the 14-day wait can still be rejected by Student Service exactly as before")
    void postFourteenDayRequest_stillRejectableByService() {
        User service = user(Role.STUDENT_SERVICE);
        User student = user(Role.STUDENT);
        Thesis thesis = thesisWithSubmission(student, user(Role.MENTOR), OffsetDateTime.now().minusDays(20));

        DefenseRequest pending = DefenseRequest.builder().id(UUID.randomUUID())
                .thesis(thesis).requestedBy(student).room("A1")
                .scheduledAt(OffsetDateTime.now().plusDays(8))
                .status(DefenseRequestStatus.PENDING)
                .createdAt(OffsetDateTime.now())
                .build();

        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(service);
        when(defenseRequestRepository.findByThesisAndStatus(thesis, DefenseRequestStatus.PENDING))
                .thenReturn(Optional.of(pending));

        DefenseRequestDecisionRequest decision = new DefenseRequestDecisionRequest();
        decision.setApproved(false);
        decision.setReason("Недостасува документација.");

        defenseService.decideDefenseRequest(thesis.getId(), decision);

        assertEquals(DefenseRequestStatus.REJECTED, pending.getStatus());
        assertEquals("Недостасува документација.", pending.getReason());
        assertEquals(ThesisStatus.PENDING_DEFENSE_SCHEDULING, thesis.getStatus(), "rejection never transitions the thesis");
        verify(defenseRepository, never()).save(any());
        verify(notificationService).notify(eq(student), eq(thesis),
                eq(NotificationType.DEFENSE_REQUEST_REJECTED), eq("Недостасува документација."));
    }
}
