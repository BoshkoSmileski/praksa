package com.praksa.service;

import com.praksa.dto.thesis.CreateThesisRequest;
import com.praksa.dto.thesis.ThesisResponse;
import com.praksa.exception.BadRequestException;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.repository.UserRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.service.impl.ThesisServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for roadmap Item #14 — resolving the {@code ELIGIBILITY_REJECTED} terminal
 * dead-end. A student whose only existing thesis is {@code ELIGIBILITY_REJECTED} must be
 * able to create a fresh thesis, while the rejected row is preserved untouched as
 * historical/audit data. The one-active-thesis rule must still apply to every other
 * non-terminal status. Pure Mockito, no Spring context — mirrors {@link ThesisCreditsDeadlineTest}.
 */
@ExtendWith(MockitoExtension.class)
class EligibilityRejectedNewThesisTest {

    @Mock private ThesisRepository thesisRepository;
    @Mock private ThesisStatusHistoryRepository statusHistoryRepository;
    @Mock private UserRepository userRepository;
    @Mock private SecurityUtils securityUtils;
    @Mock private NotificationService notificationService;
    @Mock private ApplicationPdfService applicationPdfService;

    @InjectMocks private ThesisServiceImpl thesisService;

    private User student(Integer credits) {
        return User.builder().id(UUID.randomUUID()).email("s@t.com")
                .fullName("Student").role(Role.STUDENT).credits(credits).build();
    }

    private Thesis thesisWithStatus(User owner, ThesisStatus status) {
        return Thesis.builder()
                .id(UUID.randomUUID())
                .title("Existing thesis")
                .student(owner)
                .status(status)
                .createdAt(OffsetDateTime.now().minusMonths(2))
                .submissionDeadline(OffsetDateTime.now().minusMonths(1))
                .build();
    }

    private CreateThesisRequest createRequest() {
        CreateThesisRequest req = new CreateThesisRequest();
        req.setTitle("A brand new thesis title");
        req.setStudentComment("note");
        return req;
    }

    // ------------------------------------------------------------------ core behavior

    @Test
    @DisplayName("Student whose only thesis is ELIGIBILITY_REJECTED can create a new thesis")
    void create_withRejectedThesis_allowed() {
        User s = student(240);
        Thesis rejected = thesisWithStatus(s, ThesisStatus.ELIGIBILITY_REJECTED);
        when(securityUtils.getCurrentUser()).thenReturn(s);
        when(thesisRepository.findByStudent(s)).thenReturn(List.of(rejected));

        ThesisResponse res = thesisService.createThesis(createRequest());

        assertNotNull(res);
        assertEquals(ThesisStatus.PENDING_ELIGIBILITY_CHECK, res.getStatus());
        verify(thesisRepository).save(any(Thesis.class));
    }

