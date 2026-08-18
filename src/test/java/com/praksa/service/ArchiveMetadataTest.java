package com.praksa.service;

import com.praksa.dto.defense.RecordResultRequest;
import com.praksa.exception.BadRequestException;
import com.praksa.model.Defense;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.CommitteeMemberRepository;
import com.praksa.repository.DefenseRepository;
import com.praksa.repository.DefenseResultRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.service.impl.DefenseResultServiceImpl;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Item #10 — archive metadata is populated automatically when a thesis is ARCHIVED.
 *
 * The ONLY place a thesis becomes ARCHIVED is DefenseResultServiceImpl.recordResult, so
 * these pure-Mockito tests drive that method and assert the four metadata fields:
 *   archiveRegistrationNumber, archiveDate, archivedBy (→ archivedByName), archiveNotes.
 */
@ExtendWith(MockitoExtension.class)
class ArchiveMetadataTest {

    @Mock private DefenseResultRepository resultRepository;
    @Mock private DefenseRepository defenseRepository;
    @Mock private ThesisRepository thesisRepository;
    @Mock private ThesisStatusHistoryRepository statusHistoryRepository;
    @Mock private CommitteeMemberRepository committeeRepository;
    @Mock private SecurityUtils securityUtils;
    @Mock private NotificationService notificationService;

    @InjectMocks private DefenseResultServiceImpl defenseResultService;

    private User user(Role role) {
        return User.builder().id(UUID.randomUUID()).email(role + "@t.com")
                .fullName(role + " User").role(role).build();
    }

