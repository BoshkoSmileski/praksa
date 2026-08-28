package com.praksa.service;

import com.praksa.dto.thesis.CreateThesisRequest;
import com.praksa.dto.thesis.ThesisResponse;
import com.praksa.exception.BadRequestException;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for roadmap Item #5 — the 200-credit gate on thesis creation and the
 * 1-month submission-deadline calculation + enforcement. Pure Mockito, no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class ThesisCreditsDeadlineTest {

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

    private CreateThesisRequest createRequest() {
        CreateThesisRequest req = new CreateThesisRequest();
        req.setTitle("A valid thesis title");
        req.setStudentComment("note");
        return req;
    }

    // ---------------------------------------------------------------- credit gate

    @Test
    @DisplayName("Student with 199 credits cannot create a thesis")
    void create_199credits_rejected() {
        User s = student(199);
        when(securityUtils.getCurrentUser()).thenReturn(s);
        when(thesisRepository.findByStudent(s)).thenReturn(List.of());

        assertThrows(BadRequestException.class, () -> thesisService.createThesis(createRequest()));
        verify(thesisRepository, never()).save(any());
    }

    @Test
    @DisplayName("Student with exactly 200 credits can create a thesis")
    void create_200credits_allowed() {
        User s = student(200);
        when(securityUtils.getCurrentUser()).thenReturn(s);
        when(thesisRepository.findByStudent(s)).thenReturn(List.of());

        ThesisResponse res = thesisService.createThesis(createRequest());

        assertNotNull(res);
        assertEquals(ThesisStatus.PENDING_ELIGIBILITY_CHECK, res.getStatus());
        verify(thesisRepository).save(any(Thesis.class));
    }

    @Test
    @DisplayName("Student with more than 200 credits can create a thesis")
    void create_moreThan200_allowed() {
        User s = student(240);
        when(securityUtils.getCurrentUser()).thenReturn(s);
        when(thesisRepository.findByStudent(s)).thenReturn(List.of());

        ThesisResponse res = thesisService.createThesis(createRequest());

        assertNotNull(res);
        verify(thesisRepository).save(any(Thesis.class));
    }

    @Test
    @DisplayName("Student with null credits cannot create a thesis")
    void create_nullCredits_rejected() {
        User s = student(null);
        when(securityUtils.getCurrentUser()).thenReturn(s);
        when(thesisRepository.findByStudent(s)).thenReturn(List.of());

        assertThrows(BadRequestException.class, () -> thesisService.createThesis(createRequest()));
        verify(thesisRepository, never()).save(any());
    }

    // ---------------------------------------------------------------- deadline calc

    @Test
    @DisplayName("A newly created thesis gets a submission deadline ~1 month after creation")
    void create_setsOneMonthDeadline() {
        User s = student(240);
        when(securityUtils.getCurrentUser()).thenReturn(s);
        when(thesisRepository.findByStudent(s)).thenReturn(List.of());

        ThesisResponse res = thesisService.createThesis(createRequest());

        assertNotNull(res.getCreatedAt());
        assertNotNull(res.getSubmissionDeadline());
        // Deadline is exactly createdAt + 1 calendar month (java.time month arithmetic).
        assertEquals(res.getCreatedAt().plusMonths(1), res.getSubmissionDeadline());
    }

    // ---------------------------------------------------------------- deadline enforcement

    @Test
    @DisplayName("submitApplication succeeds before the deadline and generates the PDF")
    void submit_beforeDeadline_succeeds() {
        User s = student(240);
        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("T").student(s)
                .status(ThesisStatus.APPLICATION_SUBMITTED)
                .createdAt(OffsetDateTime.now().minusDays(1))
                .submissionDeadline(OffsetDateTime.now().plusDays(20))
                .build();

        when(securityUtils.getCurrentUser()).thenReturn(s);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(applicationPdfService.generate(thesis)).thenReturn("/uploads/app.pdf");

        ThesisResponse res = thesisService.submitApplication(thesis.getId());

        assertEquals(ThesisStatus.PENDING_ARCHIVE_VALIDATION, res.getStatus());
        verify(applicationPdfService).generate(thesis);
        verify(notificationService).notifyRole(eq(Role.ARCHIVE), eq(thesis), any());
    }

    @Test
    @DisplayName("submitApplication is rejected after the deadline and does NOT generate a PDF")
    void submit_afterDeadline_rejected_noPdf() {
        User s = student(240);
        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("T").student(s)
                .status(ThesisStatus.APPLICATION_SUBMITTED)
                .createdAt(OffsetDateTime.now().minusMonths(2))
                .submissionDeadline(OffsetDateTime.now().minusDays(1))  // expired
                .build();

        when(securityUtils.getCurrentUser()).thenReturn(s);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));

        assertThrows(BadRequestException.class, () -> thesisService.submitApplication(thesis.getId()));

        // No PDF, no transition, no archive notification when the deadline has passed.
        verify(applicationPdfService, never()).generate(any());
        verify(notificationService, never()).notifyRole(any(), any(), any());
        assertEquals(ThesisStatus.APPLICATION_SUBMITTED, thesis.getStatus());
    }

    @Test
    @DisplayName("Legacy thesis with a null submission deadline can still submit (no crash, not blocked)")
    void submit_nullDeadline_legacy_succeeds() {
        User s = student(240);
        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("T").student(s)
                .status(ThesisStatus.APPLICATION_SUBMITTED)
                .createdAt(OffsetDateTime.now().minusMonths(6))
                .submissionDeadline(null)  // legacy row, pre-Item-#5
                .build();

        when(securityUtils.getCurrentUser()).thenReturn(s);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(applicationPdfService.generate(thesis)).thenReturn("/uploads/app.pdf");

        ThesisResponse res = thesisService.submitApplication(thesis.getId());

        assertEquals(ThesisStatus.PENDING_ARCHIVE_VALIDATION, res.getStatus());
        verify(applicationPdfService).generate(thesis);
    }

    // ---------------------------------------------------------------- applicationSubmittedAt (14-day defense-request rule)

    @Test
    @DisplayName("submitApplication stamps applicationSubmittedAt to ~now")
    void submit_stampsApplicationSubmittedAt() {
        User s = student(240);
        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("T").student(s)
                .status(ThesisStatus.APPLICATION_SUBMITTED)
                .createdAt(OffsetDateTime.now().minusDays(1))
                .submissionDeadline(OffsetDateTime.now().plusDays(20))
                .build();

        when(securityUtils.getCurrentUser()).thenReturn(s);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(applicationPdfService.generate(thesis)).thenReturn("/uploads/app.pdf");

        OffsetDateTime before = OffsetDateTime.now();
        thesisService.submitApplication(thesis.getId());
        OffsetDateTime after = OffsetDateTime.now();

        assertNotNull(thesis.getApplicationSubmittedAt());
        org.junit.jupiter.api.Assertions.assertTrue(
                !thesis.getApplicationSubmittedAt().isBefore(before) && !thesis.getApplicationSubmittedAt().isAfter(after),
                "applicationSubmittedAt should be stamped to ~now");
    }

    @Test
    @DisplayName("Resubmitting after a rejection re-stamps applicationSubmittedAt to a fresh timestamp")
    void resubmitAfterRejection_reStampsApplicationSubmittedAt() {
        User s = student(240);
        OffsetDateTime stale = OffsetDateTime.now().minusDays(40);
        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("T").student(s)
                .status(ThesisStatus.APPLICATION_REJECTED_BY_ARCHIVE)
                .createdAt(OffsetDateTime.now().minusDays(50))
                .submissionDeadline(OffsetDateTime.now().plusDays(20))
                .applicationSubmittedAt(stale)
                .build();

        when(securityUtils.getCurrentUser()).thenReturn(s);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(applicationPdfService.generate(thesis)).thenReturn("/uploads/app.pdf");

        thesisService.submitApplication(thesis.getId());

        org.junit.jupiter.api.Assertions.assertTrue(thesis.getApplicationSubmittedAt().isAfter(stale),
                "a resubmission is a fresh formal submission event and must reset the 14-day clock");
    }

    @Test
    @DisplayName("Deadline is measured with real month arithmetic (not a fixed 30-day span)")
    void create_deadlineUsesCalendarMonth() {
        User s = student(240);
        when(securityUtils.getCurrentUser()).thenReturn(s);
        when(thesisRepository.findByStudent(s)).thenReturn(List.of());

        ThesisResponse res = thesisService.createThesis(createRequest());

        long days = ChronoUnit.DAYS.between(res.getCreatedAt(), res.getSubmissionDeadline());
        // A calendar month is 28–31 days; a fixed "+30 days" would fail for most months.
        org.junit.jupiter.api.Assertions.assertTrue(days >= 28 && days <= 31,
                "Expected a calendar month (28-31 days) but got " + days);
    }
}
