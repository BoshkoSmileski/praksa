package com.praksa.service.impl;

import com.praksa.dto.defense.DefenseRequestCreateRequest;
import com.praksa.dto.defense.DefenseRequestDecisionRequest;
import com.praksa.dto.defense.DefenseRequestResponse;
import com.praksa.dto.defense.DefenseResponse;
import com.praksa.exception.BadRequestException;
import com.praksa.exception.ResourceNotFoundException;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.CommitteeMember;
import com.praksa.model.Defense;
import com.praksa.model.DefenseRequest;
import com.praksa.model.Thesis;
import com.praksa.model.ThesisStatusHistory;
import com.praksa.model.User;
import com.praksa.model.enums.DefenseRequestStatus;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.CommitteeMemberRepository;
import com.praksa.repository.DefenseRepository;
import com.praksa.repository.DefenseRequestRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.security.ThesisReadAccessPolicy;
import com.praksa.service.DefenseService;
import com.praksa.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DefenseServiceImpl implements DefenseService {

    // The proposed defense date must land in [createdAt + MIN_PROPOSAL_DAYS, createdAt +
    // MAX_PROPOSAL_DAYS], both bounds inclusive, measured as exact 24h durations from the
    // moment the DefenseRequest was created (OffsetDateTime#plusDays is a fixed-offset
    // duration add — no calendar/DST ambiguity). E.g. created 2026-08-01 10:00 → valid window
    // is 2026-08-06 10:00 through 2026-08-16 10:00.
    private static final long MIN_PROPOSAL_DAYS = 5;
    private static final long MAX_PROPOSAL_DAYS = 15;

    // Official faculty procedure: the request for committee formation / defense scheduling
    // may only be submitted at least 14 FULL days after the formal thesis application was
    // submitted (Thesis.applicationSubmittedAt, stamped by ThesisServiceImpl#submitApplication).
    private static final long MIN_DAYS_SINCE_APPLICATION = 14;

    private static final String ROOM_CONFLICT_REASON = "Просторијата е зафатена во тој термин.";

    private final DefenseRepository defenseRepository;
    private final DefenseRequestRepository defenseRequestRepository;
    private final ThesisRepository thesisRepository;
    private final ThesisStatusHistoryRepository statusHistoryRepository;
    private final CommitteeMemberRepository committeeRepository;
    private final SecurityUtils securityUtils;
    private final NotificationService notificationService;
    private final ThesisReadAccessPolicy thesisReadAccessPolicy;

    // -------------------------------------------------------------------------
    // CREATE REQUEST (student-proposed room/date/time)
    //
    // The STUDENT proposes the actual defense term. This is stored as a PENDING
    // DefenseRequest — it does NOT create a Defense row and does NOT change the thesis
    // status. STUDENT_SERVICE reviews it via decideDefenseRequest.
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public DefenseRequestResponse createDefenseRequest(UUID thesisId, DefenseRequestCreateRequest request) {
        User student = securityUtils.getCurrentUser();
        requireRole(student, Role.STUDENT);

        Thesis thesis = findThesis(thesisId);

        // Student must own this thesis
        if (!thesis.getStudent().getId().equals(student.getId())) {
            throw new UnauthorizedException("Не сте сопственик на оваа дипломска работа.");
        }

        requireEligibleForProposal(thesis);
        requireApplicationAgeMet(thesis);

        // Only one PENDING proposal at a time — a student cannot flood the queue with
        // unlimited requests while one is still awaiting a decision.
        if (defenseRequestRepository.findByThesisAndStatus(thesis, DefenseRequestStatus.PENDING).isPresent()) {
            throw new BadRequestException(
                    "Веќе постои барање за одбрана што чека одлука. Почекајте ја одлуката на Студентската служба "
                            + "пред да поднесете нов предлог.");
        }

        if (request.getRoom() == null || request.getRoom().isBlank()) {
            throw new BadRequestException("Просторијата е задолжителна.");
        }
        if (request.getScheduledAt() == null) {
            throw new BadRequestException("Датумот и времето на одбраната се задолжителни.");
        }

        // The 5-15 day window is anchored to the moment the request is created — this
        // exact instant is what gets persisted and re-used (never "now") when the request
        // is later approved.
        OffsetDateTime createdAt = OffsetDateTime.now();
        validateProposalWindow(request.getScheduledAt(), createdAt);

        DefenseRequest defenseRequest = DefenseRequest.builder()
                .thesis(thesis)
                .requestedBy(student)
                .room(request.getRoom().trim())
                .scheduledAt(request.getScheduledAt())
                .status(DefenseRequestStatus.PENDING)
                .createdAt(createdAt)
                .build();
        defenseRequestRepository.save(defenseRequest);

        notificationService.notifyRole(Role.STUDENT_SERVICE, thesis, NotificationType.DEFENSE_REQUESTED);

        return DefenseRequestResponse.from(defenseRequest);
    }

    // -------------------------------------------------------------------------
    // DECISION (STUDENT_SERVICE approves or rejects the PENDING request)
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public DefenseRequestResponse decideDefenseRequest(UUID thesisId, DefenseRequestDecisionRequest request) {
        User service = securityUtils.getCurrentUser();
        requireRole(service, Role.STUDENT_SERVICE);

        Thesis thesis = findThesis(thesisId);

        DefenseRequest pending = defenseRequestRepository.findByThesisAndStatus(thesis, DefenseRequestStatus.PENDING)
                .orElseThrow(() -> new BadRequestException("Нема барање за одбрана што чека одлука за оваа дипломска работа."));

        boolean approved = Boolean.TRUE.equals(request.getApproved());

        if (!approved) {
            String reason = request.getReason();
            if (reason == null || reason.isBlank()) {
                throw new BadRequestException("Причината е задолжителна при одбивање на барање за одбрана.");
            }
            rejectRequest(pending, service, reason);
            return DefenseRequestResponse.from(pending);
        }

        // Re-check everything against the CURRENT database state — the request may have sat
        // pending long enough for the proposed date to fall outside the allowed window, or for
        // another thesis's defense to have taken the room in the meantime. Both re-checks reuse
        // the exact same validation the create step ran, so the rule is never duplicated.
        try {
            validateProposalWindow(pending.getScheduledAt(), pending.getCreatedAt());
            requireRoomAvailable(pending.getRoom(), pending.getScheduledAt());
        } catch (BadRequestException conflict) {
            // Per spec: a conflict discovered at approval time rejects the request rather than
            // failing the HTTP call — the student must submit a fresh proposal.
            rejectRequest(pending, service, conflict.getMessage());
            return DefenseRequestResponse.from(pending);
        }

        OffsetDateTime decidedAt = OffsetDateTime.now();
        pending.setStatus(DefenseRequestStatus.APPROVED);
        pending.setDecidedAt(decidedAt);
        pending.setDecidedBy(service);
        defenseRequestRepository.save(pending);

        Defense defense = Defense.builder()
                .thesis(thesis)
                .room(pending.getRoom())
                .scheduledAt(pending.getScheduledAt())
                .isCancelled(false)
                .build();
        defenseRepository.save(defense);

        // Advance status only if this is the first scheduling; a rebooking after cancellation
        // is already DEFENSE_SCHEDULED, so no further transition is needed.
        if (thesis.getStatus() == ThesisStatus.PENDING_DEFENSE_SCHEDULING) {
            transitionStatus(thesis, ThesisStatus.DEFENSE_SCHEDULED, service);
        }

        // ─── Notifications (only after the defense row is persisted) ─────────
        String details = "Одбраната е закажана во просторија " + defense.getRoom()
                + " на " + defense.getScheduledAt() + ".";
        notificationService.notify(thesis.getStudent(), thesis, NotificationType.DEFENSE_SCHEDULED, details);
        for (CommitteeMember m : committeeRepository.findByThesis(thesis)) {
            notificationService.notify(m.getProfessor(), thesis, NotificationType.DEFENSE_SCHEDULED, details);
        }

        return DefenseRequestResponse.from(pending);
    }

    private void rejectRequest(DefenseRequest pending, User decider, String reason) {
        pending.setStatus(DefenseRequestStatus.REJECTED);
        pending.setReason(reason);
        pending.setDecidedAt(OffsetDateTime.now());
        pending.setDecidedBy(decider);
        defenseRequestRepository.save(pending);

        notificationService.notify(pending.getRequestedBy(), pending.getThesis(),
                NotificationType.DEFENSE_REQUEST_REJECTED, reason);
    }

    // -------------------------------------------------------------------------
    // REQUEST HISTORY (read)
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<DefenseRequestResponse> getDefenseRequests(UUID thesisId) {
        Thesis thesis = findThesis(thesisId);
        // Same thesis-scoped policy as every other thesis-level read — a student can only ever
        // resolve their OWN thesis's requests through this endpoint (owner/mentor/committee
        // seat/STUDENT_SERVICE/ARCHIVE); knowing another thesis's UUID grants nothing.
        thesisReadAccessPolicy.requireReadAccess(thesis, securityUtils.getCurrentUser());
        return defenseRequestRepository.findByThesisOrderByCreatedAtDesc(thesis)
                .stream()
                .map(DefenseRequestResponse::from)
                .toList();
    }

    // -------------------------------------------------------------------------
    // CANCEL
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public DefenseResponse cancelDefense(UUID thesisId) {
        User caller = securityUtils.getCurrentUser();

        // Only the student who owns the thesis OR their mentor can cancel
        Thesis thesis = findThesis(thesisId);

        boolean isOwner = caller.getRole() == Role.STUDENT
                && thesis.getStudent().getId().equals(caller.getId());
        boolean isMentor = caller.getRole() == Role.MENTOR
                && thesis.getMentor() != null
                && thesis.getMentor().getId().equals(caller.getId());

        if (!isOwner && !isMentor) {
            throw new UnauthorizedException("Само студентот или менторот на дипломската работа можат да ја откажат одбраната.");
        }

        Defense defense = defenseRepository.findByThesisAndIsCancelledFalse(thesis)
                .orElseThrow(() -> new ResourceNotFoundException("Не е пронајдена активна одбрана за оваа дипломска работа."));

        // Capture the details before mutating — used in the notification message.
        String room = defense.getRoom();
        OffsetDateTime scheduledAt = defense.getScheduledAt();

        // Mark cancelled — thesis status does NOT go backwards.
        // The workflow stays at DEFENSE_SCHEDULED. Cancelling frees the room (cancelled
        // defenses never block availability) and lets the student submit a brand NEW
        // DefenseRequest — see requireEligibleForProposal's "rebooking" branch.
        defense.setCancelled(true);
        defense.setCancelledBy(caller);
        defense.setCancelledAt(OffsetDateTime.now());
        defenseRepository.save(defense);

        // ─── Notifications (only after the cancellation is persisted) ────────
        String details = "Одбраната во просторија " + room + " на " + scheduledAt
                + " беше откажана од " + caller.getRole() + ". Ќе биде предложен нов термин.";
        notificationService.notify(thesis.getStudent(), thesis, NotificationType.DEFENSE_CANCELLED, details);
        for (CommitteeMember m : committeeRepository.findByThesis(thesis)) {
            notificationService.notify(m.getProfessor(), thesis, NotificationType.DEFENSE_CANCELLED, details);
        }

        return DefenseResponse.from(defense);
    }

    // -------------------------------------------------------------------------
    // READ
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public DefenseResponse getActiveDefense(UUID thesisId) {
        Thesis thesis = findThesis(thesisId);
        // Authorize against the underlying thesis before revealing the defense schedule —
        // knowing the thesis (or defense) UUID must not bypass this.
        thesisReadAccessPolicy.requireReadAccess(thesis, securityUtils.getCurrentUser());
        Defense defense = defenseRepository.findByThesisAndIsCancelledFalse(thesis)
                .orElseThrow(() -> new ResourceNotFoundException("Не е пронајдена активна одбрана за оваа дипломска работа."));
        return DefenseResponse.from(defense);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DefenseResponse> getAllDefenses(UUID thesisId) {
        Thesis thesis = findThesis(thesisId);
        // Same underlying-thesis authorization as getActiveDefense.
        thesisReadAccessPolicy.requireReadAccess(thesis, securityUtils.getCurrentUser());
        return defenseRepository.findByThesis(thesis)
                .stream()
                .map(DefenseResponse::from)
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

    // A proposal may be submitted:
    //   - the FIRST time, once Student Service has verified defense eligibility
    //     (thesis is PENDING_DEFENSE_SCHEDULING), or
    //   - AGAIN, after a previously scheduled defense was cancelled — the thesis stays
    //     DEFENSE_SCHEDULED (cancellation does not roll status back, matching the existing
    //     cancelDefense contract), but with no active Defense the room/date is effectively
    //     up for a new proposal.
    private void requireEligibleForProposal(Thesis thesis) {
        boolean firstProposal = thesis.getStatus() == ThesisStatus.PENDING_DEFENSE_SCHEDULING;
        boolean rebooking = thesis.getStatus() == ThesisStatus.DEFENSE_SCHEDULED
                && defenseRepository.findByThesisAndIsCancelledFalse(thesis).isEmpty();
        if (!firstProposal && !rebooking) {
            throw new BadRequestException(
                    "Одбрана може да се предложи само откако Студентската служба ќе ги потврди условите за одбрана, "
                            + "или откако претходно закажана одбрана ќе биде откажана. Тековен статус: "
                            + thesis.getStatus());
        }
    }

    // Official faculty procedure: at least 14 FULL days must have passed since the formal
    // thesis application was submitted before the student may request a defense (which, in
    // this codebase, is the operation that follows committee formation/review and defense-
    // eligibility verification — the ONLY remaining student-initiated "request" that leads to
    // the committee/defense process concluding). Enforced here, at the single point where the
    // student submits that request, so it cannot be bypassed by calling the API directly.
    // A null timestamp (should be unreachable once a thesis has reached this stage — every
    // thesis passes through submitApplication, which always stamps it) is treated as NOT
    // eligible rather than silently bypassing the rule, in case of corrupt/legacy data.
    private void requireApplicationAgeMet(Thesis thesis) {
        OffsetDateTime submittedAt = thesis.getApplicationSubmittedAt();
        if (submittedAt == null) {
            throw new BadRequestException(
                    "Недостасува датумот на поднесување на пријавата; одбрана не може да се побара "
                            + "додека формалната пријава не биде правилно поднесена.");
        }

        OffsetDateTime eligibleAt = submittedAt.plusDays(MIN_DAYS_SINCE_APPLICATION);
        OffsetDateTime now = OffsetDateTime.now();
        if (now.isBefore(eligibleAt)) {
            long daysRemaining = ceilDays(Duration.between(now, eligibleAt));
            throw new BadRequestException(
                    "Мора да поминат најмалку " + MIN_DAYS_SINCE_APPLICATION + " дена од поднесувањето на "
                            + "пријавата пред да може да се побара одбрана. Пријавата е поднесена на "
                            + submittedAt + "; ве молиме почекајте уште "
                            + daysRemaining + " ден(ови).");
        }
    }

    // Ceiling day count for a positive duration — used only for the human-readable
    // "please wait N more day(s)" message (e.g. 12 hours remaining reads as "1 more day").
    private static long ceilDays(Duration remaining) {
        long fullDays = remaining.toDays();
        return remaining.minus(Duration.ofDays(fullDays)).isZero() ? fullDays : fullDays + 1;
    }

    private void validateProposalWindow(OffsetDateTime scheduledAt, OffsetDateTime referenceTime) {
        OffsetDateTime earliest = referenceTime.plusDays(MIN_PROPOSAL_DAYS);
        OffsetDateTime latest = referenceTime.plusDays(MAX_PROPOSAL_DAYS);
        if (scheduledAt.isBefore(earliest) || scheduledAt.isAfter(latest)) {
            throw new BadRequestException(
                    "Предложениот датум на одбраната мора да биде помеѓу " + MIN_PROPOSAL_DAYS + " и "
                            + MAX_PROPOSAL_DAYS + " дена по датумот на барањето (дозволен период: од "
                            + earliest + " до " + latest + ").");
        }
    }

    // No-duration point-in-time booking model (see Defense entity — there is no duration
    // field anywhere in the project). The interval-overlap rule therefore degenerates to
    // exact-instant equality in the same room. Only non-cancelled defenses block; a PENDING
    // DefenseRequest never reserves the room by itself.
    private void requireRoomAvailable(String room, OffsetDateTime scheduledAt) {
        boolean conflict = defenseRepository.findByRoomAndIsCancelledFalse(room).stream()
                .anyMatch(d -> d.getScheduledAt().isEqual(scheduledAt));
        if (conflict) {
            throw new BadRequestException(ROOM_CONFLICT_REASON);
        }
    }

    private void transitionStatus(Thesis thesis, ThesisStatus newStatus, User changedBy) {
        ThesisStatus oldStatus = thesis.getStatus();
        thesis.setStatus(newStatus);
        thesisRepository.save(thesis);

        ThesisStatusHistory history = ThesisStatusHistory.builder()
                .thesis(thesis)
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .changedBy(changedBy)
                .build();
        statusHistoryRepository.save(history);
    }
}
