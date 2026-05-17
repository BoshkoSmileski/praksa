package com.praksa.service.impl;

import com.praksa.dto.defense.DefenseResponse;
import com.praksa.dto.defense.ScheduleDefenseRequest;
import com.praksa.exception.BadRequestException;
import com.praksa.exception.ResourceNotFoundException;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.Defense;
import com.praksa.model.Thesis;
import com.praksa.model.ThesisStatusHistory;
import com.praksa.model.User;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.DefenseRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.service.DefenseService;
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
    private final SecurityUtils securityUtils;

    // -------------------------------------------------------------------------
    // SCHEDULE
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public DefenseResponse scheduleDefense(UUID thesisId, ScheduleDefenseRequest request) {
        User mentor = securityUtils.getCurrentUser();
        requireRole(mentor, Role.MENTOR);

        Thesis thesis = findThesis(thesisId);

        // Mentor can schedule when thesis is PENDING_DEFENSE_CHECK (first time)
        // or DEFENSE_SCHEDULED (rescheduling after cancellation)
        if (thesis.getStatus() != ThesisStatus.PENDING_DEFENSE_CHECK
                && thesis.getStatus() != ThesisStatus.DEFENSE_SCHEDULED) {
            throw new BadRequestException(
                    "Defense can only be scheduled when thesis is PENDING_DEFENSE_CHECK or DEFENSE_SCHEDULED");
        }

        // Only the assigned mentor of this thesis can schedule
        if (!mentor.getId().equals(thesis.getMentor().getId())) {
            throw new UnauthorizedException("Only the assigned mentor can schedule the defense");
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
        if (thesis.getStatus() == ThesisStatus.PENDING_DEFENSE_CHECK) {
            transitionStatus(thesis, ThesisStatus.DEFENSE_SCHEDULED, mentor);
        }
        // If rescheduling after cancellation, the status is already DEFENSE_SCHEDULED — no change needed

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

        // Mark cancelled — thesis status does NOT go backwards.
        // The workflow stays at DEFENSE_SCHEDULED.
        // The mentor will call scheduleDefense() again to reschedule.
        defense.setCancelled(true);
        defense.setCancelledBy(caller);
        defense.setCancelledAt(OffsetDateTime.now());
        defenseRepository.save(defense);

        return DefenseResponse.from(defense);
    }

    // -------------------------------------------------------------------------
    // READ
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public DefenseResponse getActiveDefense(UUID thesisId) {
        Thesis thesis = findThesis(thesisId);
        Defense defense = defenseRepository.findByThesisAndIsCancelledFalse(thesis)
                .orElseThrow(() -> new ResourceNotFoundException("No active defense found for this thesis"));
        return DefenseResponse.from(defense);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DefenseResponse> getAllDefenses(UUID thesisId) {
        Thesis thesis = findThesis(thesisId);
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
