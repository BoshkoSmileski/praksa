package com.praksa.integration;

import com.praksa.model.Defense;
import com.praksa.model.DefenseRequest;
import com.praksa.model.Thesis;
import com.praksa.model.enums.DefenseRequestStatus;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.ThesisStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real HTTP + real PostgreSQL coverage of the student-proposed defense-scheduling redesign.
 * Complements the Mockito business-rule matrix in {@code DefenseRequestWorkflowTest} with the
 * scenarios that specifically need the real database/transaction/security stack to be
 * meaningful — most importantly the double-booking race the spec calls out explicitly
 * (section 15/21): two real theses proposing the same room/time, the first approval wins,
 * the second is genuinely rejected against the committed DB state.
 */
class DefenseRequestIntegrationTest extends AbstractWorkflowIntegrationTest {

    /** Drives a fresh thesis to PENDING_DEFENSE_SCHEDULING (ready to propose a defense term). */
    private UUID advanceToReadyForProposal(Actors a) throws Exception {
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
    @DisplayName("Happy path over real HTTP+DB: propose → PENDING → approve → DEFENSE_SCHEDULED, exactly one Defense row")
    void createThenApprove_realStack() throws Exception {
        Actors a = newActors();
        UUID thesisId = advanceToReadyForProposal(a);
        OffsetDateTime when = OffsetDateTime.now().plusDays(9);

        UUID requestId = requestDefense(a.student, thesisId, "Amphitheater A", when);
        assertEquals(ThesisStatus.PENDING_DEFENSE_SCHEDULING, reload(thesisId).getStatus());
        assertTrue(defenseRepository.findByThesis(reload(thesisId)).isEmpty());

        DefenseRequest persisted = defenseRequestRepository.findById(requestId).orElseThrow();
        assertEquals(DefenseRequestStatus.PENDING, persisted.getStatus());
        assertEquals("Amphitheater A", persisted.getRoom());

        approveDefenseRequest(a.service, thesisId);

        assertEquals(ThesisStatus.DEFENSE_SCHEDULED, reload(thesisId).getStatus());
        Defense defense = defenseRepository.findByThesisAndIsCancelledFalse(reload(thesisId)).orElseThrow();
        assertEquals("Amphitheater A", defense.getRoom());
        assertTrue(when.isEqual(defense.getScheduledAt()), "same instant (Postgres round-trips timestamptz as UTC)");
        assertEquals(DefenseRequestStatus.APPROVED, defenseRequestRepository.findById(requestId).orElseThrow().getStatus());
        assertEquals(1, notificationCount(a.student, NotificationType.DEFENSE_SCHEDULED, thesisId));
    }

    @Test
    @DisplayName("Reject with reason over real HTTP+DB: student can submit a NEW proposal afterward")
    void rejectThenNewProposal_realStack() throws Exception {
        Actors a = newActors();
        UUID thesisId = advanceToReadyForProposal(a);

        UUID firstId = requestDefense(a.student, thesisId, "Room 9", OffsetDateTime.now().plusDays(7));
        rejectDefenseRequest(a.service, thesisId, "Термин не е соодветен.");

        DefenseRequest first = defenseRequestRepository.findById(firstId).orElseThrow();
        assertEquals(DefenseRequestStatus.REJECTED, first.getStatus());
        assertEquals("Термин не е соодветен.", first.getReason());
        assertNotNull(first.getDecidedAt());
        assertEquals(ThesisStatus.PENDING_DEFENSE_SCHEDULING, reload(thesisId).getStatus());
        assertEquals(1, notificationCount(a.student, NotificationType.DEFENSE_REQUEST_REJECTED, thesisId));

        // A brand new proposal — NOT a reuse of the rejected row.
        UUID secondId = requestDefense(a.student, thesisId, "Room 10", OffsetDateTime.now().plusDays(9));
        assertTrue(!secondId.equals(firstId));
        approveDefenseRequest(a.service, thesisId);
        assertEquals(ThesisStatus.DEFENSE_SCHEDULED, reload(thesisId).getStatus());

        // Full history still has both rows, oldest decision preserved.
        List<DefenseRequest> history = defenseRequestRepository.findByThesisOrderByCreatedAtDesc(reload(thesisId));
        assertEquals(2, history.size());
        assertTrue(history.stream().anyMatch(r -> r.getId().equals(firstId) && r.getStatus() == DefenseRequestStatus.REJECTED));
        assertTrue(history.stream().anyMatch(r -> r.getId().equals(secondId) && r.getStatus() == DefenseRequestStatus.APPROVED));
    }

    @Test
    @DisplayName("Double booking (real DB): two theses propose the same room/time; approving A succeeds, approving B is rejected as a conflict")
    void doubleBooking_secondApprovalRejected_realStack() throws Exception {
        Actors a = newActors();
        Actors b = newActors();
        UUID thesisA = advanceToReadyForProposal(a);
        UUID thesisB = advanceToReadyForProposal(b);

        // Use a run-unique room name so the global "how many non-cancelled defenses are in this
        // room" assertion below is isolated from any pre-existing committed Defense rows left in
        // the shared local dev database by earlier manual/live HTTP testing (which are outside
        // this @Transactional test's own rollback). The double-booking rule is still genuinely
        // exercised: both theses propose the SAME room/time, so the conflict must still be caught.
        String room = "Shared Hall " + UUID.randomUUID();
        OffsetDateTime when = OffsetDateTime.now().plusDays(10);
        requestDefense(a.student, thesisA, room, when);
        requestDefense(b.student, thesisB, room, when);

        // Both PENDING requests legitimately coexist — a PENDING request never reserves the room.
        assertEquals(DefenseRequestStatus.PENDING,
                defenseRequestRepository.findByThesisAndStatus(reload(thesisA), DefenseRequestStatus.PENDING).orElseThrow().getStatus());
        assertEquals(DefenseRequestStatus.PENDING,
                defenseRequestRepository.findByThesisAndStatus(reload(thesisB), DefenseRequestStatus.PENDING).orElseThrow().getStatus());

        // A is approved first — the room is now genuinely occupied.
        approveDefenseRequest(a.service, thesisA);
        assertEquals(ThesisStatus.DEFENSE_SCHEDULED, reload(thesisA).getStatus());
        assertTrue(defenseRepository.findByThesisAndIsCancelledFalse(reload(thesisA)).isPresent());

        // Approving B must now fail the fresh availability check and reject B's request —
        // it must NOT create a second Defense in the same room/time.
        approveDefenseRequest(b.service, thesisB);
        assertEquals(ThesisStatus.PENDING_DEFENSE_SCHEDULING, reload(thesisB).getStatus(), "B is never scheduled");
        assertTrue(defenseRepository.findByThesisAndIsCancelledFalse(reload(thesisB)).isEmpty());

        DefenseRequest requestB = defenseRequestRepository
                .findByThesisOrderByCreatedAtDesc(reload(thesisB)).get(0);
        assertEquals(DefenseRequestStatus.REJECTED, requestB.getStatus());
        assertNotNull(requestB.getReason());
        assertEquals(1, notificationCount(b.student, NotificationType.DEFENSE_REQUEST_REJECTED, thesisB));

        // Exactly one real Defense exists in the (run-unique) shared room across both theses.
        long defensesInRoom = defenseRepository.findByRoomAndIsCancelledFalse(room).size();
        assertEquals(1, defensesInRoom);
    }

    @Test
    @DisplayName("Cancel + rebook (real DB): cancelling frees the room; a new proposal for a different term is approved")
    void cancelThenRebook_realStack() throws Exception {
        Actors a = newActors();
        UUID thesisId = advanceToReadyForProposal(a);

        // Run-unique room name, isolating the global room-count assertion below from any
        // pre-existing committed Defense rows in the shared local dev database (see the
        // double-booking test for the rationale).
        String room = "Room 1 " + UUID.randomUUID();
        requestDefense(a.student, thesisId, room, OffsetDateTime.now().plusDays(7));
        approveDefenseRequest(a.service, thesisId);
        assertEquals(ThesisStatus.DEFENSE_SCHEDULED, reload(thesisId).getStatus());

        doPatch("/api/theses/" + thesisId + "/defenses/cancel", a.student, null)
                .andExpect(status().isOk());
        assertTrue(defenseRepository.findByThesisAndIsCancelledFalse(reload(thesisId)).isEmpty());
        // Status does not roll back (existing cancelDefense contract, unchanged).
        assertEquals(ThesisStatus.DEFENSE_SCHEDULED, reload(thesisId).getStatus());

        // A brand-new proposal is now accepted (DEFENSE_SCHEDULED + no active Defense).
        requestDefense(a.student, thesisId, room, OffsetDateTime.now().plusDays(8));
        approveDefenseRequest(a.service, thesisId);

        Defense secondDefense = defenseRepository.findByThesisAndIsCancelledFalse(reload(thesisId)).orElseThrow();
        assertTrue(!secondDefense.isCancelled());

        // The cancelled first defense does not block the room for the new booking — only the
        // NEW, non-cancelled one is counted.
        assertEquals(1, defenseRepository.findByRoomAndIsCancelledFalse(room).size());
    }

    @Test
    @DisplayName("A date outside the 5-15 day window is rejected over real HTTP (400, no request row)")
    void outsideWindow_rejected_realStack() throws Exception {
        Actors a = newActors();
        UUID thesisId = advanceToReadyForProposal(a);

        doPost("/api/theses/" + thesisId + "/defenses/request", a.student,
                Map.of("room", "X1", "scheduledAt", OffsetDateTime.now().plusDays(2)))
                .andExpect(status().isBadRequest());

        assertTrue(defenseRequestRepository.findByThesisOrderByCreatedAtDesc(reload(thesisId)).isEmpty());
    }

    @Test
    @DisplayName("GET defense requests is thesis-scoped: an unrelated student cannot read another thesis's proposal history (403)")
    void getRequests_isThesisScoped() throws Exception {
        Actors a = newActors();
        UUID thesisId = advanceToReadyForProposal(a);
        requestDefense(a.student, thesisId, "Room 1", OffsetDateTime.now().plusDays(7));

        var intruder = createStudent(240);
        doGet("/api/theses/" + thesisId + "/defenses/request", intruder)
                .andExpect(status().isForbidden());

        // Owner and STUDENT_SERVICE can both read it.
        doGet("/api/theses/" + thesisId + "/defenses/request", a.student)
                .andExpect(status().isOk());
        doGet("/api/theses/" + thesisId + "/defenses/request", a.service)
                .andExpect(status().isOk());
    }
}