    @Test
    @DisplayName("The old ELIGIBILITY_REJECTED thesis is left completely untouched")
    void create_withRejectedThesis_oldRowUnchanged() {
        User s = student(240);
        Thesis rejected = thesisWithStatus(s, ThesisStatus.ELIGIBILITY_REJECTED);
        UUID rejectedId = rejected.getId();
        when(securityUtils.getCurrentUser()).thenReturn(s);
        when(thesisRepository.findByStudent(s)).thenReturn(List.of(rejected));

        thesisService.createThesis(createRequest());

        // The rejected row's status/id are unchanged in memory...
        assertEquals(ThesisStatus.ELIGIBILITY_REJECTED, rejected.getStatus());
        assertEquals(rejectedId, rejected.getId());

        // ...and it is never re-saved, deleted, or transitioned. Only the NEW thesis is saved.
        ArgumentCaptor<Thesis> saved = ArgumentCaptor.forClass(Thesis.class);
        verify(thesisRepository, times(1)).save(saved.capture());
        assertEquals(ThesisStatus.PENDING_ELIGIBILITY_CHECK, saved.getValue().getStatus());
        org.junit.jupiter.api.Assertions.assertNotEquals(rejectedId, saved.getValue().getId());
        verify(thesisRepository, never()).delete(any());
        verify(thesisRepository, never()).deleteById(any());

        // Exactly one history row is written — the new thesis's null -> PENDING_ELIGIBILITY_CHECK.
        // No history row is written against the old rejected thesis (no accidental transition).
        verify(statusHistoryRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("New thesis created after a rejection still gets createdAt=now and deadline=now+1 month")
    void create_withRejectedThesis_timestampsAndDeadline() {
        User s = student(240);
        Thesis rejected = thesisWithStatus(s, ThesisStatus.ELIGIBILITY_REJECTED);
        when(securityUtils.getCurrentUser()).thenReturn(s);
        when(thesisRepository.findByStudent(s)).thenReturn(List.of(rejected));

        ThesisResponse res = thesisService.createThesis(createRequest());

        assertNotNull(res.getCreatedAt());
        assertNotNull(res.getSubmissionDeadline());
        assertEquals(res.getCreatedAt().plusMonths(1), res.getSubmissionDeadline());
    }

    // ------------------------------------------------------------------ one-active-thesis rule preserved

    @Test
    @DisplayName("Student with a normal active thesis (IN_PROGRESS) still cannot create another")
    void create_withActiveThesis_rejected() {
        User s = student(240);
        Thesis active = thesisWithStatus(s, ThesisStatus.IN_PROGRESS);
        when(securityUtils.getCurrentUser()).thenReturn(s);
        when(thesisRepository.findByStudent(s)).thenReturn(List.of(active));

        assertThrows(BadRequestException.class, () -> thesisService.createThesis(createRequest()));
        verify(thesisRepository, never()).save(any());
    }

    @Test
    @DisplayName("Student with an active thesis alongside a rejected one still cannot create another")
    void create_withActiveAndRejected_rejected() {
        User s = student(240);
        Thesis rejected = thesisWithStatus(s, ThesisStatus.ELIGIBILITY_REJECTED);
        Thesis active = thesisWithStatus(s, ThesisStatus.PENDING_MENTOR_APPROVAL);
        when(securityUtils.getCurrentUser()).thenReturn(s);
        when(thesisRepository.findByStudent(s)).thenReturn(List.of(rejected, active));

        assertThrows(BadRequestException.class, () -> thesisService.createThesis(createRequest()));
        verify(thesisRepository, never()).save(any());
    }

    @Test
    @DisplayName("Student with only an ARCHIVED thesis can create a new one (pre-existing behavior preserved)")
    void create_withArchivedThesis_allowed() {
        User s = student(240);
        Thesis archived = thesisWithStatus(s, ThesisStatus.ARCHIVED);
        when(securityUtils.getCurrentUser()).thenReturn(s);
        when(thesisRepository.findByStudent(s)).thenReturn(List.of(archived));

        ThesisResponse res = thesisService.createThesis(createRequest());

        assertNotNull(res);
        assertEquals(ThesisStatus.PENDING_ELIGIBILITY_CHECK, res.getStatus());
        verify(thesisRepository).save(any(Thesis.class));
    }

    @Test
    @DisplayName("Student with no thesis at all can create normally")
    void create_noThesis_allowed() {
        User s = student(240);
        when(securityUtils.getCurrentUser()).thenReturn(s);
        when(thesisRepository.findByStudent(s)).thenReturn(List.of());

        ThesisResponse res = thesisService.createThesis(createRequest());

        assertNotNull(res);
        assertEquals(ThesisStatus.PENDING_ELIGIBILITY_CHECK, res.getStatus());
        verify(thesisRepository).save(any(Thesis.class));
    }

    // ------------------------------------------------------------------ credit gate still applies

    @Test
    @DisplayName("200-credit gate still applies even when the existing thesis is ELIGIBILITY_REJECTED")
    void create_withRejectedThesis_stillNeedsCredits() {
        User s = student(199);
        Thesis rejected = thesisWithStatus(s, ThesisStatus.ELIGIBILITY_REJECTED);
        when(securityUtils.getCurrentUser()).thenReturn(s);
        when(thesisRepository.findByStudent(s)).thenReturn(List.of(rejected));

        assertThrows(BadRequestException.class, () -> thesisService.createThesis(createRequest()));
        verify(thesisRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ authorization unchanged

    @Test
    @DisplayName("A non-STUDENT caller still cannot create a thesis (authorization unchanged)")
    void create_nonStudent_rejected() {
        User mentor = User.builder().id(UUID.randomUUID()).email("m@t.com")
                .fullName("Mentor").role(Role.MENTOR).credits(240).build();
        when(securityUtils.getCurrentUser()).thenReturn(mentor);

        assertThrows(UnauthorizedException.class, () -> thesisService.createThesis(createRequest()));
        verify(thesisRepository, never()).save(any());
    }
}
