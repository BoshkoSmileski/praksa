package com.praksa.service;

import com.praksa.dto.thesis.ArchiveNotesRequest;
import com.praksa.dto.thesis.ThesisResponse;
import com.praksa.exception.ResourceNotFoundException;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.repository.UserRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.security.ThesisReadAccessPolicy;
import com.praksa.service.impl.ThesisServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2.2 — {@link ThesisServiceImpl#updateArchiveNotes} unit-level guarantees.
 *
 * <p>Focus: the ARCHIVE-only authorization, the ARCHIVED-status guard, and the two
 * invariants the feature must never violate — editing notes NEVER changes the thesis
 * status (no {@code ThesisStatusHistory} row) and NEVER emits a notification. Persistence
 * and the full HTTP/security matrix are covered by the sibling
 * {@code ArchiveNotesIntegrationTest}; this class pins the no-side-effect behavior with mocks.
 */
@ExtendWith(MockitoExtension.class)
class ArchiveNotesServiceTest {

    @Mock private ThesisRepository thesisRepository;
    @Mock private ThesisStatusHistoryRepository statusHistoryRepository;
    @Mock private UserRepository userRepository;
    @Mock private SecurityUtils securityUtils;
    @Mock private NotificationService notificationService;
    @Mock private ApplicationPdfService applicationPdfService;
    @Mock private ThesisReadAccessPolicy thesisReadAccessPolicy;

    @InjectMocks private ThesisServiceImpl service;

    private User user(Role role) {
        return User.builder().id(UUID.randomUUID()).email(role + "@t.com")
                .fullName(role + " User").role(role).build();
    }

    private Thesis archivedThesis(String notes) {
        return Thesis.builder()
                .id(UUID.randomUUID())
                .student(user(Role.STUDENT))
                .title("A thesis")
                .status(ThesisStatus.ARCHIVED)
                .archiveNotes(notes)
                .build();
    }

    private ArchiveNotesRequest req(String notes) {
        ArchiveNotesRequest r = new ArchiveNotesRequest();
        r.setNotes(notes);
        return r;
    }

    @Test
    @DisplayName("ARCHIVE sets notes on an archived thesis; no status change, no history row, no notification")
    void archive_setsNotes_noSideEffects() {
        User archive = user(Role.ARCHIVE);
        Thesis thesis = archivedThesis(null);
        when(securityUtils.getCurrentUser()).thenReturn(archive);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));

        ThesisResponse res = service.updateArchiveNotes(thesis.getId(), req("  Kept in box 12, shelf 3  "));

        assertEquals("Kept in box 12, shelf 3", thesis.getArchiveNotes());   // trimmed
        assertEquals("Kept in box 12, shelf 3", res.getArchiveNotes());
        assertEquals(ThesisStatus.ARCHIVED, thesis.getStatus());             // status untouched
        verify(thesisRepository).save(thesis);
        verify(statusHistoryRepository, never()).save(any());               // no transition → no history
        verify(notificationService, never()).notify(any(), any(), any());   // no notification
    }

    @Test
    @DisplayName("ARCHIVE edits existing notes (overwrites the previous value)")
    void archive_editsExistingNotes() {
        User archive = user(Role.ARCHIVE);
        Thesis thesis = archivedThesis("old note");
        when(securityUtils.getCurrentUser()).thenReturn(archive);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));

        service.updateArchiveNotes(thesis.getId(), req("new note"));

        assertEquals("new note", thesis.getArchiveNotes());
        verify(statusHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Blank/null notes clears the field")
    void archive_blankNotes_clears() {
        User archive = user(Role.ARCHIVE);
        Thesis thesis = archivedThesis("something");
        when(securityUtils.getCurrentUser()).thenReturn(archive);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));

        service.updateArchiveNotes(thesis.getId(), req("   "));

        assertNull(thesis.getArchiveNotes());
    }

    @Test
    @DisplayName("Non-ARCHIVE roles are rejected (403) before the thesis is even loaded — no side effects")
    void nonArchive_rejected() {
        for (Role role : new Role[]{Role.STUDENT, Role.MENTOR, Role.STUDENT_SERVICE, Role.COMMITTEE}) {
            User caller = user(role);
            when(securityUtils.getCurrentUser()).thenReturn(caller);

            assertThrows(UnauthorizedException.class,
                    () -> service.updateArchiveNotes(UUID.randomUUID(), req("hax")));

            verify(thesisRepository, never()).findById(any());   // role checked BEFORE loading the thesis
            verify(thesisRepository, never()).save(any());
            verify(statusHistoryRepository, never()).save(any());
        }
    }

    @Test
    @DisplayName("Nonexistent thesis → 404 (normal not-found), notes never touched")
    void archive_nonexistentThesis_404() {
        User archive = user(Role.ARCHIVE);
        UUID missing = UUID.randomUUID();
        when(securityUtils.getCurrentUser()).thenReturn(archive);
        when(thesisRepository.findById(missing)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.updateArchiveNotes(missing, req("note")));

        verify(thesisRepository, never()).save(any());
    }

    @Test
    @DisplayName("Non-ARCHIVED thesis → 400; notes NOT set, status unchanged")
    void archive_nonArchivedStatus_rejected() {
        User archive = user(Role.ARCHIVE);
        Thesis thesis = Thesis.builder()
                .id(UUID.randomUUID())
                .student(user(Role.STUDENT))
                .title("t")
                .status(ThesisStatus.IN_PROGRESS)
                .build();
        when(securityUtils.getCurrentUser()).thenReturn(archive);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));

        assertThrows(com.praksa.exception.BadRequestException.class,
                () -> service.updateArchiveNotes(thesis.getId(), req("note")));

        assertNull(thesis.getArchiveNotes());
        assertEquals(ThesisStatus.IN_PROGRESS, thesis.getStatus());
        verify(thesisRepository, never()).save(any());
    }
}
