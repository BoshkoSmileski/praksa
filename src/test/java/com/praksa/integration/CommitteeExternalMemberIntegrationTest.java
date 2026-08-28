package com.praksa.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.praksa.model.CommitteeMember;
import com.praksa.model.Defense;
import com.praksa.model.DefenseResult;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Official faculty procedure — a committee may have 3 OR 4 members, and the 4th (optional) seat
 * is an external, non-voting professional from practice. Real HTTP + real Postgres, mirroring
 * the style of {@link CommitteeWorkflowIntegrationTest} and {@link DefenseRequestIntegrationTest}.
 *
 * <p>Covers the task's required end-to-end scenarios: a 4-member committee can be proposed and
 * approved, the external member is returned/visible as a committee member, a normal voting
 * member CAN grade, and the external member CANNOT — the backend rejects the attempt at the real
 * HTTP boundary with zero mutations.
 */
class CommitteeExternalMemberIntegrationTest extends AbstractWorkflowIntegrationTest {

    @Test
    @DisplayName("A 4-member committee (3 voting + 1 external) can be proposed and approved; "
            + "the external member is visible in the committee list with externalNonVoting=true")
    void fourMemberCommittee_proposedAndApproved_externalVisible() throws Exception {
        Actors a = newActors();
        User external = createUser(Role.MENTOR);
        UUID thesisId = advanceToMentorApproved(a);

        JsonNode proposed = proposeCommitteeWithExternal(a.mentor, thesisId, a.professorA, a.professorB, external);
        assertEquals(4, proposed.size());

        approveCommittee(a.service, thesisId);
        assertEquals(ThesisStatus.COMMITTEE_REVIEW, reload(thesisId).getStatus());

        List<CommitteeMember> members = committeeRepository.findByThesis(reload(thesisId));
        assertEquals(4, members.size());
        long externalCount = members.stream().filter(CommitteeMember::isExternalNonVoting).count();
        assertEquals(1, externalCount, "exactly one persisted seat must be external non-voting");
        CommitteeMember externalSeat = members.stream()
                .filter(m -> m.getProfessor().getId().equals(external.getId())).findFirst().orElseThrow();
        assertTrue(externalSeat.isExternalNonVoting());
        // Every other seat (mentor + the 2 formal members) must remain voting.
        assertTrue(members.stream()
                .filter(m -> !m.getProfessor().getId().equals(external.getId()))
                .allMatch(m -> !m.isExternalNonVoting()));

        // R — the external member is genuinely visible via the read endpoint (GET committee),
        // with the correct wire key, to a party with legitimate read access (the mentor).
        doGet("/api/theses/" + thesisId + "/committee", a.mentor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[?(@.professorId=='" + external.getId() + "')].externalNonVoting")
                        .value(true));
    }

    @Test
    @DisplayName("Proposing 3 professors with 0 marked external is rejected (400) — no committee created")
    void fourProfessors_noExternalDesignated_rejected() throws Exception {
        Actors a = newActors();
        User third = createUser(Role.MENTOR);
        UUID thesisId = advanceToMentorApproved(a);

        doPost("/api/theses/" + thesisId + "/committee/propose", a.mentor,
                java.util.Map.of("professorIds", List.of(
                        a.professorA.getId().toString(), a.professorB.getId().toString(), third.getId().toString())))
                .andExpect(status().isBadRequest());

        assertTrue(committeeRepository.findByThesis(reload(thesisId)).isEmpty(),
                "a rejected proposal must create no committee rows at all");
    }

    @Test
    @DisplayName("Approving a 4-member committee with 2 external members is rejected (400) — "
            + "re-validated against the actual persisted composition, not just the propose-time request")
    void approve_fourMembersTwoExternal_rejected() throws Exception {
        Actors a = newActors();
        User external1 = createUser(Role.MENTOR);
        User external2 = createUser(Role.MENTOR);
        UUID thesisId = advanceToMentorApproved(a);

        // proposeCommittee only ever allows 0 or 1 external member per request, so a 2-external
        // composition can only arise from corrupted/legacy data — simulate that directly against
        // the persisted rows (bypassing proposeCommittee's own guard) to prove approveCommittee
        // re-derives the rule from the DB rather than trusting proposeCommittee was the only path
        // that ever created these rows.
        proposeCommitteeWithExternal(a.mentor, thesisId, a.professorA, external1, external2);
        Thesis thesis = reload(thesisId);
        CommitteeMember corrupted = committeeRepository.findByThesis(thesis).stream()
                .filter(m -> m.getProfessor().getId().equals(external1.getId())).findFirst().orElseThrow();
        assertFalse(corrupted.isExternalNonVoting(), "external1 was proposed as a normal voting member");
        corrupted.setExternalNonVoting(true); // now BOTH external1 and external2 are external
        committeeRepository.saveAndFlush(corrupted);

        doPost("/api/theses/" + thesisId + "/committee/approve", a.service, null)
                .andExpect(status().isBadRequest());
        assertEquals(ThesisStatus.MENTOR_APPROVED, reload(thesisId).getStatus(),
                "a rejected approval must not advance the thesis");
    }

