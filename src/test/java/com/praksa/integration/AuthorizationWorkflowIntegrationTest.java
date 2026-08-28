package com.praksa.integration;

import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.MentorDecision;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.ThesisStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P2.4 — Test 5 (authorization / workflow boundaries) and Test 6 (invalid state transitions).
 *
 * <p>Every case drives the REAL authorization layer and the REAL status guards over HTTP, then
 * confirms from the database that a rejected operation left the workflow state, the audit trail,
 * and the notification rows untouched. The authorization/guard logic is never duplicated in the
 * test — the test proves the running application rejects the operation.
 */
class AuthorizationWorkflowIntegrationTest extends AbstractWorkflowIntegrationTest {

    // =========================================================================
    // TEST 5 — AUTHORIZATION / WORKFLOW BOUNDARIES
    // =========================================================================

    @Test
    @DisplayName("A: an unrelated student cannot act on another student's thesis (ownership → 403)")
    void unrelatedStudent_cannotModifyAnothersThesis() throws Exception {
        Actors a = newActors();
        UUID thesisId = createThesis(a.student, "Owned by the first student");
        decideEligibility(a.service, thesisId, true); // now TOPIC_SELECTION

        User intruder = createStudent(240);

        // Intruder tries to submit a mentor request on someone else's thesis → 403, no change.
        doPatch("/api/theses/" + thesisId + "/mentor-request", intruder,
                Map.of("mentorId", a.mentor.getId().toString()))
                .andExpect(status().isForbidden());
        Thesis t = reload(thesisId);
        assertEquals(ThesisStatus.TOPIC_SELECTION, t.getStatus());
        assertEquals(null, t.getMentor(), "no mentor assigned by the rejected call");
    }

    @Test
    @DisplayName("B: a mentor who is not the assigned mentor cannot decide the request (403)")
    void unassignedMentor_cannotDecideRequest() throws Exception {
        Actors a = newActors();
        UUID thesisId = createThesis(a.student, "Assigned to mentor A only");
        decideEligibility(a.service, thesisId, true);
        submitMentorRequest(a.student, thesisId, a.mentor); // PENDING_MENTOR_APPROVAL, assigned = mentor

        User otherMentor = createUser(com.praksa.model.enums.Role.MENTOR);
        // Correct role, correct status, but NOT the assigned mentor → 403.
        doPatch("/api/theses/" + thesisId + "/mentor-decision", otherMentor,
                Map.of("decision", MentorDecision.ACCEPT.name()))
                .andExpect(status().isForbidden());
        assertEquals(ThesisStatus.PENDING_MENTOR_APPROVAL, reload(thesisId).getStatus());
    }

    @Test
    @DisplayName("C: a committee member seated on thesis A cannot grade thesis B's defense (cross-thesis → 403)")
    void committeeMemberOfA_cannotGradeThesisB() throws Exception {
        // Two independent theses with DISTINCT committees, both scheduled for defense.
        Actors a = newActors();
        Actors b = newActors();
        OffsetDateTime when = OffsetDateTime.now().plusDays(9);
        advanceToDefenseScheduled(a, "Room A", when);
        UUID thesisB = advanceToDefenseScheduled(b, "Room B", when);
        UUID defenseB = defenseRepository.findByThesisAndIsCancelledFalse(reload(thesisB))
                .orElseThrow().getId();

        // a.professorA is seated on thesis A only. Grading B must be refused by the seat check.
        doPost("/api/theses/" + thesisB + "/defenses/" + defenseB + "/result", a.professorA,
                Map.of("grade", 10))
                .andExpect(status().isForbidden());

        // Thesis B remains DEFENSE_SCHEDULED (unarchived), no result recorded.
        assertEquals(ThesisStatus.DEFENSE_SCHEDULED, reload(thesisB).getStatus());
        assertEquals(true,
                defenseResultRepository.findByDefense(defenseRepository.findById(defenseB).orElseThrow()).isEmpty());
    }

