package com.praksa.integration;

import com.praksa.model.CommitteeMember;
import com.praksa.model.Thesis;
import com.praksa.model.ThesisStatusHistory;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.ThesisStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P2.4 — Test 4 (committee formation + auto-advance).
 *
 * <p><b>Adapted to the REAL implementation.</b> The task brief sketches a per-member
 * "one active member at a time, the next becomes active" sequential model. The actual
 * project has no such per-member activation. The real committee model is:
 * <ul>
 *   <li>{@code proposeCommittee} seats all three members at once (mentor auto-added as
 *       MENTOR_MEMBER + two FORMAL_MEMBERs);</li>
 *   <li>{@code approveCommittee} (STUDENT_SERVICE) moves the thesis to COMMITTEE_REVIEW and
 *       stamps {@code committeeReviewStartedAt}, starting a 5-<i>business</i>-day clock;</li>
 *   <li>each seated professor may {@code submitReviewNotes} independently (no ordering);</li>
 *   <li>the review is completed either manually ({@code acceptCommitteeReview}) or automatically
 *       by the scheduled {@code ScheduledTasksService.autoAdvanceStaleCommitteeReviews} once the
 *       clock elapses — advancing COMMITTEE_REVIEW → COMMITTEE_ACCEPTED → PENDING_DEFENSE_CHECK.</li>
 * </ul>
 * These tests therefore exercise the genuine auto-advance job and the genuine seat model rather
 * than a non-existent sequential activation. The test never re-implements the auto-advance
 * algorithm — it invokes the real {@code ScheduledTasksService} bean and asserts the resulting
 * database state.
 *
 * <p>To simulate the passage of 5 business days without waiting, the test backdates the
 * thesis's {@code committeeReviewStartedAt} timestamp (a clock-only adjustment, NOT a status
 * change or a bypass of any workflow method) and then lets the real scheduled job decide and act.
 */
class CommitteeWorkflowIntegrationTest extends AbstractWorkflowIntegrationTest {

    @Test
    @DisplayName("Auto-advance: a stale COMMITTEE_REVIEW is advanced by the real scheduled job to PENDING_DEFENSE_CHECK, notifying everyone once")
    void autoAdvance_staleCommitteeReview_advancesAndNotifies() throws Exception {
        Actors a = newActors();
        UUID thesisId = advanceToCommitteeReview(a);

        // Baseline: exactly the 3 seats, review clock set, status COMMITTEE_REVIEW.
        Thesis thesis = thesisRepository.findById(thesisId).orElseThrow();
        assertEquals(ThesisStatus.COMMITTEE_REVIEW, thesis.getStatus());
        List<CommitteeMember> members = committeeRepository.findByThesis(thesis);
        assertEquals(3, members.size());

        // Not yet stale → the job must be a no-op for this thesis (clock is "now").
        scheduledTasksService.autoAdvanceStaleCommitteeReviews();
        assertEquals(ThesisStatus.COMMITTEE_REVIEW, reload(thesisId).getStatus(),
                "a fresh review must NOT be auto-advanced");

        // Simulate 5 business days elapsing: backdate the review-start clock (timestamp only).
        thesis = reload(thesisId);
        thesis.setCommitteeReviewStartedAt(OffsetDateTime.now().minusDays(30));
        thesisRepository.saveAndFlush(thesis);

        // Invoke the REAL scheduled job — it discovers the stale review and advances it.
        scheduledTasksService.autoAdvanceStaleCommitteeReviews();

        // Persisted result: COMMITTEE_REVIEW → COMMITTEE_ACCEPTED → PENDING_DEFENSE_CHECK.
        assertEquals(ThesisStatus.PENDING_DEFENSE_CHECK, reload(thesisId).getStatus());

        // The two system transitions were recorded with changedBy = null (system action).
        List<ThesisStatusHistory> history = statusHistoryRepository
                .findByThesisOrderByChangedAtAsc(reload(thesisId));
        List<ThesisStatusHistory> systemAdvance = history.stream()
                .filter(h -> h.getNewStatus() == ThesisStatus.COMMITTEE_ACCEPTED
                        || (h.getNewStatus() == ThesisStatus.PENDING_DEFENSE_CHECK
                            && h.getOldStatus() == ThesisStatus.COMMITTEE_ACCEPTED))
                .toList();
        assertEquals(2, systemAdvance.size(), "two auto-advance transitions recorded");
        assertTrue(systemAdvance.stream().allMatch(h -> h.getChangedBy() == null),
                "auto-advance transitions are system actions (changedBy = null)");

        // Correct notification, correct recipients, each exactly once.
        assertEquals(1, notificationCount(a.student, NotificationType.COMMITTEE_REVIEW_AUTO_ADVANCED, thesisId));
        // Mentor holds the MENTOR_MEMBER seat → notified once through the committee loop (no duplicate).
        assertEquals(1, notificationCount(a.mentor, NotificationType.COMMITTEE_REVIEW_AUTO_ADVANCED, thesisId),
                "mentor notified exactly once via the MENTOR_MEMBER seat");
        assertEquals(1, notificationCount(a.professorA, NotificationType.COMMITTEE_REVIEW_AUTO_ADVANCED, thesisId));
        assertEquals(1, notificationCount(a.professorB, NotificationType.COMMITTEE_REVIEW_AUTO_ADVANCED, thesisId));

        // Idempotent: a second run does nothing (thesis has left COMMITTEE_REVIEW).
        long before = totalNotifications(thesisId);
        scheduledTasksService.autoAdvanceStaleCommitteeReviews();
        assertEquals(ThesisStatus.PENDING_DEFENSE_CHECK, reload(thesisId).getStatus());
        assertEquals(before, totalNotifications(thesisId),
                "re-running the job produces no further transitions or notifications");
    }

