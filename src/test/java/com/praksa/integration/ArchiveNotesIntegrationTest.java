package com.praksa.integration;

import com.praksa.model.Thesis;
import com.praksa.model.enums.ThesisStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P2.2 — ARCHIVE-role archive-notes editor, full HTTP integration (real security chain +
 * real PostgreSQL). Endpoint under test: {@code PATCH /api/theses/{id}/archive-notes}.
 *
 * <p>Verifies the authorization matrix, the IDOR protection, the ARCHIVED-status guard, and
 * the two invariants: editing notes never changes the thesis status (no new history row) and
 * never creates a notification. Also confirms the existing archive validation/rejection
 * workflow is unaffected.
 */
class ArchiveNotesIntegrationTest extends AbstractWorkflowIntegrationTest {

    /** Drives a fresh thesis all the way to ARCHIVED and returns its id. */
    private UUID archivedThesis(Actors a) throws Exception {
        UUID thesisId = advanceToDefenseScheduled(a, "Room 101", OffsetDateTime.now().plusDays(7));
        UUID defenseId = defenseRepository.findByThesisAndIsCancelledFalse(
                        thesisRepository.findById(thesisId).orElseThrow())
                .orElseThrow().getId();
        // The mentor holds a MENTOR_MEMBER seat on this committee, so may record the grade.
        recordResult(a.mentor, thesisId, defenseId, 9);
        return thesisId;
    }

    private String notesBody(String notes) {
        return notes == null ? "{}" : "{\"notes\":\"" + notes + "\"}";
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ARCHIVE can set notes on an ARCHIVED thesis; status unchanged, no history row, no notification")
    void archive_setsNotes() throws Exception {
        Actors a = newActors();
        UUID thesisId = archivedThesis(a);

        long historyBefore = statusHistoryRepository.findByThesisOrderByChangedAtAsc(
                thesisRepository.findById(thesisId).orElseThrow()).size();
        long notifsBefore = totalNotifications(thesisId);

        mockMvc.perform(patch("/api/theses/" + thesisId + "/archive-notes")
                        .header("Authorization", bearer(a.archive))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notesBody("Stored in archive box A-12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.archiveNotes").value("Stored in archive box A-12"))
                .andExpect(jsonPath("$.data.status").value("ARCHIVED"));

        Thesis t = thesisRepository.findById(thesisId).orElseThrow();
        assertEquals("Stored in archive box A-12", t.getArchiveNotes());
        assertEquals(ThesisStatus.ARCHIVED, t.getStatus());
        assertEquals(historyBefore,
                statusHistoryRepository.findByThesisOrderByChangedAtAsc(t).size(),
                "editing notes must not add a status-history row");
        assertEquals(notifsBefore, totalNotifications(thesisId),
                "editing notes must not create a notification");
    }

    @Test
    @DisplayName("ARCHIVE can update existing notes (overwrite) and clear them (blank)")
    void archive_updatesAndClearsNotes() throws Exception {
        Actors a = newActors();
        UUID thesisId = archivedThesis(a);

        mockMvc.perform(patch("/api/theses/" + thesisId + "/archive-notes")
                        .header("Authorization", bearer(a.archive))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notesBody("first")))
                .andExpect(status().isOk());
        assertEquals("first", thesisRepository.findById(thesisId).orElseThrow().getArchiveNotes());

        // overwrite
        mockMvc.perform(patch("/api/theses/" + thesisId + "/archive-notes")
                        .header("Authorization", bearer(a.archive))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notesBody("second")))
                .andExpect(status().isOk());
        assertEquals("second", thesisRepository.findById(thesisId).orElseThrow().getArchiveNotes());

