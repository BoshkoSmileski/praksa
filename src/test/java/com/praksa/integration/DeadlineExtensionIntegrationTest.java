package com.praksa.integration;

import com.praksa.model.DeadlineExtensionRequest;
import com.praksa.model.Thesis;
import com.praksa.model.enums.DeadlineExtensionStatus;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.ThesisStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real HTTP + real PostgreSQL coverage of the defense deadline extension request (official
 * faculty procedure: a student may request up to 15 additional days, with a reason, subject to
 * Student Service approval). Complements the Mockito business-rule matrix in
 * {@code DeadlineExtensionServiceTest} with scenarios that need the real security chain,
 * transaction boundaries, and database state to be meaningful — most importantly proving that
 * the deadline is never mutated on rejection and never extended twice on a repeated decision.
 */
class DeadlineExtensionIntegrationTest extends AbstractWorkflowIntegrationTest {

    /** Drives a fresh thesis to PENDING_DEFENSE_SCHEDULING — the point defenseDeadline is stamped. */
    private UUID advanceToDeadlineSet(Actors a) throws Exception {
        UUID thesisId = advanceToCommitteeReview(a);
        acceptReview(a.service, thesisId);
        verifyDefenseEligibility(a.service, thesisId, true, true);
        return thesisId;
    }

