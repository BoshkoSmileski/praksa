package com.praksa.service;

import com.praksa.exception.ResourceNotFoundException;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.CommitteeMemberRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.repository.UserRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.security.ThesisReadAccessPolicy;
import com.praksa.service.impl.ThesisServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Authorization for {@code GET /api/theses/{id}/application-pdf}
 * ({@link ThesisServiceImpl#downloadApplicationPdf}).
 *
 * <p>Before the fix, the endpoint allowed <em>any</em> {@code COMMITTEE}-role user to download
 * <em>any</em> thesis's application PDF as long as they knew the UUID — a per-resource
 * authorization gap. The fix delegates to the shared {@link ThesisReadAccessPolicy} so the PDF
 * download authorizes against the SAME thesis-specific rule as the other thesis-level reads.
 *
 * <p>These tests exercise the REAL {@link ThesisReadAccessPolicy} (only its
 * {@link CommitteeMemberRepository} is mocked), so the committee-seat check runs genuinely against
 * the <em>requested</em> thesis rather than being stubbed away. That proves the full role matrix
 * end-to-end through the endpoint, not just that a guard is wired in.
 *
 * <p>Policy (a caller may download iff):
 * <ol>
 *   <li>they are the thesis owner (STUDENT), or</li>
 *   <li>the assigned MENTOR, or</li>
 *   <li>a CommitteeMember seated on THIS specific thesis, or</li>
 *   <li>STUDENT_SERVICE, or</li>
 *   <li>ARCHIVE.</li>
 * </ol>
 * Everyone else → 403. The bare {@code COMMITTEE} role is NOT sufficient.
 */
@ExtendWith(MockitoExtension.class)
class ApplicationPdfDownloadAuthorizationTest {

    @Mock private ThesisRepository thesisRepository;
    @Mock private ThesisStatusHistoryRepository statusHistoryRepository;
    @Mock private UserRepository userRepository;
    @Mock private SecurityUtils securityUtils;
    @Mock private NotificationService notificationService;
    @Mock private ApplicationPdfService applicationPdfService;
    @Mock private CommitteeMemberRepository committeeMemberRepository;

    private ThesisServiceImpl thesisService;

    private User owner;
    private User assignedMentor;
    private Thesis thesis;

    /** Real, readable file on disk so an AUTHORIZED download returns a Resource. */
    @TempDir Path tmp;
    private String pdfPath;

    @BeforeEach
    void setUp() throws Exception {
        // Real read-access policy — only its committee-seat lookup is mocked.
        ThesisReadAccessPolicy policy = new ThesisReadAccessPolicy(committeeMemberRepository);
        thesisService = new ThesisServiceImpl(
                thesisRepository, statusHistoryRepository, userRepository,
                securityUtils, notificationService, applicationPdfService, policy);

        owner = User.builder().id(UUID.randomUUID()).email("owner@t.com")
                .fullName("Owner Student").role(Role.STUDENT).build();
        assignedMentor = User.builder().id(UUID.randomUUID()).email("mentor@t.com")
                .fullName("Assigned Mentor").role(Role.MENTOR).build();

        Path file = tmp.resolve("application.pdf");
        Files.write(file, "%PDF-1.4 test".getBytes());
        pdfPath = file.toAbsolutePath().toString();

        thesis = Thesis.builder().id(UUID.randomUUID()).title("Тема")
                .student(owner).mentor(assignedMentor)
                .status(ThesisStatus.PENDING_ARCHIVE_VALIDATION)
                .applicationPdfPath(pdfPath)
                .build();

        // findThesis(id) → this thesis (lenient: the null-path ordering test overrides it).
        lenient().when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
    }

    private User asRole(Role role) {
        return User.builder().id(UUID.randomUUID()).email(role + "@t.com")
                .fullName(role.name()).role(role).build();
    }

    private org.springframework.core.io.Resource download() {
        return thesisService.downloadApplicationPdf(thesis.getId());
    }

    // ── ALLOWED ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("thesis owner (STUDENT) → allowed, PDF returned")
    void owner_allowed() {
        when(securityUtils.getCurrentUser()).thenReturn(owner);

        org.springframework.core.io.Resource r = download();

        assertNotNull(r);
        assertTrue(r.exists());
        // Owner is authorized directly — the committee-seat lookup is never consulted.
        verify(committeeMemberRepository, never()).existsByThesisAndProfessor(any(), any());
    }

    @Test
    @DisplayName("assigned mentor → allowed, PDF returned")
    void assignedMentor_allowed() {
        when(securityUtils.getCurrentUser()).thenReturn(assignedMentor);

        assertNotNull(download());
        verify(committeeMemberRepository, never()).existsByThesisAndProfessor(any(), any());
    }

    @Test
    @DisplayName("committee member seated on THIS thesis → allowed, PDF returned")
    void seatedCommitteeMember_allowed() {
        User committee = asRole(Role.COMMITTEE);
        when(securityUtils.getCurrentUser()).thenReturn(committee);
        // Seat verified against the REQUESTED thesis.
        when(committeeMemberRepository.existsByThesisAndProfessor(thesis, committee)).thenReturn(true);

        assertNotNull(download());
        verify(committeeMemberRepository).existsByThesisAndProfessor(eq(thesis), eq(committee));
    }

    @Test
    @DisplayName("STUDENT_SERVICE → allowed, PDF returned")
    void studentService_allowed() {
        when(securityUtils.getCurrentUser()).thenReturn(asRole(Role.STUDENT_SERVICE));

        assertNotNull(download());
        verify(committeeMemberRepository, never()).existsByThesisAndProfessor(any(), any());
    }

    @Test
    @DisplayName("ARCHIVE → allowed, PDF returned")
    void archive_allowed() {
        when(securityUtils.getCurrentUser()).thenReturn(asRole(Role.ARCHIVE));

        assertNotNull(download());
        verify(committeeMemberRepository, never()).existsByThesisAndProfessor(any(), any());
    }

    // ── DENIED (403) ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("unseated COMMITTEE user → 403 (bare COMMITTEE role is not enough)")
    void unseatedCommittee_denied() {
        User committee = asRole(Role.COMMITTEE);
        when(securityUtils.getCurrentUser()).thenReturn(committee);
        when(committeeMemberRepository.existsByThesisAndProfessor(thesis, committee)).thenReturn(false);

        assertThrows(UnauthorizedException.class, this::download);
    }

    @Test
    @DisplayName("COMMITTEE member seated on ANOTHER thesis → 403; seat checked against the REQUESTED thesis")
    void committeeSeatedOnAnotherThesis_denied() {
        User committee = asRole(Role.COMMITTEE);
        when(securityUtils.getCurrentUser()).thenReturn(committee);
        // The user DOES sit on some other thesis, but the check is scoped to THIS thesis → false.
        when(committeeMemberRepository.existsByThesisAndProfessor(thesis, committee)).thenReturn(false);

        assertThrows(UnauthorizedException.class, this::download);
        // Proves membership is verified against the requested thesis, not "belongs to any committee".
        verify(committeeMemberRepository).existsByThesisAndProfessor(eq(thesis), eq(committee));
    }

    @Test
    @DisplayName("unrelated MENTOR (not assigned, not seated) → 403")
    void unrelatedMentor_denied() {
        User otherMentor = asRole(Role.MENTOR);
        when(securityUtils.getCurrentUser()).thenReturn(otherMentor);
        // Not the assigned mentor, so the policy falls through to the seat check → not seated.
        when(committeeMemberRepository.existsByThesisAndProfessor(thesis, otherMentor)).thenReturn(false);

        assertThrows(UnauthorizedException.class, this::download);
    }

    @Test
    @DisplayName("unrelated STUDENT (not the owner) → 403")
    void unrelatedStudent_denied() {
        User otherStudent = asRole(Role.STUDENT);
        when(securityUtils.getCurrentUser()).thenReturn(otherStudent);
        // A non-owner STUDENT never satisfies the owner branch and is not seated.
        when(committeeMemberRepository.existsByThesisAndProfessor(thesis, otherStudent)).thenReturn(false);

        assertThrows(UnauthorizedException.class, this::download);
    }

    // ── DENIED — no sensitive downstream work performed ──────────────────────

    @Test
    @DisplayName("authorization runs BEFORE the PDF-existence probe: unauthorized caller on a thesis "
            + "with NO generated PDF still gets 403 (not 404), so downstream file work never runs")
    void authorizationPrecedesPdfExistenceAndFileWork() {
        // A thesis that has no generated application PDF at all.
        Thesis noPdf = Thesis.builder().id(UUID.randomUUID()).title("Тема")
                .student(owner).mentor(assignedMentor)
                .status(ThesisStatus.PENDING_ARCHIVE_VALIDATION)
                .applicationPdfPath(null)
                .build();
        when(thesisRepository.findById(noPdf.getId())).thenReturn(Optional.of(noPdf));

        User committee = asRole(Role.COMMITTEE);
        when(securityUtils.getCurrentUser()).thenReturn(committee);
        when(committeeMemberRepository.existsByThesisAndProfessor(noPdf, committee)).thenReturn(false);

        // If authorization ran AFTER the null-path check, an unauthorized caller would leak the
        // PDF's existence as a 404 (ResourceNotFoundException). Getting 403 proves the guard is
        // first — the caller can't even probe whether a PDF exists, and no file is read.
        assertThrows(UnauthorizedException.class,
                () -> thesisService.downloadApplicationPdf(noPdf.getId()));
    }

    @Test
    @DisplayName("authorized caller whose PDF file is missing on disk → 404 (proves the guard let "
            + "an authorized read through to the file layer)")
    void authorizedButFileMissing_notFound() {
        Thesis missing = Thesis.builder().id(UUID.randomUUID()).title("Тема")
                .student(owner).mentor(assignedMentor)
                .status(ThesisStatus.PENDING_ARCHIVE_VALIDATION)
                .applicationPdfPath(tmp.resolve("does-not-exist.pdf").toAbsolutePath().toString())
                .build();
        when(thesisRepository.findById(missing.getId())).thenReturn(Optional.of(missing));
        when(securityUtils.getCurrentUser()).thenReturn(owner);

        assertThrows(ResourceNotFoundException.class,
                () -> thesisService.downloadApplicationPdf(missing.getId()));
    }
}
