package com.praksa.integration;

import com.praksa.model.CommitteeMember;
import com.praksa.model.Defense;
import com.praksa.model.DefenseResult;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.DefenseRequestStatus;
import com.praksa.model.enums.MemberRole;
import com.praksa.model.enums.MentorDecision;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.ThesisStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P2.4 — Test 1 (happy path), Test 2 (revision loop), Test 3 (rejection loop).
 *
 * Every step is a REAL HTTP call through the full stack (controller → service → repository →
 * PostgreSQL) as the correct authenticated user; persisted state is verified straight from the
 * repositories after each meaningful transition. Nothing is mocked except the SMTP boundary
 * (mail disabled), and notification ROWS are still asserted from the database.
 */
class ThesisWorkflowIntegrationTest extends AbstractWorkflowIntegrationTest {

    // =========================================================================
    // TEST 1 — HAPPY PATH: full lifecycle, create → … → ARCHIVED
    // =========================================================================

    @Test
    @DisplayName("Happy path: a thesis walks the whole lifecycle to ARCHIVED with correct persisted state at each stage")
    void happyPath_fullLifecycle() throws Exception {
        Actors a = newActors();

        // 1–2. Create thesis
        UUID thesisId = createThesis(a.student, "Distributed consensus in academic systems");
        Thesis thesis = thesisRepository.findById(thesisId).orElseThrow();
        assertEquals(ThesisStatus.PENDING_ELIGIBILITY_CHECK, thesis.getStatus());
        assertEquals(a.student.getId(), thesis.getStudent().getId());
        assertNotNull(thesis.getSubmissionDeadline(), "creation must anchor the 1-month deadline");
        // first history row: null → PENDING_ELIGIBILITY_CHECK
        assertEquals(1, statusHistoryRepository.findByThesisOrderByChangedAtAsc(thesis).size());

        // 3–4. Eligibility approved → TOPIC_SELECTION
        decideEligibility(a.service, thesisId, true);
        assertEquals(ThesisStatus.TOPIC_SELECTION, reload(thesisId).getStatus());
        assertEquals(1, notificationCount(a.student, NotificationType.ELIGIBILITY_APPROVED, thesisId));

        // 5. Mentor request → PENDING_MENTOR_APPROVAL, mentor assigned, mentor notified
        submitMentorRequest(a.student, thesisId, a.mentor);
        thesis = reload(thesisId);
        assertEquals(ThesisStatus.PENDING_MENTOR_APPROVAL, thesis.getStatus());
        assertEquals(a.mentor.getId(), thesis.getMentor().getId());
        assertEquals(1, notificationCount(a.mentor, NotificationType.MENTOR_REQUEST_RECEIVED, thesisId));

        // 6. Mentor ACCEPTs → APPLICATION_SUBMITTED (misleadingly named: not yet submitted)
        decideMentor(a.mentor, thesisId, MentorDecision.ACCEPT, null);
        assertEquals(ThesisStatus.APPLICATION_SUBMITTED, reload(thesisId).getStatus());
        assertEquals(1, notificationCount(a.student, NotificationType.MENTOR_ACCEPTED_TOPIC, thesisId));

        // 7. Submit application → PENDING_ARCHIVE_VALIDATION, PDF generated, ARCHIVE notified
        submitApplication(a.student, thesisId);
        thesis = reload(thesisId);
        assertEquals(ThesisStatus.PENDING_ARCHIVE_VALIDATION, thesis.getStatus());
        assertNotNull(thesis.getApplicationPdfPath(), "submitApplication must generate the application PDF");
        assertEquals(1, notificationCount(a.archive, NotificationType.APPLICATION_PENDING_ARCHIVE, thesisId));

        // 8. Archive approves → PENDING_SERVICE_VALIDATION
        archiveValidate(a.archive, thesisId, true, null);
        assertEquals(ThesisStatus.PENDING_SERVICE_VALIDATION, reload(thesisId).getStatus());
        assertEquals(1, notificationCount(a.service, NotificationType.APPLICATION_PENDING_SERVICE, thesisId));

        // 9. Service approves → IN_PROGRESS
        serviceValidate(a.service, thesisId, true, null);
        assertEquals(ThesisStatus.IN_PROGRESS, reload(thesisId).getStatus());
        assertEquals(1, notificationCount(a.student, NotificationType.APPLICATION_VALIDATED, thesisId));

        // 10. Upload a version + mark final → FINAL_SUBMITTED
        UUID versionId = uploadVersion(a.student, thesisId);
        markFinal(a.student, thesisId, versionId);
        assertEquals(ThesisStatus.FINAL_SUBMITTED, reload(thesisId).getStatus());
        assertEquals(1, notificationCount(a.mentor, NotificationType.FINAL_VERSION_SUBMITTED, thesisId));

        // 11. Mentor approves final → MENTOR_APPROVED
        approveFinal(a.mentor, thesisId);
        assertEquals(ThesisStatus.MENTOR_APPROVED, reload(thesisId).getStatus());
        assertEquals(1, notificationCount(a.student, NotificationType.MENTOR_APPROVED_THESIS, thesisId));

        // 12. Propose committee — mentor auto-seated as MENTOR_MEMBER, 2 FORMAL_MEMBERs
        proposeCommittee(a.mentor, thesisId, a.professorA, a.professorB);
        List<CommitteeMember> members = committeeRepository.findByThesis(reload(thesisId));
        assertEquals(3, members.size(), "committee must have exactly 3 seats");
        long mentorSeats = members.stream().filter(m -> m.getMemberRole() == MemberRole.MENTOR_MEMBER).count();
        long formalSeats = members.stream().filter(m -> m.getMemberRole() == MemberRole.FORMAL_MEMBER).count();
        assertEquals(1, mentorSeats, "exactly one MENTOR_MEMBER seat");
        assertEquals(2, formalSeats, "exactly two FORMAL_MEMBER seats");
        assertTrue(members.stream().anyMatch(m -> m.getMemberRole() == MemberRole.MENTOR_MEMBER
                && m.getProfessor().getId().equals(a.mentor.getId())), "mentor holds the MENTOR_MEMBER seat");

        // 13. Approve committee → COMMITTEE_REVIEW, seats stamped, review clock started
        approveCommittee(a.service, thesisId);
        thesis = reload(thesisId);
        assertEquals(ThesisStatus.COMMITTEE_REVIEW, thesis.getStatus());
        assertNotNull(thesis.getCommitteeReviewStartedAt(), "committee approval starts the 5-business-day clock");
        assertTrue(committeeRepository.findByThesis(thesis).stream().allMatch(m -> m.getApprovedBy() != null),
                "all seats stamped approvedBy");
        assertEquals(1, notificationCount(a.student, NotificationType.COMMITTEE_FORMED, thesisId));
        assertEquals(1, notificationCount(a.mentor, NotificationType.COMMITTEE_FORMED, thesisId),
                "mentor notified once via the MENTOR_MEMBER seat, not twice");

        // 13b. A seated professor submits review notes (persisted on the seat)
        UUID formalMemberId = members.stream()
                .filter(m -> m.getProfessor().getId().equals(a.professorA.getId()))
                .findFirst().orElseThrow().getId();
        submitReview(a.professorA, thesisId, formalMemberId, "Looks solid.");
        assertEquals("Looks solid.",
                committeeRepository.findById(formalMemberId).orElseThrow().getNotes());

        // 14. Accept committee review → COMMITTEE_ACCEPTED → PENDING_DEFENSE_CHECK
        acceptReview(a.service, thesisId);
        assertEquals(ThesisStatus.PENDING_DEFENSE_CHECK, reload(thesisId).getStatus());
        assertEquals(1, notificationCount(a.student, NotificationType.COMMITTEE_REVIEW_ACCEPTED, thesisId));

        // 15. Service verifies defense eligibility → PENDING_DEFENSE_SCHEDULING
        verifyDefenseEligibility(a.service, thesisId, true, true);
        assertEquals(ThesisStatus.PENDING_DEFENSE_SCHEDULING, reload(thesisId).getStatus());
        assertEquals(1, notificationCount(a.student, NotificationType.DEFENSE_ELIGIBILITY_VERIFIED, thesisId));

        // 16. Student proposes the actual room/date/time → DefenseRequest PENDING, no
        //     Defense row yet, no status change (the request itself never schedules).
        OffsetDateTime when = OffsetDateTime.now().plusDays(10);
        UUID requestId = requestDefense(a.student, thesisId, "Amphitheater A", when);
        assertEquals(ThesisStatus.PENDING_DEFENSE_SCHEDULING, reload(thesisId).getStatus());
        assertTrue(defenseRepository.findByThesis(reload(thesisId)).isEmpty(),
                "a PENDING defense request must NOT create a Defense row");
        assertEquals(1, notificationCount(a.service, NotificationType.DEFENSE_REQUESTED, thesisId));

        // 17. Service approves the proposal → DEFENSE_SCHEDULED, Defense row persisted with
        //     the exact room/time copied from the request.
        approveDefenseRequest(a.service, thesisId);
        assertEquals(ThesisStatus.DEFENSE_SCHEDULED, reload(thesisId).getStatus());
        Defense defense = defenseRepository.findByThesisAndIsCancelledFalse(reload(thesisId)).orElseThrow();
        UUID defenseId = defense.getId();
        assertEquals("Amphitheater A", defense.getRoom());
        assertTrue(when.isEqual(defense.getScheduledAt()), "same instant (Postgres round-trips timestamptz as UTC)");
        assertEquals(thesisId, defense.getThesis().getId());
        assertTrue(!defense.isCancelled());
        assertEquals(1, notificationCount(a.student, NotificationType.DEFENSE_SCHEDULED, thesisId));
        assertEquals(DefenseRequestStatus.APPROVED,
                defenseRequestRepository.findById(requestId).orElseThrow().getStatus());

        // 18. A seated committee member records the grade → ARCHIVED
        UUID resultId = recordResult(a.professorA, thesisId, defenseId, 9);
        thesis = reload(thesisId);
        assertEquals(ThesisStatus.ARCHIVED, thesis.getStatus());
        assertNotNull(thesis.getArchiveRegistrationNumber(), "archiving assigns a registration number");
        assertTrue(thesis.getArchiveRegistrationNumber().startsWith("DT-"),
                "registration number uses the DT-YYYY-NNNN format");
        assertNotNull(thesis.getArchiveDate());
        assertEquals(a.professorA.getId(), thesis.getArchivedBy().getId());
        DefenseResult result = defenseResultRepository.findById(resultId).orElseThrow();
        assertEquals(9, result.getGrade());
        assertEquals(1, notificationCount(a.student, NotificationType.THESIS_GRADED, thesisId));
        assertEquals(1, notificationCount(a.student, NotificationType.THESIS_ARCHIVED, thesisId));
    }