    private Thesis reload(UUID thesisId) {
        return thesisRepository.findById(thesisId).orElseThrow();
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Happy path over real HTTP+DB: request → PENDING → approve → deadline extended by exactly requestedDays")
    void requestThenApprove_realStack() throws Exception {
        Actors a = newActors();
        UUID thesisId = advanceToDeadlineSet(a);
        OffsetDateTime originalDeadline = reload(thesisId).getDefenseDeadline();
        assertNotNull(originalDeadline, "defenseDeadline must be set once eligibility is verified");

        UUID requestId = requestDeadlineExtension(a.student, thesisId, "Медицинска причина.", 10);

        DeadlineExtensionRequest persisted = deadlineExtensionRequestRepository.findById(requestId).orElseThrow();
        assertEquals(DeadlineExtensionStatus.PENDING, persisted.getStatus());
        assertEquals(10, persisted.getRequestedDays());
        // The deadline must NOT change just from submitting the request.
        assertTrue(originalDeadline.isEqual(reload(thesisId).getDefenseDeadline()));
        // Thesis workflow status is untouched by an administrative sub-process.
        assertEquals(ThesisStatus.PENDING_DEFENSE_SCHEDULING, reload(thesisId).getStatus());

        approveDeadlineExtension(a.service, thesisId);

        Thesis after = reload(thesisId);
        assertTrue(originalDeadline.plusDays(10).isEqual(after.getDefenseDeadline()),
                "deadline must be extended by EXACTLY the requested 10 days");
        assertEquals(ThesisStatus.PENDING_DEFENSE_SCHEDULING, after.getStatus(), "status still untouched");
        DeadlineExtensionRequest decided = deadlineExtensionRequestRepository.findById(requestId).orElseThrow();
        assertEquals(DeadlineExtensionStatus.APPROVED, decided.getStatus());
        assertEquals(1, notificationCount(a.student, NotificationType.DEADLINE_EXTENSION_APPROVED, thesisId));
        assertEquals(1, notificationCount(a.service, NotificationType.DEADLINE_EXTENSION_REQUESTED, thesisId));
    }

    @Test
    @DisplayName("Reject with reason over real HTTP+DB: deadline unchanged, reason preserved, student can resubmit")
    void rejectThenResubmit_realStack() throws Exception {
        Actors a = newActors();
        UUID thesisId = advanceToDeadlineSet(a);
        OffsetDateTime originalDeadline = reload(thesisId).getDefenseDeadline();

        UUID firstId = requestDeadlineExtension(a.student, thesisId, "Прв обид.", 7);
        rejectDeadlineExtension(a.service, thesisId, "Недоволно образложение.");

        DeadlineExtensionRequest first = deadlineExtensionRequestRepository.findById(firstId).orElseThrow();
        assertEquals(DeadlineExtensionStatus.REJECTED, first.getStatus());
        assertEquals("Недоволно образложение.", first.getDecisionReason());
        assertNotNull(first.getDecidedAt());
        assertNull(first.getNewDeadline(), "a rejected request never records a newDeadline");
        assertTrue(originalDeadline.isEqual(reload(thesisId).getDefenseDeadline()), "deadline must stay unchanged");
        assertEquals(1, notificationCount(a.student, NotificationType.DEADLINE_EXTENSION_REJECTED, thesisId));

        // A brand new request is allowed after a rejection — NOT a reuse of the rejected row.
        UUID secondId = requestDeadlineExtension(a.student, thesisId, "Втор обид.", 5);
        assertTrue(!secondId.equals(firstId));
        approveDeadlineExtension(a.service, thesisId);

        assertTrue(originalDeadline.plusDays(5).isEqual(reload(thesisId).getDefenseDeadline()));
    }

    @Test
    @DisplayName("A second PENDING request while one is already pending is rejected (400), no duplicate row")
    void duplicatePending_rejected_realStack() throws Exception {
        Actors a = newActors();
        UUID thesisId = advanceToDeadlineSet(a);
        requestDeadlineExtension(a.student, thesisId, "Прво барање.", 5);

        doPost("/api/theses/" + thesisId + "/deadline-extension-request", a.student,
                Map.of("reason", "Второ барање.", "requestedDays", 5))
                .andExpect(status().isBadRequest());

        List<DeadlineExtensionRequest> all = deadlineExtensionRequestRepository.findByThesisOrderByCreatedAtDesc(reload(thesisId));
        assertEquals(1, all.size());
    }

    @Test
    @DisplayName("A student cannot request a deadline extension for another student's thesis (real 403, real IDOR guard)")
    void nonOwnerStudent_rejected_realStack() throws Exception {
        Actors a = newActors();
        UUID thesisId = advanceToDeadlineSet(a);
        var intruder = createStudent(240);

        doPost("/api/theses/" + thesisId + "/deadline-extension-request", intruder,
                Map.of("reason", "Не е мое.", "requestedDays", 5))
                .andExpect(status().isForbidden());

        assertTrue(deadlineExtensionRequestRepository.findByThesisOrderByCreatedAtDesc(reload(thesisId)).isEmpty());
    }

    @Test
    @DisplayName("An unauthenticated caller is rejected (401/403), no request created")
    void unauthenticated_rejected_realStack() throws Exception {
        Actors a = newActors();
        UUID thesisId = advanceToDeadlineSet(a);

        // Passing a null actor to doPost() omits the Authorization header entirely.
        doPost("/api/theses/" + thesisId + "/deadline-extension-request", null,
                Map.of("reason", "X", "requestedDays", 5))
                .andExpect(status().is4xxClientError());

        assertTrue(deadlineExtensionRequestRepository.findByThesisOrderByCreatedAtDesc(reload(thesisId)).isEmpty());
    }

    @Test
    @DisplayName("A STUDENT cannot decide a request; a MENTOR cannot decide a request (real 403)")
    void unauthorizedDeciders_rejected_realStack() throws Exception {
        Actors a = newActors();
        UUID thesisId = advanceToDeadlineSet(a);
        requestDeadlineExtension(a.student, thesisId, "Причина.", 5);

        doPatch("/api/theses/" + thesisId + "/deadline-extension-decision", a.student,
                Map.of("approved", true))
                .andExpect(status().isForbidden());
        doPatch("/api/theses/" + thesisId + "/deadline-extension-decision", a.mentor,
                Map.of("approved", true))
                .andExpect(status().isForbidden());

        assertEquals(DeadlineExtensionStatus.PENDING,
                deadlineExtensionRequestRepository.findByThesisAndStatus(reload(thesisId), DeadlineExtensionStatus.PENDING)
                        .orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Approving twice over real HTTP is impossible — second decision finds nothing pending (400)")
    void doubleApproval_rejected_realStack() throws Exception {
        Actors a = newActors();
        UUID thesisId = advanceToDeadlineSet(a);
        OffsetDateTime originalDeadline = reload(thesisId).getDefenseDeadline();
        requestDeadlineExtension(a.student, thesisId, "Причина.", 8);
        approveDeadlineExtension(a.service, thesisId);
        OffsetDateTime afterFirstApproval = reload(thesisId).getDefenseDeadline();

        doPatch("/api/theses/" + thesisId + "/deadline-extension-decision", a.service,
                Map.of("approved", true))
                .andExpect(status().isBadRequest());

        assertTrue(afterFirstApproval.isEqual(reload(thesisId).getDefenseDeadline()),
                "a second (rejected) decision attempt must never extend the deadline again");
        assertTrue(originalDeadline.plusDays(8).isEqual(afterFirstApproval));
    }

    @Test
    @DisplayName("A second extension request is blocked once one has already been APPROVED for this thesis")
    void secondExtensionAfterApproval_blocked_realStack() throws Exception {
        Actors a = newActors();
        UUID thesisId = advanceToDeadlineSet(a);
        requestDeadlineExtension(a.student, thesisId, "Прво.", 5);
        approveDeadlineExtension(a.service, thesisId);

        doPost("/api/theses/" + thesisId + "/deadline-extension-request", a.student,
                Map.of("reason", "Второ.", "requestedDays", 5))
                .andExpect(status().isBadRequest());

        List<DeadlineExtensionRequest> all = deadlineExtensionRequestRepository.findByThesisOrderByCreatedAtDesc(reload(thesisId));
        assertEquals(1, all.size(), "the blocked second submission must not create a row");
    }

    @Test
    @DisplayName("Requesting more than 15 days is rejected server-side (400), even bypassing client-side checks")
    void moreThanFifteenDays_rejected_realStack() throws Exception {
        Actors a = newActors();
        UUID thesisId = advanceToDeadlineSet(a);

        doPost("/api/theses/" + thesisId + "/deadline-extension-request", a.student,
                Map.of("reason", "Премногу.", "requestedDays", 16))
                .andExpect(status().isBadRequest());

        assertTrue(deadlineExtensionRequestRepository.findByThesisOrderByCreatedAtDesc(reload(thesisId)).isEmpty());
    }

    @Test
    @DisplayName("Regression: the 5-15 day defense-request window, room booking, and grading workflow still work "
            + "unaffected by the deadline-extension feature")
    void existingDefenseWorkflow_stillWorks_realStack() throws Exception {
        Actors a = newActors();
        UUID thesisId = advanceToDeadlineSet(a);

        // Extend the deadline first, to prove it doesn't interfere with the unrelated
        // defense-request/room-booking/grading pipeline that follows.
        requestDeadlineExtension(a.student, thesisId, "Причина.", 5);
        approveDeadlineExtension(a.service, thesisId);

        requestDefense(a.student, thesisId, "Deadline-Ext-Room", OffsetDateTime.now().plusDays(9));
        approveDefenseRequest(a.service, thesisId);
        assertEquals(ThesisStatus.DEFENSE_SCHEDULED, reload(thesisId).getStatus());

        var defense = defenseRepository.findByThesisAndIsCancelledFalse(reload(thesisId)).orElseThrow();
        recordResult(a.professorA, thesisId, defense.getId(), 8);
        assertEquals(ThesisStatus.ARCHIVED, reload(thesisId).getStatus());
        assertNotNull(reload(thesisId).getArchiveRegistrationNumber());
    }
}