    private Thesis scheduledThesis(User student, User mentor) {
        return Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).mentor(mentor).status(ThesisStatus.DEFENSE_SCHEDULED).build();
    }

    private Defense defenseFor(Thesis thesis, boolean cancelled) {
        return Defense.builder().id(UUID.randomUUID()).thesis(thesis)
                .room("A1").scheduledAt(OffsetDateTime.now().minusDays(1)).isCancelled(cancelled).build();
    }

    private RecordResultRequest req(int grade, String notes) {
        RecordResultRequest r = new RecordResultRequest();
        r.setGrade(grade);
        r.setNotes(notes);
        return r;
    }

    private void stubHappyPath(User recorder, Thesis thesis, Defense defense, long existingCount) {
        when(securityUtils.getCurrentUser()).thenReturn(recorder);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        // Recorder holds a seat on THIS thesis's committee (write-side grading authorization).
        when(committeeRepository.existsByThesisAndProfessor(thesis, recorder)).thenReturn(true);
        when(defenseRepository.findById(defense.getId())).thenReturn(Optional.of(defense));
        when(resultRepository.findByDefense(defense)).thenReturn(Optional.empty());
        when(thesisRepository.countByArchiveRegistrationNumberStartingWith(anyString())).thenReturn(existingCount);
    }

    @Test
    @DisplayName("Archiving populates ALL required metadata: reg number, date, archived-by user, and status ARCHIVED")
    void archiving_populatesAllMetadata() {
        User committee = user(Role.COMMITTEE);
        Thesis thesis = scheduledThesis(user(Role.STUDENT), user(Role.MENTOR));
        Defense defense = defenseFor(thesis, false);
        stubHappyPath(committee, thesis, defense, 0L);

        OffsetDateTime before = OffsetDateTime.now();
        defenseResultService.recordResult(thesis.getId(), defense.getId(), req(9, "Well done"));

        assertEquals(ThesisStatus.ARCHIVED, thesis.getStatus());

        // registration number generated in DT-YYYY-0001 form for the current year
        String expectedPrefix = "DT-" + OffsetDateTime.now().getYear() + "-";
        assertNotNull(thesis.getArchiveRegistrationNumber());
        assertTrue(thesis.getArchiveRegistrationNumber().startsWith(expectedPrefix),
                "reg number should start with " + expectedPrefix + " but was " + thesis.getArchiveRegistrationNumber());
        assertEquals(expectedPrefix + "0001", thesis.getArchiveRegistrationNumber());

        // archiveDate populated with a timestamp at/after the call
        assertNotNull(thesis.getArchiveDate());
        assertTrue(!thesis.getArchiveDate().isBefore(before.minusSeconds(1)));

        // archivedBy is the ACTUAL user who performed the archive (→ archivedByName in the DTO)
        assertSame(committee, thesis.getArchivedBy());
        assertEquals("COMMITTEE User", thesis.getArchivedBy().getFullName());
    }

    @Test
    @DisplayName("A seated committee member archives and becomes the archived-by user")
    void archiving_bySeatedCommitteeMember_setsArchivedBy() {
        // Grading is now scoped to the thesis's committee (P1 write-side IDOR / BUG-13).
        // STUDENT_SERVICE can no longer record a grade — see DefenseResultGradingAuthorizationTest
        // for the full role matrix. Here a seated COMMITTEE-role professor performs the archive.
        User committeeMember = user(Role.COMMITTEE);
        Thesis thesis = scheduledThesis(user(Role.STUDENT), user(Role.MENTOR));
        Defense defense = defenseFor(thesis, false);
        stubHappyPath(committeeMember, thesis, defense, 0L);

        defenseResultService.recordResult(thesis.getId(), defense.getId(), req(8, null));

        assertSame(committeeMember, thesis.getArchivedBy());
        assertEquals(ThesisStatus.ARCHIVED, thesis.getStatus());
    }

    @Test
    @DisplayName("Registration number is the next in sequence — never duplicates an existing archived record")
    void registrationNumber_isNextInSequence() {
        User committee = user(Role.COMMITTEE);
        Thesis thesis = scheduledThesis(user(Role.STUDENT), user(Role.MENTOR));
        Defense defense = defenseFor(thesis, false);
        // 5 theses already archived this year → the new one must be 0006, not a reused 0001..0005
        stubHappyPath(committee, thesis, defense, 5L);

        defenseResultService.recordResult(thesis.getId(), defense.getId(), req(7, null));

        String expected = "DT-" + OffsetDateTime.now().getYear() + "-0006";
        assertEquals(expected, thesis.getArchiveRegistrationNumber());
    }

    @Test
    @DisplayName("Existing archive notes are preserved (not overwritten) when archiving")
    void archiveNotes_preservedIfPresent() {
        User committee = user(Role.COMMITTEE);
        Thesis thesis = scheduledThesis(user(Role.STUDENT), user(Role.MENTOR));
        thesis.setArchiveNotes("Pre-existing archive note");
        Defense defense = defenseFor(thesis, false);
        stubHappyPath(committee, thesis, defense, 0L);

        defenseResultService.recordResult(thesis.getId(), defense.getId(), req(10, "Excellent"));

        // archiveNotes is NOT clobbered by the archiving step, and NOT fabricated from result notes
        assertEquals("Pre-existing archive note", thesis.getArchiveNotes());
    }

    @Test
    @DisplayName("Archive notes stay null when none exist — never fabricated from the grade notes")
    void archiveNotes_nullWhenAbsent() {
        User committee = user(Role.COMMITTEE);
        Thesis thesis = scheduledThesis(user(Role.STUDENT), user(Role.MENTOR));
        Defense defense = defenseFor(thesis, false);
        stubHappyPath(committee, thesis, defense, 0L);

        defenseResultService.recordResult(thesis.getId(), defense.getId(), req(6, "Grade notes here"));

        assertNull(thesis.getArchiveNotes());
    }

    @Test
    @DisplayName("A thesis that is NOT successfully archived receives NO archive metadata prematurely")
    void failedArchive_noMetadata() {
        User committee = user(Role.COMMITTEE);
        Thesis thesis = scheduledThesis(user(Role.STUDENT), user(Role.MENTOR));
        Defense cancelled = defenseFor(thesis, true); // cancelled defense → recordResult throws

        when(securityUtils.getCurrentUser()).thenReturn(committee);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        // Seated committee member: authorization passes, so the cancelled-defense rule is what rejects.
        when(committeeRepository.existsByThesisAndProfessor(thesis, committee)).thenReturn(true);
        when(defenseRepository.findById(cancelled.getId())).thenReturn(Optional.of(cancelled));

        assertThrows(BadRequestException.class,
                () -> defenseResultService.recordResult(thesis.getId(), cancelled.getId(), req(8, null)));

        assertNull(thesis.getArchiveRegistrationNumber());
        assertNull(thesis.getArchiveDate());
        assertNull(thesis.getArchivedBy());
        assertEquals(ThesisStatus.DEFENSE_SCHEDULED, thesis.getStatus()); // unchanged, not ARCHIVED
    }
}