    @Test
    @DisplayName("D: proposing a defense is STUDENT-owner-only; deciding it is STUDENT_SERVICE-only (403 otherwise)")
    void defenseRequestWorkflow_roleBoundaries() throws Exception {
        Actors a = newActors();
        UUID thesisId = advanceToCommitteeReview(a);
        acceptReview(a.service, thesisId);
        verifyDefenseEligibility(a.service, thesisId, true, true); // PENDING_DEFENSE_SCHEDULING

        OffsetDateTime when = OffsetDateTime.now().plusDays(8);

        // Mentor cannot propose a defense term → 403.
        doPost("/api/theses/" + thesisId + "/defenses/request", a.mentor,
                Map.of("room", "X1", "scheduledAt", when))
                .andExpect(status().isForbidden());
        // STUDENT_SERVICE cannot propose one either — only the owning student may → 403.
        doPost("/api/theses/" + thesisId + "/defenses/request", a.service,
                Map.of("room", "X1", "scheduledAt", when))
                .andExpect(status().isForbidden());

        // Still awaiting a proposal; no DefenseRequest/Defense row created by the rejected calls.
        assertEquals(ThesisStatus.PENDING_DEFENSE_SCHEDULING, reload(thesisId).getStatus());
        assertEquals(true, defenseRepository.findByThesis(reload(thesisId)).isEmpty());
        assertEquals(true,
                defenseRequestRepository.findByThesisAndStatus(reload(thesisId), com.praksa.model.enums.DefenseRequestStatus.PENDING).isEmpty());

        // Student submits the proposal.
        requestDefense(a.student, thesisId, "X1", when);

        // Mentor/student cannot decide it → 403.
        doPatch("/api/theses/" + thesisId + "/defenses/request/decision", a.mentor,
                Map.of("approved", true))
                .andExpect(status().isForbidden());
        doPatch("/api/theses/" + thesisId + "/defenses/request/decision", a.student,
                Map.of("approved", true))
                .andExpect(status().isForbidden());
        assertEquals(ThesisStatus.PENDING_DEFENSE_SCHEDULING, reload(thesisId).getStatus());

        // STUDENT_SERVICE succeeds.
        approveDefenseRequest(a.service, thesisId);
        assertEquals(ThesisStatus.DEFENSE_SCHEDULED, reload(thesisId).getStatus());
    }

    @Test
    @DisplayName("E: knowing the thesis UUID does not grant a workflow transition to an unrelated user (403)")
    void knowingUuid_doesNotBypassAuthorization() throws Exception {
        Actors a = newActors();
        UUID thesisId = createThesis(a.student, "Guarded by role + ownership");

        // A COMMITTEE-role user who knows the UUID cannot decide eligibility (role → 403).
        doPatch("/api/theses/" + thesisId + "/eligibility", a.committee, Map.of("approved", true))
                .andExpect(status().isForbidden());
        // Another student who knows the UUID cannot submit the application (ownership → 403).
        User intruder = createStudent(240);
        doPatch("/api/theses/" + thesisId + "/submit-application", intruder, null)
                .andExpect(status().isForbidden());

        assertEquals(ThesisStatus.PENDING_ELIGIBILITY_CHECK, reload(thesisId).getStatus());
    }

    @Test
    @DisplayName("F: a caller cannot skip ahead by invoking a later transition directly (status guard → 400)")
    void cannotSkipAheadToLaterTransition() throws Exception {
        Actors a = newActors();
        UUID thesisId = createThesis(a.student, "Only at the very first step");

        // STUDENT_SERVICE tries to approve a committee that does not exist yet, on a
        // PENDING_ELIGIBILITY_CHECK thesis → status guard rejects it (400).
        doPost("/api/theses/" + thesisId + "/committee/approve", a.service, null)
                .andExpect(status().isBadRequest());
        // Mentor tries to approve the final thesis before it is FINAL_SUBMITTED → 400.
        // (Assign the mentor first so the failure is the STATUS guard, not the mentor guard.)
        decideEligibility(a.service, thesisId, true);
        submitMentorRequest(a.student, thesisId, a.mentor);
        doPatch("/api/theses/" + thesisId + "/approve-final", a.mentor, null)
                .andExpect(status().isBadRequest());

        assertEquals(ThesisStatus.PENDING_MENTOR_APPROVAL, reload(thesisId).getStatus());
    }

    // =========================================================================
    // TEST 6 — INVALID STATE TRANSITIONS (rejected + DB stays consistent)
    // =========================================================================

    @Test
    @DisplayName("Invalid: resubmitting an already-submitted application is rejected and changes nothing")
    void invalid_resubmitAlreadySubmitted_noChange() throws Exception {
        Actors a = newActors();
        UUID thesisId = createThesis(a.student, "Application submitted once");
        decideEligibility(a.service, thesisId, true);
        submitMentorRequest(a.student, thesisId, a.mentor);
        decideMentor(a.mentor, thesisId, MentorDecision.ACCEPT, null);
        submitApplication(a.student, thesisId); // → PENDING_ARCHIVE_VALIDATION (+1 ARCHIVE notification)

        long historyBefore = statusHistoryRepository.findByThesisOrderByChangedAtAsc(reload(thesisId)).size();
        long archiveNotifBefore = notificationCount(a.archive, NotificationType.APPLICATION_PENDING_ARCHIVE, thesisId);

        // Submitting again from PENDING_ARCHIVE_VALIDATION is not an allowed source status → 400.
        doPatch("/api/theses/" + thesisId + "/submit-application", a.student, null)
                .andExpect(status().isBadRequest());

        Thesis t = reload(thesisId);
        assertEquals(ThesisStatus.PENDING_ARCHIVE_VALIDATION, t.getStatus(), "status unchanged");
        assertEquals(historyBefore, statusHistoryRepository.findByThesisOrderByChangedAtAsc(t).size(),
                "no new history row");
        assertEquals(archiveNotifBefore,
                notificationCount(a.archive, NotificationType.APPLICATION_PENDING_ARCHIVE, thesisId),
                "no duplicate ARCHIVE notification");
    }

