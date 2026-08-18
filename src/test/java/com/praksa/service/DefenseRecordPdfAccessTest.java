package com.praksa.service;

import com.praksa.exception.BadRequestException;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.CommitteeMember;
import com.praksa.model.Defense;
import com.praksa.model.DefenseResult;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.MemberRole;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Item #11 — authorization and availability (state) rules for the defense-record PDF
 * endpoint ({@code DefenseResultServiceImpl.generateRecordPdf}). The PDF renderer itself
 * is mocked here; actual rendering (incl. Cyrillic) is covered by
 * {@link DefenseRecordPdfServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class DefenseRecordPdfAccessTest {

    @Mock private DefenseResultRepository resultRepository;
    @Mock private DefenseRepository defenseRepository;
    @Mock private ThesisRepository thesisRepository;
    @Mock private ThesisStatusHistoryRepository statusHistoryRepository;
    @Mock private CommitteeMemberRepository committeeRepository;
    @Mock private SecurityUtils securityUtils;
    @Mock private NotificationService notificationService;
    @Mock private DefenseRecordPdfService recordPdfService;

    @InjectMocks private DefenseResultServiceImpl service;

    private User user(Role role) {
        return User.builder().id(UUID.randomUUID()).email(role + "@t.com")
                .fullName(role + " User").role(role).build();
    }

    private Thesis thesis(User student, User mentor) {
        return Thesis.builder().id(UUID.randomUUID()).title("Тема")
                .student(student).mentor(mentor).status(ThesisStatus.ARCHIVED)
                .archiveRegistrationNumber("DT-2026-0001").build();
    }

    private Defense defense(Thesis thesis) {
        return Defense.builder().id(UUID.randomUUID()).thesis(thesis)
                .room("A1").scheduledAt(OffsetDateTime.now().minusDays(1)).isCancelled(false).build();
    }

    private DefenseResult result(Defense defense, User recorder) {
        return DefenseResult.builder().id(UUID.randomUUID()).defense(defense)
                .grade(9).notes("Одлично").recordedBy(recorder).build();
    }

    @Test
    @DisplayName("Student owner can download the record; renderer is invoked with the loaded data")
    void studentOwner_canDownload() {
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);
        Thesis thesis = thesis(student, mentor);
        Defense defense = defense(thesis);
        DefenseResult result = result(defense, user(Role.COMMITTEE));
        List<CommitteeMember> committee = List.of(
                CommitteeMember.builder().thesis(thesis).professor(mentor).memberRole(MemberRole.MENTOR_MEMBER).build());
        byte[] expected = {1, 2, 3};

        when(securityUtils.getCurrentUser()).thenReturn(student);
        when(defenseRepository.findById(defense.getId())).thenReturn(Optional.of(defense));
        when(resultRepository.findByDefense(defense)).thenReturn(Optional.of(result));
        when(committeeRepository.findByThesis(thesis)).thenReturn(committee);
        when(recordPdfService.generate(thesis, defense, result, committee)).thenReturn(expected);

        byte[] pdf = service.generateRecordPdf(thesis.getId(), defense.getId());

        assertArrayEquals(expected, pdf);
        verify(recordPdfService).generate(eq(thesis), eq(defense), eq(result), eq(committee));
    }

    @Test
    @DisplayName("Committee member seated on THIS thesis can download the record")
    void seatedCommitteeMember_canDownload() {
        User student = user(Role.STUDENT);
        User committeeProf = user(Role.COMMITTEE);
        Thesis thesis = thesis(student, user(Role.MENTOR));
        Defense defense = defense(thesis);
        DefenseResult result = result(defense, committeeProf);

        when(securityUtils.getCurrentUser()).thenReturn(committeeProf);
        when(defenseRepository.findById(defense.getId())).thenReturn(Optional.of(defense));
        when(committeeRepository.existsByThesisAndProfessor(thesis, committeeProf)).thenReturn(true);
        when(resultRepository.findByDefense(defense)).thenReturn(Optional.of(result));
        when(committeeRepository.findByThesis(thesis)).thenReturn(List.of());
        when(recordPdfService.generate(any(), any(), any(), any())).thenReturn(new byte[]{9});

        byte[] pdf = service.generateRecordPdf(thesis.getId(), defense.getId());

        assertArrayEquals(new byte[]{9}, pdf);
    }

    @Test
    @DisplayName("ARCHIVE role can download the record")
    void archiveRole_canDownload() {
        User archive = user(Role.ARCHIVE);
        Thesis thesis = thesis(user(Role.STUDENT), user(Role.MENTOR));
        Defense defense = defense(thesis);
        DefenseResult result = result(defense, user(Role.COMMITTEE));

        when(securityUtils.getCurrentUser()).thenReturn(archive);
        when(defenseRepository.findById(defense.getId())).thenReturn(Optional.of(defense));
        when(resultRepository.findByDefense(defense)).thenReturn(Optional.of(result));
        when(committeeRepository.findByThesis(thesis)).thenReturn(List.of());
        when(recordPdfService.generate(any(), any(), any(), any())).thenReturn(new byte[]{7});

        byte[] pdf = service.generateRecordPdf(thesis.getId(), defense.getId());

        assertArrayEquals(new byte[]{7}, pdf);
    }

    @Test
    @DisplayName("Unrelated user (COMMITTEE not on this committee) is rejected with 403 — no render")
    void unrelatedCommittee_rejected() {
        User outsider = user(Role.COMMITTEE);
        Thesis thesis = thesis(user(Role.STUDENT), user(Role.MENTOR));
        Defense defense = defense(thesis);

        when(securityUtils.getCurrentUser()).thenReturn(outsider);
        when(defenseRepository.findById(defense.getId())).thenReturn(Optional.of(defense));
        when(committeeRepository.existsByThesisAndProfessor(thesis, outsider)).thenReturn(false);

        assertThrows(UnauthorizedException.class,
                () -> service.generateRecordPdf(thesis.getId(), defense.getId()));

        verify(recordPdfService, never()).generate(any(), any(), any(), any());
        verify(resultRepository, never()).findByDefense(any());
    }

    @Test
    @DisplayName("Ungraded defense (no result) is rejected with 400 — no render")
    void ungradedDefense_rejected() {
        User service_ = user(Role.STUDENT_SERVICE);
        Thesis thesis = thesis(user(Role.STUDENT), user(Role.MENTOR));
        Defense defense = defense(thesis);

        when(securityUtils.getCurrentUser()).thenReturn(service_);
        when(defenseRepository.findById(defense.getId())).thenReturn(Optional.of(defense));
        when(resultRepository.findByDefense(defense)).thenReturn(Optional.empty());

        assertThrows(BadRequestException.class,
                () -> service.generateRecordPdf(thesis.getId(), defense.getId()));

        verify(recordPdfService, never()).generate(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Defense that does not belong to the thesis in the path is rejected with 400")
    void defenseNotBelongingToThesis_rejected() {
        Thesis thesis = thesis(user(Role.STUDENT), user(Role.MENTOR));
        Defense defense = defense(thesis);
        UUID unrelatedThesisId = UUID.randomUUID();

        when(defenseRepository.findById(defense.getId())).thenReturn(Optional.of(defense));

        assertThrows(BadRequestException.class,
                () -> service.generateRecordPdf(unrelatedThesisId, defense.getId()));

        verify(recordPdfService, never()).generate(any(), any(), any(), any());
    }
}