    @Test
    @DisplayName("Full workflow: a defense can still be scheduled with a 4-member committee")
    void fourMemberCommittee_defenseCanBeScheduled() throws Exception {
        Actors a = newActors();
        User external = createUser(Role.MENTOR);
        UUID thesisId = advanceToCommitteeReviewWithExternal(a, external);

        acceptReview(a.service, thesisId);
        assertEquals(ThesisStatus.PENDING_DEFENSE_CHECK, reload(thesisId).getStatus());
        verifyDefenseEligibility(a.service, thesisId, true, true);
        assertEquals(ThesisStatus.PENDING_DEFENSE_SCHEDULING, reload(thesisId).getStatus());

        String room = "External-Member-Room-" + UUID.randomUUID().toString().substring(0, 8);
        requestDefense(a.student, thesisId, room, OffsetDateTime.now().plusDays(10));
        approveDefenseRequest(a.service, thesisId);

        assertEquals(ThesisStatus.DEFENSE_SCHEDULED, reload(thesisId).getStatus());
        Defense defense = defenseRepository.findByThesisAndIsCancelledFalse(reload(thesisId)).orElseThrow();
        assertEquals(room, defense.getRoom());
    }

    @Test
    @DisplayName("A normal VOTING committee member CAN record the grade; the external member CANNOT — "
            + "the backend rejects the attempt at the real HTTP boundary with zero mutations")
    void votingMemberGrades_externalMemberRejected() throws Exception {
        Actors a = newActors();
        User external = createUser(Role.MENTOR);
        UUID thesisId = advanceToCommitteeReviewWithExternal(a, external);

        acceptReview(a.service, thesisId);
        verifyDefenseEligibility(a.service, thesisId, true, true);
        String room = "Grading-Room-" + UUID.randomUUID().toString().substring(0, 8);
        requestDefense(a.student, thesisId, room, OffsetDateTime.now().plusDays(10));
        approveDefenseRequest(a.service, thesisId);

        Thesis thesis = reload(thesisId);
        Defense defense = defenseRepository.findByThesisAndIsCancelledFalse(thesis).orElseThrow();

        // The external member genuinely holds a seat (confirmed via the committee list) yet is
        // rejected when attempting to grade — proving the restriction is about voting rights on
        // the SEAT, not about being an unrelated/unseated user.
        boolean externalSeated = committeeRepository.findByThesis(thesis).stream()
                .anyMatch(m -> m.getProfessor().getId().equals(external.getId()));
        assertTrue(externalSeated, "the external professional must be a genuine, seated committee member");

        doPost("/api/theses/" + thesisId + "/defenses/" + defense.getId() + "/result", external,
                java.util.Map.of("grade", 9))
                .andExpect(status().isForbidden());

        // Zero mutations: no DefenseResult, status unchanged, no archive metadata.
        assertTrue(defenseResultRepository.findByDefense(defense).isEmpty(),
                "the external member's rejected attempt must create no DefenseResult");
        assertEquals(ThesisStatus.DEFENSE_SCHEDULED, reload(thesisId).getStatus());
        assertNull(reload(thesisId).getArchiveRegistrationNumber());

        // A genuine voting member (professorA, seated and NOT external) can grade successfully —
        // proving the restriction is specific to the external seat, not a general breakage.
        UUID resultId = recordResult(a.professorA, thesisId, defense.getId(), 9);
        DefenseResult result = defenseResultRepository.findById(resultId).orElseThrow();
        assertEquals(9, result.getGrade());
        assertEquals(ThesisStatus.ARCHIVED, reload(thesisId).getStatus());
    }

    private Thesis reload(UUID thesisId) {
        return thesisRepository.findById(thesisId).orElseThrow();
    }
}
