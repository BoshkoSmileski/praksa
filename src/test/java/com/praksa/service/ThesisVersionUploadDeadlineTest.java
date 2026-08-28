package com.praksa.service;

import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.CommitteeMemberRepository;
import com.praksa.repository.ThesisCommentRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.repository.ThesisVersionRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.service.impl.ThesisVersionServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 45-day mentor-review-deadline feature — scenario E: a successful version upload resets
 * {@link Thesis#getLastVersionSubmittedAt()}. The timestamp must be stamped only when the
 * upload itself succeeds (never on a mere read), which is exercised here by verifying
 * {@code thesisRepository.save} is called with a non-null, freshly-set timestamp right after
 * the version is persisted.
 *
 * Pure Mockito, mirroring {@link ThesisVersionServiceNotificationTest}.
 */
@ExtendWith(MockitoExtension.class)
class ThesisVersionUploadDeadlineTest {

    @Mock private ThesisRepository thesisRepository;
    @Mock private ThesisVersionRepository versionRepository;
    @Mock private ThesisCommentRepository commentRepository;
    @Mock private ThesisStatusHistoryRepository statusHistoryRepository;
    @Mock private CommitteeMemberRepository committeeMemberRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private SecurityUtils securityUtils;
    @Mock private NotificationService notificationService;

    @InjectMocks private ThesisVersionServiceImpl versionService;

    private User user(Role role) {
        return User.builder().id(UUID.randomUUID()).email(role + "@t.com")
                .fullName(role + " User").role(role).build();
    }

    @Test
    @DisplayName("A successful version upload stamps lastVersionSubmittedAt to now and persists the thesis")
    void uploadVersion_resetsLastVersionSubmittedAt() {
        User student = user(Role.STUDENT);
        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).status(ThesisStatus.IN_PROGRESS)
                .lastVersionSubmittedAt(OffsetDateTime.now().minusDays(60)) // stale, from a prior cycle
                .build();

        MockMultipartFile file = new MockMultipartFile(
                "file", "thesis.pdf", "application/pdf", "content".getBytes());

        when(securityUtils.getCurrentUser()).thenReturn(student);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(versionRepository.findTopByThesisOrderByVersionNumberDesc(thesis)).thenReturn(Optional.empty());
        when(fileStorageService.storePdf(any(), any(), anyInt())).thenReturn("uploads/v1.pdf");

        OffsetDateTime before = OffsetDateTime.now();
        versionService.uploadVersion(thesis.getId(), file);
        OffsetDateTime after = OffsetDateTime.now();

        assertNotNull(thesis.getLastVersionSubmittedAt());
        assertTrue(!thesis.getLastVersionSubmittedAt().isBefore(before)
                        && !thesis.getLastVersionSubmittedAt().isAfter(after),
                "lastVersionSubmittedAt should be stamped to ~now, not the stale prior value");
        verify(thesisRepository).save(thesis);
    }

    @Test
    @DisplayName("A failed version upload (DB save failure) does NOT stamp lastVersionSubmittedAt")
    void uploadVersion_dbFailure_doesNotStampDeadline() {
        User student = user(Role.STUDENT);
        OffsetDateTime original = OffsetDateTime.now().minusDays(10);
        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).status(ThesisStatus.IN_PROGRESS)
                .lastVersionSubmittedAt(original)
                .build();

        MockMultipartFile file = new MockMultipartFile(
                "file", "thesis.pdf", "application/pdf", "content".getBytes());

        when(securityUtils.getCurrentUser()).thenReturn(student);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(versionRepository.findTopByThesisOrderByVersionNumberDesc(thesis)).thenReturn(Optional.empty());
        when(fileStorageService.storePdf(any(), any(), anyInt())).thenReturn("uploads/v1.pdf");
        when(versionRepository.save(any())).thenThrow(new RuntimeException("db down"));

        try {
            versionService.uploadVersion(thesis.getId(), file);
        } catch (RuntimeException expected) {
            // expected — upload rolled back
        }

        // The clock is untouched: still the original value, never saved a second time.
        assertNotNull(thesis.getLastVersionSubmittedAt());
        assertTrue(thesis.getLastVersionSubmittedAt().isEqual(original));
        verify(thesisRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("14-day defense-request rule — a version upload does NOT change applicationSubmittedAt")
    void uploadVersion_doesNotChangeApplicationSubmittedAt() {
        User student = user(Role.STUDENT);
        OffsetDateTime submittedAt = OffsetDateTime.now().minusDays(5);
        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).status(ThesisStatus.IN_PROGRESS)
                .applicationSubmittedAt(submittedAt)
                .build();

        MockMultipartFile file = new MockMultipartFile(
                "file", "thesis.pdf", "application/pdf", "content".getBytes());

        when(securityUtils.getCurrentUser()).thenReturn(student);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(versionRepository.findTopByThesisOrderByVersionNumberDesc(thesis)).thenReturn(Optional.empty());
        when(fileStorageService.storePdf(any(), any(), anyInt())).thenReturn("uploads/v1.pdf");

        versionService.uploadVersion(thesis.getId(), file);

        assertTrue(thesis.getApplicationSubmittedAt().isEqual(submittedAt),
                "applicationSubmittedAt must be untouched by a version upload (it is only set by submitApplication)");
    }
}
