package com.praksa.service;

import com.praksa.dto.thesis.DeadlineExtensionCreateRequest;
import com.praksa.dto.thesis.DeadlineExtensionDecisionRequest;
import com.praksa.dto.thesis.DeadlineExtensionResponse;
import com.praksa.exception.BadRequestException;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.DeadlineExtensionRequest;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.DeadlineExtensionStatus;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.DeadlineExtensionRequestRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.security.ThesisReadAccessPolicy;
import com.praksa.service.impl.DeadlineExtensionServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Official faculty procedure: a student may request an extension of the defense deadline, for a
 * maximum of 15 additional days, with a written explanation. STUDENT_SERVICE approves or rejects
 * the request; approval extends {@code Thesis.defenseDeadline} by EXACTLY the requested amount,
 * rejection leaves it unchanged. See {@code DeadlineExtensionServiceImpl} and the dated CLAUDE.md
 * handoff entry for the full design rationale.
 *
 * <p>Pure Mockito unit tests, no Spring context, no database. Mirrors the style of
 * {@code DefenseRequestApplicationAgeTest} / {@code DefenseRequestWorkflowTest}.
 */
@ExtendWith(MockitoExtension.class)
class DeadlineExtensionServiceTest {

    @Mock private DeadlineExtensionRequestRepository extensionRequestRepository;
    @Mock private ThesisRepository thesisRepository;
    @Mock private SecurityUtils securityUtils;
    @Mock private NotificationService notificationService;
    @Mock private ThesisReadAccessPolicy thesisReadAccessPolicy;

    @InjectMocks private DeadlineExtensionServiceImpl service;

    private User user(Role role) {
        return User.builder().id(UUID.randomUUID()).email(role + "-" + UUID.randomUUID() + "@t.com")
                .fullName(role + " User").role(role).build();
    }

