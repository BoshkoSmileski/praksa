package com.praksa.service.impl;

import com.praksa.dto.defense.DefenseResultResponse;
import com.praksa.dto.defense.RecordResultRequest;
import com.praksa.exception.BadRequestException;
import com.praksa.exception.ResourceNotFoundException;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.Defense;
import com.praksa.model.DefenseResult;
import com.praksa.model.Thesis;
import com.praksa.model.ThesisStatusHistory;
import com.praksa.model.User;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.DefenseRepository;
import com.praksa.repository.DefenseResultRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.service.DefenseResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DefenseResultServiceImpl implements DefenseResultService {

    private final DefenseResultRepository resultRepository;
    private final DefenseRepository defenseRepository;
    private final ThesisRepository thesisRepository;
    private final ThesisStatusHistoryRepository statusHistoryRepository;
    private final SecurityUtils securityUtils;

    @Override
    @Transactional
    public DefenseResultResponse recordResult(UUID thesisId, UUID defenseId, RecordResultRequest request) {
        User recorder = securityUtils.getCurrentUser();

        // Only committee members or admin can record a grade.
        // We check role here; a stricter check would verify they're on this thesis's committee.
        if (recorder.getRole() != Role.COMMITTEE && recorder.getRole() != Role.ADMIN) {
            throw new UnauthorizedException("Only committee members can record a defense result");
        }

        Thesis thesis = findThesis(thesisId);
        requireStatus(thesis, ThesisStatus.DEFENSE_SCHEDULED);

        Defense defense = defenseRepository.findById(defenseId)
                .orElseThrow(() -> new ResourceNotFoundException("Defense not found: " + defenseId));

        // Ensure this defense belongs to the correct thesis
        if (!defense.getThesis().getId().equals(thesisId)) {
            throw new BadRequestException("Defense does not belong to this thesis");
        }

        // Cannot record a result for a cancelled defense
        if (defense.isCancelled()) {
            throw new BadRequestException("Cannot record a result for a cancelled defense");
        }

        // Prevent recording a result twice for the same defense
        if (resultRepository.findByDefense(defense).isPresent()) {
            throw new BadRequestException("A result has already been recorded for this defense");
        }

        DefenseResult result = DefenseResult.builder()
                .defense(defense)
                .grade(request.getGrade())
                .notes(request.getNotes())
                .recordedBy(recorder)
                .build();

        resultRepository.save(result);

        // Archive the thesis.
        // This is the critical state change: once ARCHIVED, this thesis no longer
        // counts toward the mentor's active thesis limit (our query filters by != ARCHIVED).
        transitionStatus(thesis, ThesisStatus.ARCHIVED, recorder);

        return DefenseResultResponse.from(result);
    }

    @Override
    @Transactional(readOnly = true)
    public DefenseResultResponse getResult(UUID thesisId, UUID defenseId) {
        Defense defense = defenseRepository.findById(defenseId)
                .orElseThrow(() -> new ResourceNotFoundException("Defense not found: " + defenseId));

        if (!defense.getThesis().getId().equals(thesisId)) {
            throw new BadRequestException("Defense does not belong to this thesis");
        }

        DefenseResult result = resultRepository.findByDefense(defense)
                .orElseThrow(() -> new ResourceNotFoundException("No result recorded yet for this defense"));

        return DefenseResultResponse.from(result);
    }

    // -------------------------------------------------------------------------
    // PRIVATE HELPERS
    // -------------------------------------------------------------------------

    private Thesis findThesis(UUID id) {
        return thesisRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Thesis not found: " + id));
    }

    private void requireStatus(Thesis thesis, ThesisStatus required) {
        if (thesis.getStatus() != required) {
            throw new BadRequestException(
                    "Invalid status. Expected: " + required + ", current: " + thesis.getStatus());
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