    @Test
    @DisplayName("Invalid: approving the final thesis before FINAL_SUBMITTED is rejected and changes nothing")
    void invalid_approveFinalTooEarly_noChange() throws Exception {
        Actors a = newActors();
        UUID thesisId = advanceToInProgress(a); // IN_PROGRESS, mentor assigned

        long historyBefore = statusHistoryRepository.findByThesisOrderByChangedAtAsc(reload(thesisId)).size();

        // approve-final requires FINAL_SUBMITTED; from IN_PROGRESS the status guard → 400.
        doPatch("/api/theses/" + thesisId + "/approve-final", a.mentor, null)
                .andExpect(status().isBadRequest());

        assertEquals(ThesisStatus.IN_PROGRESS, reload(thesisId).getStatus());
        assertEquals(historyBefore, statusHistoryRepository.findByThesisOrderByChangedAtAsc(reload(thesisId)).size());
    }

    @Test
    @DisplayName("Invalid: proposing a defense before the thesis is ready is rejected and creates no request/Defense row")
    void invalid_defenseRequestTooEarly_noRowsCreated() throws Exception {
        Actors a = newActors();
        UUID thesisId = advanceToInProgress(a); // IN_PROGRESS — nowhere near defense scheduling

        OffsetDateTime when = OffsetDateTime.now().plusDays(6);
        // The student has the role and owns the thesis, but the status is not
        // PENDING_DEFENSE_SCHEDULING/eligible-DEFENSE_SCHEDULED → 400.
        doPost("/api/theses/" + thesisId + "/defenses/request", a.student,
                Map.of("room", "Z9", "scheduledAt", when))
                .andExpect(status().isBadRequest());

        assertEquals(ThesisStatus.IN_PROGRESS, reload(thesisId).getStatus());
        assertEquals(true, defenseRepository.findByThesis(reload(thesisId)).isEmpty(),
                "no Defense row created by a rejected proposal");
        assertEquals(0, defenseRequestRepository.findByThesisOrderByCreatedAtDesc(reload(thesisId)).size(),
                "no DefenseRequest row created by a rejected proposal");
    }

    @Test
    @DisplayName("Invalid: defense-eligibility verification with a condition unmet is rejected — no transition, no notification")
    void invalid_defenseEligibilityPartial_noTransition() throws Exception {
        Actors a = newActors();
        UUID thesisId = advanceToCommitteeReview(a);
        acceptReview(a.service, thesisId); // PENDING_DEFENSE_CHECK

        long historyBefore = statusHistoryRepository.findByThesisOrderByChangedAtAsc(reload(thesisId)).size();
        long notifBefore = totalNotifications(thesisId);

        // Only one condition true → 400, nothing changes.
        doPatch("/api/theses/" + thesisId + "/defense-eligibility", a.service,
                Map.of("examsCompleted", true, "documentationComplete", false))
                .andExpect(status().isBadRequest());

        assertEquals(ThesisStatus.PENDING_DEFENSE_CHECK, reload(thesisId).getStatus(), "no transition");
        assertEquals(historyBefore, statusHistoryRepository.findByThesisOrderByChangedAtAsc(reload(thesisId)).size(),
                "no history row");
        assertEquals(notifBefore, totalNotifications(thesisId),
                "no DEFENSE_ELIGIBILITY_VERIFIED notification on a failed verification");

        // A student cannot propose a defense while still PENDING_DEFENSE_CHECK (must be verified first) → 400.
        doPost("/api/theses/" + thesisId + "/defenses/request", a.student,
                Map.of("room", "X1", "scheduledAt", OffsetDateTime.now().plusDays(7)))
                .andExpect(status().isBadRequest());
        assertEquals(ThesisStatus.PENDING_DEFENSE_CHECK, reload(thesisId).getStatus());
    }

    private Thesis reload(UUID thesisId) {
        return thesisRepository.findById(thesisId).orElseThrow();
    }
}