        // clear (blank → null)
        mockMvc.perform(patch("/api/theses/" + thesisId + "/archive-notes")
                        .header("Authorization", bearer(a.archive))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notesBody("   ")))
                .andExpect(status().isOk());
        assertNull(thesisRepository.findById(thesisId).orElseThrow().getArchiveNotes());
    }

    @Test
    @DisplayName("STUDENT / MENTOR / STUDENT_SERVICE / COMMITTEE are all forbidden (403); notes unchanged")
    void nonArchiveRoles_forbidden() throws Exception {
        Actors a = newActors();
        UUID thesisId = archivedThesis(a);

        // seed a known value via the archive so we can prove the forbidden calls change nothing
        mockMvc.perform(patch("/api/theses/" + thesisId + "/archive-notes")
                        .header("Authorization", bearer(a.archive))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notesBody("legit")))
                .andExpect(status().isOk());

        for (var caller : new com.praksa.model.User[]{a.student, a.mentor, a.service, a.committee, a.professorA}) {
            mockMvc.perform(patch("/api/theses/" + thesisId + "/archive-notes")
                            .header("Authorization", bearer(caller))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(notesBody("tampered")))
                    .andExpect(status().isForbidden());
        }

        assertEquals("legit", thesisRepository.findById(thesisId).orElseThrow().getArchiveNotes());
    }

    @Test
    @DisplayName("Unauthenticated request is rejected")
    void unauthenticated_rejected() throws Exception {
        Actors a = newActors();
        UUID thesisId = archivedThesis(a);

        mockMvc.perform(patch("/api/theses/" + thesisId + "/archive-notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notesBody("anon")))
                .andExpect(status().is4xxClientError());  // 401/403 — no valid session

        assertNull(thesisRepository.findById(thesisId).orElseThrow().getArchiveNotes());
    }

    @Test
    @DisplayName("ARCHIVE + nonexistent thesis → 404")
    void archive_nonexistentThesis_404() throws Exception {
        Actors a = newActors();
        mockMvc.perform(patch("/api/theses/" + UUID.randomUUID() + "/archive-notes")
                        .header("Authorization", bearer(a.archive))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notesBody("note")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("IDOR: a non-ARCHIVE user cannot edit a thesis's notes by knowing its ID (403)")
    void idor_nonArchiveCannotEditById() throws Exception {
        Actors a = newActors();
        UUID thesisId = archivedThesis(a);

        // The thesis owner (a real, related user) still cannot edit archive notes — it is not their action.
        mockMvc.perform(patch("/api/theses/" + thesisId + "/archive-notes")
                        .header("Authorization", bearer(a.student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notesBody("owner-tamper")))
                .andExpect(status().isForbidden());

        assertNull(thesisRepository.findById(thesisId).orElseThrow().getArchiveNotes());
    }

    @Test
    @DisplayName("ARCHIVE on a non-ARCHIVED thesis → 400 (notes only editable on the archive record)")
    void archive_nonArchivedThesis_400() throws Exception {
        Actors a = newActors();
        UUID inProgress = advanceToInProgress(a);   // status = IN_PROGRESS, not ARCHIVED

        mockMvc.perform(patch("/api/theses/" + inProgress + "/archive-notes")
                        .header("Authorization", bearer(a.archive))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notesBody("too early")))
                .andExpect(status().isBadRequest());

        Thesis t = thesisRepository.findById(inProgress).orElseThrow();
        assertNull(t.getArchiveNotes());
        assertEquals(ThesisStatus.IN_PROGRESS, t.getStatus());
    }

    @Test
    @DisplayName("Existing archive validation/rejection workflow still works")
    void existingArchiveValidationWorkflow_unaffected() throws Exception {
        Actors a = newActors();
        // Drive to PENDING_ARCHIVE_VALIDATION
        UUID thesisId = createThesis(a.student, "Archive-workflow thesis");
        decideEligibility(a.service, thesisId, true);
        submitMentorRequest(a.student, thesisId, a.mentor);
        decideMentor(a.mentor, thesisId, com.praksa.model.enums.MentorDecision.ACCEPT, null);
        submitApplication(a.student, thesisId);
        assertEquals(ThesisStatus.PENDING_ARCHIVE_VALIDATION,
                thesisRepository.findById(thesisId).orElseThrow().getStatus());

        // Archive REJECTS with a reason → APPLICATION_REJECTED_BY_ARCHIVE, comment stored
        archiveValidate(a.archive, thesisId, false, "Missing signature page");
        Thesis rejected = thesisRepository.findById(thesisId).orElseThrow();
        assertEquals(ThesisStatus.APPLICATION_REJECTED_BY_ARCHIVE, rejected.getStatus());
        assertEquals("Missing signature page", rejected.getArchiveComment());

        // Student resubmits, archive APPROVES → PENDING_SERVICE_VALIDATION
        submitApplication(a.student, thesisId);
        archiveValidate(a.archive, thesisId, true, null);
        assertEquals(ThesisStatus.PENDING_SERVICE_VALIDATION,
                thesisRepository.findById(thesisId).orElseThrow().getStatus());
    }
}
