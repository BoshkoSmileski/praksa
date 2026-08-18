package com.praksa.service;

import com.praksa.exception.BadRequestException;
import com.praksa.model.Thesis;
import com.praksa.model.ThesisVersion;
import com.praksa.model.User;
import com.praksa.model.enums.NotificationType;
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

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the final-version notification added in roadmap Item #4.
 *
 * The recipient is the assigned mentor only: markAsFinal runs while the thesis is
 * IN_PROGRESS, before any defense committee exists.
 */
@ExtendWith(MockitoExtension.class)
class ThesisVersionServiceNotificationTest {

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
    @DisplayName("markAsFinal notifies the assigned mentor with FINAL_VERSION_SUBMITTED")
    void markAsFinal_notifiesMentor() {
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);

        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).mentor(mentor).status(ThesisStatus.IN_PROGRESS).build();

        ThesisVersion version = ThesisVersion.builder().id(UUID.randomUUID())
                .thesis(thesis).versionNumber(2).pdfUrl("uploads/v2.pdf").isFinal(false).build();

        when(securityUtils.getCurrentUser()).thenReturn(student);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(versionRepository.findByThesisAndIsFinalTrue(thesis)).thenReturn(Optional.empty());
        when(versionRepository.findById(version.getId())).thenReturn(Optional.of(version));

        versionService.markAsFinal(thesis.getId(), version.getId());

        verify(notificationService).notify(mentor, thesis, NotificationType.FINAL_VERSION_SUBMITTED);
        assert version.isFinal();
        assert thesis.getStatus() == ThesisStatus.FINAL_SUBMITTED;
    }

    @Test
    @DisplayName("markAsFinal from the wrong status throws and sends NO notification")
    void markAsFinal_wrongStatus_noNotification() {
        User student = user(Role.STUDENT);
        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).mentor(user(Role.MENTOR))
                .status(ThesisStatus.FINAL_SUBMITTED).build(); // not IN_PROGRESS

        when(securityUtils.getCurrentUser()).thenReturn(student);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));

        assertThrows(BadRequestException.class,
                () -> versionService.markAsFinal(thesis.getId(), UUID.randomUUID()));

        verify(notificationService, never()).notify(any(), any(), any());
    }
}