    private Thesis thesisWithDeadline(User student, OffsetDateTime deadline) {
        return Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).status(ThesisStatus.PENDING_DEFENSE_SCHEDULING)
                .defenseDeadline(deadline).build();
    }

    private DeadlineExtensionCreateRequest createReq(String reason, Integer days) {
        DeadlineExtensionCreateRequest r = new DeadlineExtensionCreateRequest();
        r.setReason(reason);
        r.setRequestedDays(days);
        return r;
    }

    private DeadlineExtensionDecisionRequest decisionReq(boolean approved, String reason) {
        DeadlineExtensionDecisionRequest r = new DeadlineExtensionDecisionRequest();
        r.setApproved(approved);
        r.setReason(reason);
        return r;
    }

    private void mockThesis(Thesis thesis) {
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
    }

    // =========================================================================
    // SUBMIT — authorization
    // =========================================================================

    @Test
    @DisplayName("1: the thesis owner (STUDENT) can submit a deadline extension request")
    void owner_canSubmit() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesisWithDeadline(student, OffsetDateTime.now().plusDays(10));
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);
        when(extensionRequestRepository.findByThesisAndStatus(thesis, DeadlineExtensionStatus.PENDING))
                .thenReturn(Optional.empty());
        when(extensionRequestRepository.findByThesisAndStatus(thesis, DeadlineExtensionStatus.APPROVED))
                .thenReturn(Optional.empty());

        DeadlineExtensionResponse resp = service.submitDeadlineExtensionRequest(
                thesis.getId(), createReq("Медицинска причина.", 10));

        assertEquals(DeadlineExtensionStatus.PENDING, resp.getStatus());
        assertEquals(10, resp.getRequestedDays());
        assertEquals("Медицинска причина.", resp.getReason());
        verify(extensionRequestRepository).save(any());
        verify(notificationService).notifyRole(eq(Role.STUDENT_SERVICE), eq(thesis),
                eq(NotificationType.DEADLINE_EXTENSION_REQUESTED));
    }

    @Test
    @DisplayName("2: a non-owner student cannot submit for another student's thesis")
    void nonOwner_cannotSubmit() {
        User owner = user(Role.STUDENT);
        User intruder = user(Role.STUDENT);
        Thesis thesis = thesisWithDeadline(owner, OffsetDateTime.now().plusDays(10));
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(intruder);

        assertThrows(UnauthorizedException.class, () ->
                service.submitDeadlineExtensionRequest(thesis.getId(), createReq("X", 5)));

        verify(extensionRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("a MENTOR (or any non-STUDENT role) cannot submit a request")
    void nonStudent_cannotSubmit() {
        User mentor = user(Role.MENTOR);
        when(securityUtils.getCurrentUser()).thenReturn(mentor);

        assertThrows(UnauthorizedException.class, () ->
                service.submitDeadlineExtensionRequest(UUID.randomUUID(), createReq("X", 5)));

        verify(extensionRequestRepository, never()).save(any());
        verify(thesisRepository, never()).findById(any());
    }

    // =========================================================================
    // SUBMIT — validation
    // =========================================================================

    @Test
    @DisplayName("submitting without a defense deadline set is rejected")
    void noDeadlineSet_rejected() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesisWithDeadline(student, null);
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);

        assertThrows(BadRequestException.class, () ->
                service.submitDeadlineExtensionRequest(thesis.getId(), createReq("X", 5)));

        verify(extensionRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("service-layer defense-in-depth: requestedDays > 15 rejected even if the DTO check is bypassed")
    void requestedDaysAbove15_rejectedByService() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesisWithDeadline(student, OffsetDateTime.now().plusDays(10));
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);

        assertThrows(BadRequestException.class, () ->
                service.submitDeadlineExtensionRequest(thesis.getId(), createReq("X", 16)));

        verify(extensionRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("service-layer defense-in-depth: requestedDays < 1 rejected even if the DTO check is bypassed")
    void requestedDaysBelow1_rejectedByService() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesisWithDeadline(student, OffsetDateTime.now().plusDays(10));
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);

        assertThrows(BadRequestException.class, () ->
                service.submitDeadlineExtensionRequest(thesis.getId(), createReq("X", 0)));

        verify(extensionRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("a blank reason is rejected by the service layer too")
    void blankReason_rejectedByService() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesisWithDeadline(student, OffsetDateTime.now().plusDays(10));
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);

        assertThrows(BadRequestException.class, () ->
                service.submitDeadlineExtensionRequest(thesis.getId(), createReq("   ", 5)));

        verify(extensionRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("11: a second submission while one is already PENDING is rejected")
    void duplicatePending_rejected() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesisWithDeadline(student, OffsetDateTime.now().plusDays(10));
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);
        DeadlineExtensionRequest existingPending = DeadlineExtensionRequest.builder()
                .id(UUID.randomUUID()).thesis(thesis).requestedBy(student)
                .status(DeadlineExtensionStatus.PENDING).build();
        when(extensionRequestRepository.findByThesisAndStatus(thesis, DeadlineExtensionStatus.PENDING))
                .thenReturn(Optional.of(existingPending));

        assertThrows(BadRequestException.class, () ->
                service.submitDeadlineExtensionRequest(thesis.getId(), createReq("Уште еднаш.", 5)));

        verify(extensionRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("submission is rejected once an APPROVED extension already exists for this thesis")
    void alreadyApprovedExtension_blocksFurtherSubmission() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesisWithDeadline(student, OffsetDateTime.now().plusDays(20));
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);
        when(extensionRequestRepository.findByThesisAndStatus(thesis, DeadlineExtensionStatus.PENDING))
                .thenReturn(Optional.empty());
        DeadlineExtensionRequest existingApproved = DeadlineExtensionRequest.builder()
                .id(UUID.randomUUID()).thesis(thesis).requestedBy(student)
                .status(DeadlineExtensionStatus.APPROVED).build();
        when(extensionRequestRepository.findByThesisAndStatus(thesis, DeadlineExtensionStatus.APPROVED))
                .thenReturn(Optional.of(existingApproved));

        assertThrows(BadRequestException.class, () ->
                service.submitDeadlineExtensionRequest(thesis.getId(), createReq("Повторно.", 5)));

        verify(extensionRequestRepository, never()).save(any());
    }

    // =========================================================================
    // DECISION — authorization
    // =========================================================================

    @Test
    @DisplayName("4: STUDENT_SERVICE can decide a pending request")
    void studentService_canDecide() {
        User service_ = user(Role.STUDENT_SERVICE);
        User student = user(Role.STUDENT);
        Thesis thesis = thesisWithDeadline(student, OffsetDateTime.now().plusDays(10));
        DeadlineExtensionRequest pending = pendingRequest(thesis, student, 10);
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(service_);
        when(extensionRequestRepository.findByThesisAndStatus(thesis, DeadlineExtensionStatus.PENDING))
                .thenReturn(Optional.of(pending));

        DeadlineExtensionResponse resp = service.decideDeadlineExtensionRequest(thesis.getId(), decisionReq(true, null));

        assertEquals(DeadlineExtensionStatus.APPROVED, resp.getStatus());
    }

    @Test
    @DisplayName("5: a STUDENT cannot decide a request")
    void student_cannotDecide() {
        User student = user(Role.STUDENT);
        when(securityUtils.getCurrentUser()).thenReturn(student);

        assertThrows(UnauthorizedException.class, () ->
                service.decideDeadlineExtensionRequest(UUID.randomUUID(), decisionReq(true, null)));

        verify(thesisRepository, never()).findById(any());
    }

    @ParameterizedTest(name = "6: role {0} cannot decide a request")
    @EnumSource(value = Role.class, names = {"MENTOR", "COMMITTEE", "ARCHIVE"})
    @DisplayName("6: an unauthorized role cannot decide a request")
    void otherRoles_cannotDecide(Role role) {
        User caller = user(role);
        when(securityUtils.getCurrentUser()).thenReturn(caller);

        assertThrows(UnauthorizedException.class, () ->
                service.decideDeadlineExtensionRequest(UUID.randomUUID(), decisionReq(true, null)));

        verify(thesisRepository, never()).findById(any());
    }

    @Test
    @DisplayName("deciding when no request is pending is rejected")
    void noPendingRequest_decisionRejected() {
        User service_ = user(Role.STUDENT_SERVICE);
        User student = user(Role.STUDENT);
        Thesis thesis = thesisWithDeadline(student, OffsetDateTime.now().plusDays(10));
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(service_);
        when(extensionRequestRepository.findByThesisAndStatus(thesis, DeadlineExtensionStatus.PENDING))
                .thenReturn(Optional.empty());

        assertThrows(BadRequestException.class, () ->
                service.decideDeadlineExtensionRequest(thesis.getId(), decisionReq(true, null)));

        verify(thesisRepository, never()).save(any());
    }

    // =========================================================================
    // APPROVAL
    // =========================================================================

    @Test
    @DisplayName("12/13/14: approval marks APPROVED and extends the deadline by EXACTLY the requested amount")
    void approval_extendsDeadlineByExactAmount() {
        User service_ = user(Role.STUDENT_SERVICE);
        User student = user(Role.STUDENT);
        OffsetDateTime originalDeadline = OffsetDateTime.now().plusDays(3);
        Thesis thesis = thesisWithDeadline(student, originalDeadline);
        DeadlineExtensionRequest pending = pendingRequest(thesis, student, 15);
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(service_);
        when(extensionRequestRepository.findByThesisAndStatus(thesis, DeadlineExtensionStatus.PENDING))
                .thenReturn(Optional.of(pending));

        DeadlineExtensionResponse resp = service.decideDeadlineExtensionRequest(thesis.getId(), decisionReq(true, null));

        assertEquals(DeadlineExtensionStatus.APPROVED, resp.getStatus());
        assertEquals(originalDeadline.plusDays(15), thesis.getDefenseDeadline(),
                "the deadline must be extended by EXACTLY the requested 15 days, never more/less");
        assertEquals(originalDeadline.plusDays(15), resp.getNewDeadline());
        assertEquals(originalDeadline, resp.getPreviousDeadline());
        verify(thesisRepository).save(thesis);
    }

    @Test
    @DisplayName("approval with a 1-day request extends by exactly 1 day (lower boundary)")
    void approval_oneDayBoundary() {
        User service_ = user(Role.STUDENT_SERVICE);
        User student = user(Role.STUDENT);
        OffsetDateTime originalDeadline = OffsetDateTime.now().plusDays(3);
        Thesis thesis = thesisWithDeadline(student, originalDeadline);
        DeadlineExtensionRequest pending = pendingRequest(thesis, student, 1);
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(service_);
        when(extensionRequestRepository.findByThesisAndStatus(thesis, DeadlineExtensionStatus.PENDING))
                .thenReturn(Optional.of(pending));

        service.decideDeadlineExtensionRequest(thesis.getId(), decisionReq(true, null));

        assertEquals(originalDeadline.plusDays(1), thesis.getDefenseDeadline());
    }

    @Test
    @DisplayName("15: approving a deadline extension never changes the thesis's workflow status")
    void approval_neverChangesThesisStatus() {
        User service_ = user(Role.STUDENT_SERVICE);
        User student = user(Role.STUDENT);
        Thesis thesis = thesisWithDeadline(student, OffsetDateTime.now().plusDays(5));
        ThesisStatus statusBefore = thesis.getStatus();
        DeadlineExtensionRequest pending = pendingRequest(thesis, student, 7);
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(service_);
        when(extensionRequestRepository.findByThesisAndStatus(thesis, DeadlineExtensionStatus.PENDING))
                .thenReturn(Optional.of(pending));

        service.decideDeadlineExtensionRequest(thesis.getId(), decisionReq(true, null));

        assertEquals(statusBefore, thesis.getStatus(), "status must be untouched by a deadline extension");
    }

    @Test
    @DisplayName("16: approving twice is impossible — the second call finds no pending request")
    void approvalCannotBeRepeated() {
        User service_ = user(Role.STUDENT_SERVICE);
        User student = user(Role.STUDENT);
        Thesis thesis = thesisWithDeadline(student, OffsetDateTime.now().plusDays(5));
        DeadlineExtensionRequest pending = pendingRequest(thesis, student, 5);
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(service_);
        // First call sees the PENDING row.
        when(extensionRequestRepository.findByThesisAndStatus(thesis, DeadlineExtensionStatus.PENDING))
                .thenReturn(Optional.of(pending))
                // Second call: the row is no longer PENDING (approved by the first call in real
                // usage) — simulated here by returning empty, exactly what the repository would
                // return for real once pending.setStatus(APPROVED) has been persisted.
                .thenReturn(Optional.empty());

        service.decideDeadlineExtensionRequest(thesis.getId(), decisionReq(true, null));
        OffsetDateTime deadlineAfterFirstApproval = thesis.getDefenseDeadline();

        assertThrows(BadRequestException.class, () ->
                service.decideDeadlineExtensionRequest(thesis.getId(), decisionReq(true, null)));

        assertEquals(deadlineAfterFirstApproval, thesis.getDefenseDeadline(),
                "the second (rejected) call must never extend the deadline again");
    }

    @Test
    @DisplayName("21: the student receives an approval notification with the new deadline")
    void approval_sendsNotificationToStudent() {
        User service_ = user(Role.STUDENT_SERVICE);
        User student = user(Role.STUDENT);
        Thesis thesis = thesisWithDeadline(student, OffsetDateTime.now().plusDays(5));
        DeadlineExtensionRequest pending = pendingRequest(thesis, student, 5);
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(service_);
        when(extensionRequestRepository.findByThesisAndStatus(thesis, DeadlineExtensionStatus.PENDING))
                .thenReturn(Optional.of(pending));

        service.decideDeadlineExtensionRequest(thesis.getId(), decisionReq(true, null));

        verify(notificationService).notify(eq(student), eq(thesis),
                eq(NotificationType.DEADLINE_EXTENSION_APPROVED), any(String.class));
    }

    // =========================================================================
    // REJECTION
    // =========================================================================

    @Test
    @DisplayName("a rejection with no reason is rejected (400)")
    void rejectionWithoutReason_rejected() {
        User service_ = user(Role.STUDENT_SERVICE);
        User student = user(Role.STUDENT);
        Thesis thesis = thesisWithDeadline(student, OffsetDateTime.now().plusDays(5));
        DeadlineExtensionRequest pending = pendingRequest(thesis, student, 5);
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(service_);
        when(extensionRequestRepository.findByThesisAndStatus(thesis, DeadlineExtensionStatus.PENDING))
                .thenReturn(Optional.of(pending));

        assertThrows(BadRequestException.class, () ->
                service.decideDeadlineExtensionRequest(thesis.getId(), decisionReq(false, "  ")));

        verify(thesisRepository, never()).save(any());
    }

    @Test
    @DisplayName("17/18/19: rejection marks REJECTED, preserves the reason, and leaves the deadline unchanged")
    void rejection_preservesReasonAndLeavesDeadlineUnchanged() {
        User service_ = user(Role.STUDENT_SERVICE);
        User student = user(Role.STUDENT);
        OffsetDateTime originalDeadline = OffsetDateTime.now().plusDays(5);
        Thesis thesis = thesisWithDeadline(student, originalDeadline);
        DeadlineExtensionRequest pending = pendingRequest(thesis, student, 10);
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(service_);
        when(extensionRequestRepository.findByThesisAndStatus(thesis, DeadlineExtensionStatus.PENDING))
                .thenReturn(Optional.of(pending));

        DeadlineExtensionResponse resp = service.decideDeadlineExtensionRequest(
                thesis.getId(), decisionReq(false, "Недоволно образложение."));

        assertEquals(DeadlineExtensionStatus.REJECTED, resp.getStatus());
        assertEquals("Недоволно образложение.", resp.getDecisionReason());
        assertEquals(originalDeadline, thesis.getDefenseDeadline(), "rejection must never change the deadline");
        assertNull(resp.getNewDeadline(), "a rejected request never gets a newDeadline");
        verify(thesisRepository, never()).save(any());
    }

    @Test
    @DisplayName("20: rejecting twice is impossible — the second call finds no pending request")
    void rejectionCannotBeRepeated() {
        User service_ = user(Role.STUDENT_SERVICE);
        User student = user(Role.STUDENT);
        Thesis thesis = thesisWithDeadline(student, OffsetDateTime.now().plusDays(5));
        DeadlineExtensionRequest pending = pendingRequest(thesis, student, 10);
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(service_);
        when(extensionRequestRepository.findByThesisAndStatus(thesis, DeadlineExtensionStatus.PENDING))
                .thenReturn(Optional.of(pending))
                .thenReturn(Optional.empty());

        service.decideDeadlineExtensionRequest(thesis.getId(), decisionReq(false, "Прва причина."));

        assertThrows(BadRequestException.class, () ->
                service.decideDeadlineExtensionRequest(thesis.getId(), decisionReq(false, "Втора причина.")));

        assertEquals("Прва причина.", pending.getDecisionReason(), "the second call must never overwrite the first decision");
    }

    @Test
    @DisplayName("22/23: the student receives exactly one rejection notification carrying the reason, no duplicate")
    void rejection_sendsExactlyOneNotificationWithReason() {
        User service_ = user(Role.STUDENT_SERVICE);
        User student = user(Role.STUDENT);
        Thesis thesis = thesisWithDeadline(student, OffsetDateTime.now().plusDays(5));
        DeadlineExtensionRequest pending = pendingRequest(thesis, student, 10);
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(service_);
        when(extensionRequestRepository.findByThesisAndStatus(thesis, DeadlineExtensionStatus.PENDING))
                .thenReturn(Optional.of(pending));

        service.decideDeadlineExtensionRequest(thesis.getId(), decisionReq(false, "Недоволна причина."));

        verify(notificationService).notify(eq(student), eq(thesis),
                eq(NotificationType.DEADLINE_EXTENSION_REJECTED), any(String.class));
        verify(notificationService, never()).notifyRole(any(), any(), any());
    }

    // =========================================================================
    // Repeated request after rejection — allowed
    // =========================================================================

    @Test
    @DisplayName("a student can submit a brand-new request after a rejection")
    void newRequestAllowedAfterRejection() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesisWithDeadline(student, OffsetDateTime.now().plusDays(5));
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);
        // No PENDING and no APPROVED row exists — only a REJECTED one, which never blocks.
        when(extensionRequestRepository.findByThesisAndStatus(thesis, DeadlineExtensionStatus.PENDING))
                .thenReturn(Optional.empty());
        when(extensionRequestRepository.findByThesisAndStatus(thesis, DeadlineExtensionStatus.APPROVED))
                .thenReturn(Optional.empty());

        DeadlineExtensionResponse resp = service.submitDeadlineExtensionRequest(
                thesis.getId(), createReq("Втор обид.", 5));

        assertEquals(DeadlineExtensionStatus.PENDING, resp.getStatus());
    }

    // =========================================================================
    // Reason is trimmed before storage
    // =========================================================================

    @Test
    @DisplayName("the reason is trimmed before being stored")
    void reasonIsTrimmed() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesisWithDeadline(student, OffsetDateTime.now().plusDays(5));
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);
        when(extensionRequestRepository.findByThesisAndStatus(thesis, DeadlineExtensionStatus.PENDING))
                .thenReturn(Optional.empty());
        when(extensionRequestRepository.findByThesisAndStatus(thesis, DeadlineExtensionStatus.APPROVED))
                .thenReturn(Optional.empty());

        DeadlineExtensionResponse resp = service.submitDeadlineExtensionRequest(
                thesis.getId(), createReq("   Патување во странство.   ", 5));

        assertEquals("Патување во странство.", resp.getReason());
    }

    // =========================================================================
    // Read (history) — thesis-scoped access
    // =========================================================================

    @Test
    @DisplayName("read history is authorized via the shared ThesisReadAccessPolicy")
    void history_authorizedViaReadAccessPolicy() {
        User student = user(Role.STUDENT);
        Thesis thesis = thesisWithDeadline(student, OffsetDateTime.now().plusDays(5));
        mockThesis(thesis);
        when(securityUtils.getCurrentUser()).thenReturn(student);
        when(extensionRequestRepository.findByThesisOrderByCreatedAtDesc(thesis))
                .thenReturn(java.util.List.of());

        assertTrue(service.getDeadlineExtensionRequests(thesis.getId()).isEmpty());
        verify(thesisReadAccessPolicy).requireReadAccess(thesis, student);
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private DeadlineExtensionRequest pendingRequest(Thesis thesis, User student, int days) {
        return DeadlineExtensionRequest.builder()
                .id(UUID.randomUUID())
                .thesis(thesis)
                .requestedBy(student)
                .reason("Медицинска причина.")
                .requestedDays(days)
                .status(DeadlineExtensionStatus.PENDING)
                .createdAt(OffsetDateTime.now())
                .build();
    }
}
