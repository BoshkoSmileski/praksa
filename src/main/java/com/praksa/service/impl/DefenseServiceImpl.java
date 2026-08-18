package com.praksa.service.impl;

import com.praksa.dto.defense.DefenseResponse;
import com.praksa.dto.defense.ScheduleDefenseRequest;
import com.praksa.dto.thesis.ThesisResponse;
import com.praksa.exception.BadRequestException;
import com.praksa.exception.ResourceNotFoundException;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.CommitteeMember;
import com.praksa.model.Defense;
import com.praksa.model.Thesis;
import com.praksa.model.ThesisStatusHistory;
import com.praksa.model.User;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.CommitteeMemberRepository;
import com.praksa.repository.DefenseRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.security.ThesisReadAccessPolicy;
import com.praksa.service.DefenseService;
import com.praksa.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DefenseServiceImpl implements DefenseService {

    private final DefenseRepository defenseRepository;
    private final ThesisRepository thesisRepository;
    private final ThesisStatusHistoryRepository statusHistoryRepository;
    private final CommitteeMemberRepository committeeRepository;
    private final SecurityUtils securityUtils;
    private final NotificationService notificationService;
    private final ThesisReadAccessPolicy thesisReadAccessPolicy;

    // -------------------------------------------------------------------------
    // REQUEST (student-initiated) — roadmap Item #6, gated by Item #8
    //
    // The STUDENT signals they are ready to defend. This does NOT create a Defense
    // row and does NOT pick a room/date/time — it only tells STUDENT_SERVICE the
    // student is ready so they can schedule the real event.
    //
    // Since Item #8, the transition into PENDING_DEFENSE_SCHEDULING is owned by the
    // explicit Student Service eligibility verification (verifyDefenseEligibility),
    // NOT by this request. A student may therefore only request AFTER that check has
    // passed — i.e. when the thesis is already PENDING_DEFENSE_SCHEDULING. While the
    // thesis is still PENDING_DEFENSE_CHECK (eligibility not yet verified) the request
    // is rejected. The request itself does not change the status; it just fires the
    // DEFENSE_REQUESTED notification to Student Service.
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public ThesisResponse requestDefense(UUID thesisId) {
        User student = securityUtils.getCurrentUser();
        requireRole(student, Role.STUDENT);

        Thesis thesis = findThesis(thesisId);

        // Student must own this thesis
        if (!thesis.getStudent().getId().equals(student.getId())) {
            throw new UnauthorizedException("You do not own this thesis");
        }

        // A defense can only be requested AFTER Student Service has explicitly verified
        // the student's defense eligibility (Item #8), which moves the thesis to
        // PENDING_DEFENSE_SCHEDULING. Before that (still PENDING_DEFENSE_CHECK) the
        // student cannot request — they must wait for the eligibility check.
        if (thesis.getStatus() != ThesisStatus.PENDING_DEFENSE_SCHEDULING) {
            throw new BadRequestException(
                    "A defense can only be requested after Student Service has verified defense eligibility "
                            + "(thesis in PENDING_DEFENSE_SCHEDULING). Current: " + thesis.getStatus());
        }

        // No status change: the thesis is already awaiting scheduling. The request is a
        // signal that the student is ready; STUDENT_SERVICE sets the concrete date via
        // scheduleDefense. No Defense row is created here.
        notificationService.notifyRole(Role.STUDENT_SERVICE, thesis, NotificationType.DEFENSE_REQUESTED);

        return ThesisResponse.from(thesis);
    }

    // -------------------------------------------------------------------------
    // SCHEDULE (STUDENT_SERVICE) — roadmap Item #6
    //
    // Only STUDENT_SERVICE schedules the defense, after a student request
    // (PENDING_DEFENSE_SCHEDULING → DEFENSE_SCHEDULED) or when rescheduling an
    // existing defense (already DEFENSE_SCHEDULED). Mentors can NOT schedule.
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public DefenseResponse scheduleDefense(UUID thesisId, ScheduleDefenseRequest request) {
        User service = securityUtils.getCurrentUser();
        requireRole(service, Role.STUDENT_SERVICE);

        Thesis thesis = findThesis(thesisId);

        // STUDENT_SERVICE can schedule when a student has requested a defense
        // (PENDING_DEFENSE_SCHEDULING, first time) or when rescheduling after a
        // cancellation (DEFENSE_SCHEDULED). Scheduling an arbitrary thesis that is
        // NOT awaiting scheduling is rejected — this also closes the old path where
        // a mentor could create the first defense straight from PENDING_DEFENSE_CHECK.
        if (thesis.getStatus() != ThesisStatus.PENDING_DEFENSE_SCHEDULING
                && thesis.getStatus() != ThesisStatus.DEFENSE_SCHEDULED) {
            throw new BadRequestException(
                    "Defense can only be scheduled when thesis is PENDING_DEFENSE_SCHEDULING or DEFENSE_SCHEDULED");
        }

        // If there's already an active defense and it's not cancelled, refuse.
        // The user must cancel first, then reschedule.
        defenseRepository.findByThesisAndIsCancelledFalse(thesis).ifPresent(existing -> {
            throw new BadRequestException(
                    "An active defense already exists. Cancel it before scheduling a new one.");
        });

        Defense defense = Defense.builder()
                .thesis(thesis)
                .room(request.getRoom())
                .scheduledAt(request.getScheduledAt())
                .isCancelled(false)
                .build();

        defenseRepository.save(defense);

        // Advance status only if this is the first scheduling
        if (thesis.getStatus() == ThesisStatus.PENDING_DEFENSE_SCHEDULING) {
            transitionStatus(thesis, ThesisStatus.DEFENSE_SCHEDULED, service);
        }
        // If rescheduling after cancellation, the status is already DEFENSE_SCHEDULED — no change needed

        // ─── Notifications (only after the defense row is persisted) ─────────
        // Custom message carries the concrete room + time (primitive String — safe across
        // the async boundary). The mentor holds a committee seat, so iterating the committee
        // notifies the mentor exactly once (satisfies "notify the mentor" without a duplicate).
        String details = "Defense scheduled in room " + defense.getRoom()
                + " at " + defense.getScheduledAt() + ".";
        notificationService.notify(thesis.getStudent(), thesis, NotificationType.DEFENSE_SCHEDULED, details);
        for (CommitteeMember m : committeeRepository.findByThesis(thesis)) {
            notificationService.notify(m.getProfessor(), thesis, NotificationType.DEFENSE_SCHEDULED, details);
        }

        return DefenseResponse.from(defense);
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
            throw new UnauthorizedException("Only the thesis student or mentor can cancel a defense");
        }

        Defense defense = defenseRepository.findByThesisAndIsCancelledFalse(thesis)
                .orElseThrow(() -> new ResourceNotFoundException("No active defense found for this thesis"));

        // Capture the details before mutating — used in the notification message.
        String room = defense.getRoom();
        OffsetDateTime scheduledAt = defense.getScheduledAt();

        // Mark cancelled — thesis status does NOT go backwards.
        // The workflow stays at DEFENSE_SCHEDULED.
        // STUDENT_SERVICE will call scheduleDefense() again to reschedule.
        defense.setCancelled(true);
        defense.setCancelledBy(caller);
        defense.setCancelledAt(OffsetDateTime.now());
        defenseRepository.save(defense);

        // ─── Notifications (only after the cancellation is persisted) ────────
        // Custom message states who cancelled and which defense (primitive String).
        // The mentor holds a committee seat, so iterating the committee notifies the
        // mentor exactly once; the student is notified separately (they may be the caller,
        // in which case this doubles as a confirmation).
        String details = "The defense in room " + room + " at " + scheduledAt
                + " was cancelled by the " + caller.getRole() + ". A new date will be scheduled.";
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
                .orElseThrow(() -> new ResourceNotFoundException("No active defense found for this thesis"));
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
                .orElseThrow(() -> new ResourceNotFoundException("Thesis not found: " + id));
    }

    private void requireRole(User user, Role required) {
        if (user.getRole() != required) {
            throw new UnauthorizedException("This action requires role: " + required);
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