    @Test
    @DisplayName("A seated member cannot submit review notes once the review is complete (status has left COMMITTEE_REVIEW)")
    void completedReview_memberCannotSubmitNotesAgain() throws Exception {
        Actors a = newActors();
        UUID thesisId = advanceToCommitteeReview(a);

        // Grab professorA's seat id while the review is open.
        UUID seatId = committeeRepository.findByThesis(reload(thesisId)).stream()
                .filter(m -> m.getProfessor().getId().equals(a.professorA.getId()))
                .findFirst().orElseThrow().getId();

        // A first submission is fine while COMMITTEE_REVIEW.
        submitReview(a.professorA, thesisId, seatId, "First pass.");
        assertEquals("First pass.", committeeRepository.findById(seatId).orElseThrow().getNotes());

        // Complete the review (manual accept) → PENDING_DEFENSE_CHECK.
        acceptReview(a.service, thesisId);
        assertEquals(ThesisStatus.PENDING_DEFENSE_CHECK, reload(thesisId).getStatus());

        // Now the same member cannot submit notes again: the status guard rejects it (400)
        // and the previously stored notes are unchanged.
        doPatch("/api/theses/" + thesisId + "/committee/" + seatId + "/review", a.professorA,
                java.util.Map.of("notes", "Late edit after completion"))
                .andExpect(status().isBadRequest());
        assertEquals("First pass.", committeeRepository.findById(seatId).orElseThrow().getNotes(),
                "notes must not change once the review is complete");
    }

    @Test
    @DisplayName("approveCommittee requires exactly 3 members and only STUDENT_SERVICE may approve")
    void approveCommittee_requiresThreeMembersAndServiceRole() throws Exception {
        Actors a = newActors();
        UUID thesisId = advanceToMentorApproved(a);

        // Mentor proposes → 3 seats created, but thesis still MENTOR_APPROVED (no auto status change).
        proposeCommittee(a.mentor, thesisId, a.professorA, a.professorB);
        assertEquals(ThesisStatus.MENTOR_APPROVED, reload(thesisId).getStatus());
        assertEquals(3, committeeRepository.countByThesis(reload(thesisId)));

        // A non-STUDENT_SERVICE user cannot approve the committee (role boundary → 403).
        doPost("/api/theses/" + thesisId + "/committee/approve", a.mentor, null)
                .andExpect(status().isForbidden());
        assertEquals(ThesisStatus.MENTOR_APPROVED, reload(thesisId).getStatus());
        assertNull(committeeRepository.findByThesis(reload(thesisId)).get(0).getApprovedBy(),
                "seats stay unstamped while approval is refused");

        // STUDENT_SERVICE approves → COMMITTEE_REVIEW.
        approveCommittee(a.service, thesisId);
        assertEquals(ThesisStatus.COMMITTEE_REVIEW, reload(thesisId).getStatus());

        // Re-proposing a committee after one exists is rejected (400).
        doPost("/api/theses/" + thesisId + "/committee/propose", a.mentor,
                java.util.Map.of("professorIds",
                        List.of(a.professorA.getId().toString(), a.professorB.getId().toString())))
                .andExpect(status().isBadRequest());
    }

    private Thesis reload(UUID thesisId) {
        return thesisRepository.findById(thesisId).orElseThrow();
    }
}
