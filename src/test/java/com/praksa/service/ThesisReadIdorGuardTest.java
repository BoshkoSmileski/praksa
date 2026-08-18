package com.praksa.service;

import com.praksa.exception.ResourceNotFoundException;
import com.praksa.exception.UnauthorizedException;
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
import com.praksa.repository.UserRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.security.ThesisReadAccessPolicy;
import com.praksa.service.impl.CommitteeServiceImpl;
import com.praksa.service.impl.DefenseResultServiceImpl;
import com.praksa.service.impl.DefenseServiceImpl;
import com.praksa.service.impl.ThesisServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Read-side IDOR fix — service-layer wiring for the six affected read endpoints. Proves that
 * each one delegates to {@link ThesisReadAccessPolicy#requireReadAccess} BEFORE returning any
 * data, so an unrelated authenticated caller (who only knows a thesis/defense UUID) is rejected
 * with a 403 and no sensitive data is loaded. The policy itself is mocked here; its full
 * role-by-role behaviour is covered by {@code ThesisReadAccessPolicyTest}.
 *
 * Pattern per method: one "denied" test (policy throws → method throws, downstream repo never
 * queried) and one "allowed" test (policy is a no-op → method proceeds past the guard).
 */
@ExtendWith(MockitoExtension.class)
class ThesisReadIdorGuardTest {

    // Shared collaborators (Mockito injects the matching-typed mock into every service below).
    @Mock private ThesisRepository thesisRepository;
    @Mock private ThesisStatusHistoryRepository statusHistoryRepository;
    @Mock private UserRepository userRepository;
    @Mock private CommitteeMemberRepository committeeRepository;
    @Mock private DefenseRepository defenseRepository;
    @Mock private DefenseResultRepository resultRepository;
    @Mock private SecurityUtils securityUtils;
    @Mock private NotificationService notificationService;
    @Mock private ApplicationPdfService applicationPdfService;
    @Mock private DefenseRecordPdfService recordPdfService;
    @Mock private ThesisReadAccessPolicy policy;

    @InjectMocks private ThesisServiceImpl thesisService;
    @InjectMocks private CommitteeServiceImpl committeeService;
    @InjectMocks private DefenseServiceImpl defenseService;
    @InjectMocks private DefenseResultServiceImpl defenseResultService;

    private User caller() {
        return User.builder().id(UUID.randomUUID()).email("outsider@t.com")
                .fullName("Outsider").role(Role.STUDENT).build();
    }

    private Thesis thesis() {
        User student = User.builder().id(UUID.randomUUID()).fullName("Owner").role(Role.STUDENT).build();
        return Thesis.builder().id(UUID.randomUUID()).title("Тема")
                .student(student).status(ThesisStatus.IN_PROGRESS).build();
    }

    private Defense defense(Thesis thesis) {
        return Defense.builder().id(UUID.randomUUID()).thesis(thesis)
                .room("A1").scheduledAt(OffsetDateTime.now().plusDays(1)).isCancelled(false).build();
    }

    private void denyGuard() {
        doThrow(new UnauthorizedException("You do not have access to this thesis"))
                .when(policy).requireReadAccess(any(), any());
    }

    // ── ThesisServiceImpl.getThesisById ──────────────────────────────────────

    @Nested
    class GetThesisById {
        @Test
        @DisplayName("unrelated caller → 403, no thesis returned")
        void denied() {
            Thesis t = thesis();
            User u = caller();
            when(thesisRepository.findById(t.getId())).thenReturn(Optional.of(t));
            when(securityUtils.getCurrentUser()).thenReturn(u);
            denyGuard();

            assertThrows(UnauthorizedException.class, () -> thesisService.getThesisById(t.getId()));
            verify(policy).requireReadAccess(eq(t), eq(u));
        }

        @Test
        @DisplayName("related caller → guard passes, thesis returned")
        void allowed() {
            Thesis t = thesis();
            when(thesisRepository.findById(t.getId())).thenReturn(Optional.of(t));
            when(securityUtils.getCurrentUser()).thenReturn(caller());

            assertNotNull(thesisService.getThesisById(t.getId()));
            verify(policy).requireReadAccess(eq(t), any());
        }
    }

    // ── ThesisServiceImpl.getStatusHistory ───────────────────────────────────

    @Nested
    class GetStatusHistory {
        @Test
        @DisplayName("unrelated caller → 403, history never queried")
        void denied() {
            Thesis t = thesis();
            when(thesisRepository.findById(t.getId())).thenReturn(Optional.of(t));
            when(securityUtils.getCurrentUser()).thenReturn(caller());
            denyGuard();

            assertThrows(UnauthorizedException.class, () -> thesisService.getStatusHistory(t.getId()));
            verify(statusHistoryRepository, never()).findByThesisOrderByChangedAtAsc(any());
        }

        @Test
        @DisplayName("related caller → guard passes, history queried")
        void allowed() {
            Thesis t = thesis();
            when(thesisRepository.findById(t.getId())).thenReturn(Optional.of(t));
            when(securityUtils.getCurrentUser()).thenReturn(caller());
            when(statusHistoryRepository.findByThesisOrderByChangedAtAsc(t)).thenReturn(List.of());

            assertTrue(thesisService.getStatusHistory(t.getId()).isEmpty());
            verify(policy).requireReadAccess(eq(t), any());
        }
    }

    // ── CommitteeServiceImpl.getCommittee ────────────────────────────────────

    @Nested
    class GetCommittee {
        @Test
        @DisplayName("unrelated caller → 403, committee never queried")
        void denied() {
            Thesis t = thesis();
            when(thesisRepository.findById(t.getId())).thenReturn(Optional.of(t));
            when(securityUtils.getCurrentUser()).thenReturn(caller());
            denyGuard();

            assertThrows(UnauthorizedException.class, () -> committeeService.getCommittee(t.getId()));
            verify(committeeRepository, never()).findByThesis(any());
        }

        @Test
        @DisplayName("related caller → guard passes, committee queried")
        void allowed() {
            Thesis t = thesis();
            when(thesisRepository.findById(t.getId())).thenReturn(Optional.of(t));
            when(securityUtils.getCurrentUser()).thenReturn(caller());
            when(committeeRepository.findByThesis(t)).thenReturn(List.of());

            assertTrue(committeeService.getCommittee(t.getId()).isEmpty());
            verify(policy).requireReadAccess(eq(t), any());
        }
    }

    // ── DefenseServiceImpl.getActiveDefense ──────────────────────────────────

    @Nested
    class GetActiveDefense {
        @Test
        @DisplayName("unrelated caller → 403, defense never queried")
        void denied() {
            Thesis t = thesis();
            when(thesisRepository.findById(t.getId())).thenReturn(Optional.of(t));
            when(securityUtils.getCurrentUser()).thenReturn(caller());
            denyGuard();

            assertThrows(UnauthorizedException.class, () -> defenseService.getActiveDefense(t.getId()));
            verify(defenseRepository, never()).findByThesisAndIsCancelledFalse(any());
        }

        @Test
        @DisplayName("related caller → guard passes, reaches the defense lookup")
        void allowed() {
            Thesis t = thesis();
            when(thesisRepository.findById(t.getId())).thenReturn(Optional.of(t));
            when(securityUtils.getCurrentUser()).thenReturn(caller());
            when(defenseRepository.findByThesisAndIsCancelledFalse(t)).thenReturn(Optional.empty());

            // Reaching ResourceNotFound (not Unauthorized) proves the guard allowed the read.
            assertThrows(ResourceNotFoundException.class, () -> defenseService.getActiveDefense(t.getId()));
            verify(policy).requireReadAccess(eq(t), any());
        }
    }

    // ── DefenseServiceImpl.getAllDefenses ────────────────────────────────────

    @Nested
    class GetAllDefenses {
        @Test
        @DisplayName("unrelated caller → 403, defenses never queried")
        void denied() {
            Thesis t = thesis();
            when(thesisRepository.findById(t.getId())).thenReturn(Optional.of(t));
            when(securityUtils.getCurrentUser()).thenReturn(caller());
            denyGuard();

            assertThrows(UnauthorizedException.class, () -> defenseService.getAllDefenses(t.getId()));
            verify(defenseRepository, never()).findByThesis(any());
        }

        @Test
        @DisplayName("related caller → guard passes, defenses queried")
        void allowed() {
            Thesis t = thesis();
            when(thesisRepository.findById(t.getId())).thenReturn(Optional.of(t));
            when(securityUtils.getCurrentUser()).thenReturn(caller());
            when(defenseRepository.findByThesis(t)).thenReturn(List.of());

            assertTrue(defenseService.getAllDefenses(t.getId()).isEmpty());
            verify(policy).requireReadAccess(eq(t), any());
        }
    }

    // ── ThesisServiceImpl.findByRegistrationNumber ───────────────────────────

    @Nested
    class FindByRegistrationNumber {
        @Test
        @DisplayName("unrelated caller → 403, no thesis returned (registration-number enumeration blocked)")
        void denied() {
            Thesis t = thesis();
            t.setArchiveRegistrationNumber("DT-2026-0001");
            when(thesisRepository.findByArchiveRegistrationNumber("DT-2026-0001")).thenReturn(Optional.of(t));
            when(securityUtils.getCurrentUser()).thenReturn(caller());
            denyGuard();

            assertThrows(UnauthorizedException.class,
                    () -> thesisService.findByRegistrationNumber("DT-2026-0001"));
            verify(policy).requireReadAccess(eq(t), any());
        }

        @Test
        @DisplayName("related caller → guard passes, thesis returned")
        void allowed() {
            Thesis t = thesis();
            t.setArchiveRegistrationNumber("DT-2026-0001");
            when(thesisRepository.findByArchiveRegistrationNumber("DT-2026-0001")).thenReturn(Optional.of(t));
            when(securityUtils.getCurrentUser()).thenReturn(caller());

            assertNotNull(thesisService.findByRegistrationNumber("DT-2026-0001"));
            verify(policy).requireReadAccess(eq(t), any());
        }
    }

    // ── DefenseResultServiceImpl.getResult ───────────────────────────────────

    @Nested
    class GetResult {
        @Test
        @DisplayName("unrelated caller → 403, result never queried (grade not exposed)")
        void denied() {
            Thesis t = thesis();
            Defense d = defense(t);
            when(defenseRepository.findById(d.getId())).thenReturn(Optional.of(d));
            when(securityUtils.getCurrentUser()).thenReturn(caller());
            denyGuard();

            assertThrows(UnauthorizedException.class, () -> defenseResultService.getResult(t.getId(), d.getId()));
            verify(policy).requireReadAccess(eq(t), any());
            verify(resultRepository, never()).findByDefense(any());
        }

        @Test
        @DisplayName("related caller → guard passes, reaches the result lookup")
        void allowed() {
            Thesis t = thesis();
            Defense d = defense(t);
            when(defenseRepository.findById(d.getId())).thenReturn(Optional.of(d));
            when(securityUtils.getCurrentUser()).thenReturn(caller());
            when(resultRepository.findByDefense(d)).thenReturn(Optional.empty());

            // Reaching ResourceNotFound (not Unauthorized) proves the guard allowed the read.
            assertThrows(ResourceNotFoundException.class,
                    () -> defenseResultService.getResult(t.getId(), d.getId()));
            verify(policy).requireReadAccess(eq(t), any());
        }
    }
}