    // =========================================================================
    // TEST 2 — REVISION LOOP: mentor REQUEST_CHANGES → student revises → back to review
    // =========================================================================

    @Test
    @DisplayName("Revision loop: REQUEST_CHANGES → revise → resubmit returns to PENDING_MENTOR_APPROVAL and can be accepted")
    void revisionLoop_requestChangesThenReviseThenAccept() throws Exception {
        Actors a = newActors();

        UUID thesisId = createThesis(a.student, "Original working title");
        decideEligibility(a.service, thesisId, true);
        submitMentorRequest(a.student, thesisId, a.mentor);

        // Mentor requests changes → MENTOR_REQUESTED_CHANGES, revisionCount incremented, mentor kept
        decideMentor(a.mentor, thesisId, MentorDecision.REQUEST_CHANGES, "Please narrow the scope.");
        Thesis thesis = reload(thesisId);
        assertEquals(ThesisStatus.MENTOR_REQUESTED_CHANGES, thesis.getStatus());
        assertEquals(1, thesis.getRevisionCount(), "REQUEST_CHANGES increments revisionCount");
        assertNotNull(thesis.getMentor(), "mentor stays assigned through a change request");
        assertEquals(a.mentor.getId(), thesis.getMentor().getId());
        assertEquals(1, notificationCount(a.student, NotificationType.MENTOR_REQUESTED_CHANGES, thesisId));

        // Student revises → PENDING_MENTOR_APPROVAL, same owner + mentor, title updated,
        // revisionCount NOT changed by the student action
        reviseProposal(a.student, thesisId, "Revised, narrower title");
        thesis = reload(thesisId);
        assertEquals(ThesisStatus.PENDING_MENTOR_APPROVAL, thesis.getStatus());
        assertEquals("Revised, narrower title", thesis.getTitle());
        assertEquals(a.student.getId(), thesis.getStudent().getId());
        assertEquals(a.mentor.getId(), thesis.getMentor().getId());
        assertEquals(1, thesis.getRevisionCount(), "student resubmission does not change revisionCount");
        assertEquals(1, notificationCount(a.mentor, NotificationType.STUDENT_RESUBMITTED_PROPOSAL, thesisId));

        // Workflow continues normally: mentor now accepts → APPLICATION_SUBMITTED
        decideMentor(a.mentor, thesisId, MentorDecision.ACCEPT, null);
        assertEquals(ThesisStatus.APPLICATION_SUBMITTED, reload(thesisId).getStatus());

        // Authorization within the loop: an unrelated student cannot revise this thesis
        // (and it is no longer in MENTOR_REQUESTED_CHANGES anyway) — the ownership guard is 403.
        User intruder = createStudent(240);
        doPatch("/api/theses/" + thesisId + "/revise-proposal", intruder,
                java.util.Map.of("title", "hijacked title"))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // TEST 3 — REJECTION LOOP: application rejected by archive, resubmit restarts at archive
    // =========================================================================

    @Test
    @DisplayName("Rejection loop: archive rejects the application (reason stored), student resubmits, restarts at PENDING_ARCHIVE_VALIDATION")
    void rejectionLoop_archiveRejectsThenStudentResubmits() throws Exception {
        Actors a = newActors();

        UUID thesisId = createThesis(a.student, "Thesis heading for rejection");
        decideEligibility(a.service, thesisId, true);
        submitMentorRequest(a.student, thesisId, a.mentor);
        decideMentor(a.mentor, thesisId, MentorDecision.ACCEPT, null);
        submitApplication(a.student, thesisId);
        assertEquals(ThesisStatus.PENDING_ARCHIVE_VALIDATION, reload(thesisId).getStatus());

        // A rejection comment is MANDATORY: rejecting without one is a 400 and changes nothing.
        long historyBefore = statusHistoryRepository.findByThesisOrderByChangedAtAsc(reload(thesisId)).size();
        doPatch("/api/theses/" + thesisId + "/archive-validate", a.archive,
                java.util.Map.of("approved", false))
                .andExpect(status().isBadRequest());
        assertEquals(ThesisStatus.PENDING_ARCHIVE_VALIDATION, reload(thesisId).getStatus(),
                "a rejected-without-comment call must not transition the thesis");
        assertEquals(historyBefore,
                statusHistoryRepository.findByThesisOrderByChangedAtAsc(reload(thesisId)).size(),
                "no history row on a failed rejection");

        // An unrelated / non-ARCHIVE user cannot reject the application (role boundary → 403).
        doPatch("/api/theses/" + thesisId + "/archive-validate", a.service,
                java.util.Map.of("approved", false, "comment", "not my call"))
                .andExpect(status().isForbidden());
        assertEquals(ThesisStatus.PENDING_ARCHIVE_VALIDATION, reload(thesisId).getStatus());

        // Archive REJECTS with a mandatory reason → APPLICATION_REJECTED_BY_ARCHIVE, reason stored
        archiveValidate(a.archive, thesisId, false, "Missing signed cover page.");
        Thesis thesis = reload(thesisId);
        assertEquals(ThesisStatus.APPLICATION_REJECTED_BY_ARCHIVE, thesis.getStatus());
        assertEquals("Missing signed cover page.", thesis.getArchiveComment());
        assertEquals(1, notificationCount(a.student, NotificationType.APPLICATION_REJECTED_BY_ARCHIVE, thesisId));

        // Student RESUBMITS → always restarts at PENDING_ARCHIVE_VALIDATION (archive sees it first again)
        submitApplication(a.student, thesisId);
        assertEquals(ThesisStatus.PENDING_ARCHIVE_VALIDATION, reload(thesisId).getStatus());

        // Continue the loop through the SERVICE rejection branch to prove the same restart invariant.
        archiveValidate(a.archive, thesisId, true, null);
        assertEquals(ThesisStatus.PENDING_SERVICE_VALIDATION, reload(thesisId).getStatus());
        serviceValidate(a.service, thesisId, false, "Enrollment record incomplete.");
        thesis = reload(thesisId);
        assertEquals(ThesisStatus.APPLICATION_REJECTED_BY_SERVICE, thesis.getStatus());
        assertEquals("Enrollment record incomplete.", thesis.getServiceComment());
        assertEquals(1, notificationCount(a.student, NotificationType.APPLICATION_REJECTED_BY_SERVICE, thesisId));

        // Resubmission after a SERVICE rejection also returns to ARCHIVE first (never skips archive).
        submitApplication(a.student, thesisId);
        assertEquals(ThesisStatus.PENDING_ARCHIVE_VALIDATION, reload(thesisId).getStatus());

        // The loop can complete normally the next time around.
        archiveValidate(a.archive, thesisId, true, null);
        serviceValidate(a.service, thesisId, true, null);
        assertEquals(ThesisStatus.IN_PROGRESS, reload(thesisId).getStatus());
    }

    // =========================================================================
    // TEST 4 — GRADE 5 = DEFENSE_FAILED: real end-to-end, not archived, student can reapply
    // =========================================================================

    @Test
    @DisplayName("Grade 5 over the real stack: thesis moves to DEFENSE_FAILED (not ARCHIVED), " +
                 "receives no archive metadata, notifies the student once, and the student can " +
                 "immediately reapply — while a genuinely active thesis still blocks reapplication")
    void grade5_realStack_defenseFailedAndStudentCanReapply() throws Exception {
        Actors a = newActors();
        OffsetDateTime when = OffsetDateTime.now().plusDays(10);
        UUID thesisId = advanceToDefenseScheduled(a, "Room 101", when);

        Defense defense = defenseRepository.findByThesisAndIsCancelledFalse(reload(thesisId)).orElseThrow();
        UUID defenseId = defense.getId();

        // A seated committee member grades 5 — the official "not defended" outcome.
        UUID resultId = recordResult(a.professorA, thesisId, defenseId, 5);

        // Status is DEFENSE_FAILED, never ARCHIVED.
        Thesis thesis = reload(thesisId);
        assertEquals(ThesisStatus.DEFENSE_FAILED, thesis.getStatus());

        // NO archive metadata was assigned for a failed defense.
        assertNull(thesis.getArchiveRegistrationNumber());
        assertNull(thesis.getArchiveDate());

        // The DefenseResult (grade 5) and the Defense row are both preserved untouched.
        DefenseResult result = defenseResultRepository.findById(resultId).orElseThrow();
        assertEquals(5, result.getGrade());
        assertTrue(defenseRepository.findById(defenseId).isPresent());

        // Exactly one notification — DEFENSE_FAILED_CAN_REAPPLY — and NOT the archive-success pair.
        assertEquals(1, notificationCount(a.student, NotificationType.DEFENSE_FAILED_CAN_REAPPLY, thesisId));
        assertEquals(0, notificationCount(a.student, NotificationType.THESIS_GRADED, thesisId));
        assertEquals(0, notificationCount(a.student, NotificationType.THESIS_ARCHIVED, thesisId));

        // Item D — reapplication: DEFENSE_FAILED does not count as an active thesis, so the
        // SAME student can immediately open a brand-new application.
        UUID newThesisId = createThesis(a.student, "A reworked thesis after the failed defense");
        assertEquals(ThesisStatus.PENDING_ELIGIBILITY_CHECK, reload(newThesisId).getStatus());
        // The old DEFENSE_FAILED row is left completely untouched as historical/audit data.
        assertEquals(ThesisStatus.DEFENSE_FAILED, reload(thesisId).getStatus());

        // Item E — the active-thesis rule is NOT globally weakened: the brand-new thesis the
        // student just opened IS active (PENDING_ELIGIBILITY_CHECK), so a third attempt is
        // correctly rejected.
        doPost("/api/theses", a.student, java.util.Map.of("title", "A third thesis, should be blocked"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------

    private Thesis reload(UUID thesisId) {
        return thesisRepository.findById(thesisId).orElseThrow();
    }
}
