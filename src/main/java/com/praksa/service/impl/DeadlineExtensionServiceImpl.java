package com.praksa.service.impl;

import com.praksa.dto.thesis.DeadlineExtensionCreateRequest;
import com.praksa.dto.thesis.DeadlineExtensionDecisionRequest;
import com.praksa.dto.thesis.DeadlineExtensionResponse;
import com.praksa.exception.BadRequestException;
import com.praksa.exception.ResourceNotFoundException;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.DeadlineExtensionRequest;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.DeadlineExtensionStatus;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;
import com.praksa.repository.DeadlineExtensionRequestRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.security.ThesisReadAccessPolicy;
import com.praksa.service.DeadlineExtensionService;
import com.praksa.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Official faculty procedure: a student may request an extension of the thesis's defense
 * deadline ({@link Thesis#getDefenseDeadline()}), for a maximum of 15 additional days, with a
 * written explanation.
 *
 * <p><b>Request lifecycle.</b> Submitting a request never mutates the deadline directly — it
 * creates a PENDING {@link DeadlineExtensionRequest} row. Only an APPROVED decision advances
 * {@code Thesis.defenseDeadline}, by EXACTLY the requested number of days (server-computed,
 * never a client-supplied date). Rejection leaves the deadline untouched but records the
 * mandatory reason. Neither decision ever changes the thesis's workflow {@code status} — this is
 * a purely administrative sub-process layered on top of the existing lifecycle, matching how
 * archive notes (P2.2) never touch status either.
 *
 * <p><b>Repeat-request policy (design decision, documented in CLAUDE.md).</b> At most one
 * PENDING request may exist per thesis at a time (mirrors {@code DefenseRequest}'s "one pending
 * proposal" rule). A student may resubmit after a rejection. To keep the total extension bounded
 * — this project has no concept of "extension #2" in the faculty procedure it was handed — at
 * most ONE approved extension is permitted per thesis; a further submission is rejected once an
 * APPROVED row already exists. This guarantees the deadline can never silently drift more than
 * 15 days from its original value through repeated approvals.
 */
@Service
@RequiredArgsConstructor
public class DeadlineExtensionServiceImpl implements DeadlineExtensionService {

    private final DeadlineExtensionRequestRepository extensionRequestRepository;
    private final ThesisRepository thesisRepository;
    private final SecurityUtils securityUtils;
    private final NotificationService notificationService;
    private final ThesisReadAccessPolicy thesisReadAccessPolicy;

    private static final String MK_APPROVED = "Барањето за продолжување на рокот е одобрено.";
    private static final String MK_REJECTED = "Барањето за продолжување на рокот е одбиено.";

    // -------------------------------------------------------------------------
    // SUBMIT REQUEST (student-initiated)
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public DeadlineExtensionResponse submitDeadlineExtensionRequest(UUID thesisId, DeadlineExtensionCreateRequest request) {
        User student = securityUtils.getCurrentUser();
        requireRole(student, Role.STUDENT);

        Thesis thesis = findThesis(thesisId);

        // Ownership — never trust a client-supplied student id; the caller is resolved solely
        // from the authenticated principal (securityUtils.getCurrentUser()) and compared against
        // the persisted thesis owner. Same IDOR-safe pattern as every other student-owned action
        // in ThesisServiceImpl / DefenseServiceImpl.
        if (!thesis.getStudent().getId().equals(student.getId())) {
            throw new UnauthorizedException("Не сте сопственик на оваа дипломска работа.");
        }

        if (thesis.getDefenseDeadline() == null) {
            throw new BadRequestException(
                    "Оваа дипломска работа сè уште нема рок за одбрана — продолжување на рокот може да "
                            + "се побара откако Студентската служба ќе ги потврди условите за одбрана.");
        }

        // Only one PENDING proposal at a time — mirrors DefenseServiceImpl's identical rule.
        if (extensionRequestRepository.findByThesisAndStatus(thesis, DeadlineExtensionStatus.PENDING).isPresent()) {
            throw new BadRequestException(
                    "Веќе постои барање за продолжување на рокот што чека одлука. Почекајте ја одлуката на "
                            + "Студентската служба пред да поднесете ново барање.");
        }

        // Conservative design choice: at most one APPROVED extension per thesis, so the total
        // extension granted can never silently exceed the 15-day-per-request cap through
        // repeated approvals. See the class-level Javadoc.
        if (extensionRequestRepository.findByThesisAndStatus(thesis, DeadlineExtensionStatus.APPROVED).isPresent()) {
            throw new BadRequestException(
                    "Веќе е одобрено продолжување на рокот за одбрана на оваа дипломска работа; "
                            + "дополнителни продолжувања не се дозволени.");
        }

        // Defense-in-depth: re-validate requestedDays even though the DTO's @Min/@Max already
        // enforce 1-15 — the service layer is the authoritative boundary in this codebase, never
        // the DTO alone (matching CommitteeServiceImpl's proposal-composition re-validation).
        Integer requestedDays = request.getRequestedDays();
        if (requestedDays == null || requestedDays < 1) {
            throw new BadRequestException("Бројот на побарани денови мора да биде најмалку 1.");
        }
        if (requestedDays > 15) {
            throw new BadRequestException("Едно барање за продолжување не може да надмине 15 дена.");
        }

        String reason = request.getReason() == null ? null : request.getReason().trim();
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("Причината е задолжителна.");
        }

        DeadlineExtensionRequest extensionRequest = DeadlineExtensionRequest.builder()
                .thesis(thesis)
                .requestedBy(student)
                .reason(reason)
                .requestedDays(requestedDays)
                .status(DeadlineExtensionStatus.PENDING)
                .createdAt(OffsetDateTime.now())
                .build();
        extensionRequestRepository.save(extensionRequest);

        notificationService.notifyRole(Role.STUDENT_SERVICE, thesis, NotificationType.DEADLINE_EXTENSION_REQUESTED);

        return DeadlineExtensionResponse.from(extensionRequest);
    }

    // -------------------------------------------------------------------------
    // DECISION (STUDENT_SERVICE approves or rejects the PENDING request)
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public DeadlineExtensionResponse decideDeadlineExtensionRequest(UUID thesisId, DeadlineExtensionDecisionRequest request) {
        User service = securityUtils.getCurrentUser();
        requireRole(service, Role.STUDENT_SERVICE);

        Thesis thesis = findThesis(thesisId);

        DeadlineExtensionRequest pending = extensionRequestRepository
                .findByThesisAndStatus(thesis, DeadlineExtensionStatus.PENDING)
                .orElseThrow(() -> new BadRequestException(
                        "Нема барање за продолжување на рокот што чека одлука за оваа дипломска работа."));

        boolean approved = Boolean.TRUE.equals(request.getApproved());

        if (!approved) {
            String reason = request.getReason();
            if (reason == null || reason.isBlank()) {
                throw new BadRequestException("Причината е задолжителна при одбивање на барање за продолжување на рокот.");
            }
            pending.setStatus(DeadlineExtensionStatus.REJECTED);
            pending.setDecisionReason(reason.trim());
            pending.setPreviousDeadline(thesis.getDefenseDeadline());
            pending.setDecidedAt(OffsetDateTime.now());
            pending.setDecidedBy(service);
            extensionRequestRepository.save(pending);

            // Deadline is deliberately left UNTOUCHED — no thesisRepository.save(thesis) call.
            String message = MK_REJECTED + "\n\nПричина: " + pending.getDecisionReason();
            notificationService.notify(pending.getRequestedBy(), thesis,
                    NotificationType.DEADLINE_EXTENSION_REJECTED, message);

            return DeadlineExtensionResponse.from(pending);
        }

        OffsetDateTime previousDeadline = thesis.getDefenseDeadline();
        if (previousDeadline == null) {
            // Defensive guard — should be unreachable, since submission already required a
            // non-null deadline and nothing else clears it. Never silently invents one.
            throw new BadRequestException(
                    "Не може да се одобри: оваа дипломска работа повеќе нема поставен рок за одбрана.");
        }

        OffsetDateTime newDeadline = previousDeadline.plusDays(pending.getRequestedDays());

        // The deadline extension is an administrative sub-process — it does NOT go through
        // transitionStatus() and writes NO ThesisStatusHistory row, since the thesis's workflow
        // status is not changing (see class Javadoc).
        thesis.setDefenseDeadline(newDeadline);
        thesisRepository.save(thesis);

        pending.setStatus(DeadlineExtensionStatus.APPROVED);
        pending.setPreviousDeadline(previousDeadline);
        pending.setNewDeadline(newDeadline);
        pending.setDecidedAt(OffsetDateTime.now());
        pending.setDecidedBy(service);
        extensionRequestRepository.save(pending);

        String message = MK_APPROVED + "\n\nНов рок за одбрана: " + newDeadline;
        notificationService.notify(pending.getRequestedBy(), thesis,
                NotificationType.DEADLINE_EXTENSION_APPROVED, message);

        return DeadlineExtensionResponse.from(pending);
    }

    // -------------------------------------------------------------------------
    // REQUEST HISTORY (read)
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<DeadlineExtensionResponse> getDeadlineExtensionRequests(UUID thesisId) {
        Thesis thesis = findThesis(thesisId);
        // Same thesis-scoped policy as every other thesis-level read (owner/mentor/committee
        // seat/STUDENT_SERVICE/ARCHIVE) — knowing another thesis's UUID grants nothing.
        thesisReadAccessPolicy.requireReadAccess(thesis, securityUtils.getCurrentUser());
        return extensionRequestRepository.findByThesisOrderByCreatedAtDesc(thesis)
                .stream()
                .map(DeadlineExtensionResponse::from)
                .toList();
    }

    // -------------------------------------------------------------------------
    // PRIVATE HELPERS
    // -------------------------------------------------------------------------

    private Thesis findThesis(UUID id) {
        return thesisRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Дипломската работа не е пронајдена: " + id));
    }

    private void requireRole(User user, Role required) {
        if (user.getRole() != required) {
            throw new UnauthorizedException("Оваа акција бара улога: " + required);
        }
    }
}
