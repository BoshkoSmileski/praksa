package com.praksa.service.impl;

import com.praksa.dto.defense.DefenseResultResponse;
import com.praksa.dto.defense.RecordResultRequest;
import com.praksa.exception.BadRequestException;
import com.praksa.exception.ResourceNotFoundException;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.CommitteeMember;
import com.praksa.model.Defense;
import com.praksa.model.DefenseResult;
import com.praksa.model.Thesis;
import com.praksa.model.ThesisStatusHistory;
import com.praksa.model.User;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.CommitteeMemberRepository;
import com.praksa.repository.DefenseRepository;
import com.praksa.repository.DefenseResultRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.security.ThesisReadAccessPolicy;
import com.praksa.service.DefenseRecordPdfService;
import com.praksa.service.DefenseResultService;
import com.praksa.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DefenseResultServiceImpl implements DefenseResultService {

    private final DefenseResultRepository resultRepository;
    private final DefenseRepository defenseRepository;
    private final ThesisRepository thesisRepository;
    private final ThesisStatusHistoryRepository statusHistoryRepository;
    private final CommitteeMemberRepository committeeRepository;
    private final SecurityUtils securityUtils;
    private final NotificationService notificationService;
    private final DefenseRecordPdfService recordPdfService;
    private final ThesisReadAccessPolicy thesisReadAccessPolicy;

    /** A valid defense grade is strictly 5 through 10 inclusive. */
    private static final int MIN_DEFENSE_GRADE = 5;
    private static final int MAX_DEFENSE_GRADE = 10;

    @Override
    @Transactional
    public DefenseResultResponse recordResult(UUID thesisId, UUID defenseId, RecordResultRequest request) {
        // Defense-in-depth: RecordResultRequest already enforces @NotNull/@Min(5)/@Max(10)
        // at the controller boundary (@Valid). recordResult is also public on the service
        // interface, so guard here too — no internal caller can bypass bean validation and
        // archive a thesis on a null/out-of-range grade. Runs first, before any DB write,
        // status transition, or notification.
        validateGrade(request.getGrade());

        User recorder = securityUtils.getCurrentUser();

        Thesis thesis = findThesis(thesisId);

        // ─── Write-side authorization (P1 grading IDOR / BUG-13) ──────────────
        // Recording a grade also archives the thesis, so it must be restricted to a professor
        // who ACTUALLY SITS on THIS thesis's committee — never granted by role alone. Merely
        // holding the COMMITTEE role, sitting on some OTHER thesis's committee, being the
        // thesis owner, or knowing the thesis/defense UUID is NOT sufficient. The membership
        // check uses the requested thesis and the authenticated professor, reusing the same
        // repository mechanism as the read-access policy (existsByThesisAndProfessor).
        //
        // Committee seats are only ever held by professors (the mentor as MENTOR_MEMBER + two
        // FORMAL_MEMBERs). Administrative roles (STUDENT_SERVICE, ARCHIVE) and the STUDENT owner
        // never hold a seat and are therefore denied here — this resolves BUG-13 ("the spec says
        // only the committee grades") together with the previously un-scoped COMMITTEE check.
        //
        // Runs BEFORE the status check and before any DefenseResult save, status transition,
        // history row, archive metadata, or notification — so an unauthorized request mutates
        // nothing.
        requireCommitteeSeat(thesis, recorder);

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

        // ─── Assign archive metadata ──────────────────────────────────────
        // Done BEFORE the status transition so the saved thesis has all fields populated.
        // Once set, the registration number is immutable (no update endpoint exposes it).
        OffsetDateTime now = OffsetDateTime.now();
        thesis.setArchiveRegistrationNumber(generateRegistrationNumber(now.getYear()));
        thesis.setArchiveDate(now);
        thesis.setArchivedBy(recorder);
        // archiveNotes intentionally left null — can be set later via a future endpoint.

        // Archive the thesis.
        // This is the critical state change: once ARCHIVED, this thesis no longer
        // counts toward the mentor's active thesis limit (our query filters by != ARCHIVED).
        transitionStatus(thesis, ThesisStatus.ARCHIVED, recorder);

        // ─── Notifications (only after grade saved + thesis archived) ────────
        // Both go to the student. THESIS_GRADED carries the concrete grade; THESIS_ARCHIVED
        // carries the registration number — both primitive Strings, safe across the async
        // boundary. This is the single place these two events occur, so no duplicates.
        notificationService.notify(thesis.getStudent(), thesis, NotificationType.THESIS_GRADED,
                "Your thesis defense has been graded. Grade: " + result.getGrade() + ".");
        notificationService.notify(thesis.getStudent(), thesis, NotificationType.THESIS_ARCHIVED,
                "Congratulations! Your thesis has been defended and archived under registration number "
                        + thesis.getArchiveRegistrationNumber() + ".");

        return DefenseResultResponse.from(result);
    }

    /**
     * Generates a registration number in the form DT-YYYY-NNNN.
     * The sequence resets per calendar year. Uniqueness is also enforced at the DB level —
     * if two concurrent archivings race for the same number, the loser's transaction rolls back.
     */
    private String generateRegistrationNumber(int year) {
        String prefix = "DT-" + year + "-";
        long count = thesisRepository.countByArchiveRegistrationNumberStartingWith(prefix);
        return String.format("%s%04d", prefix, count + 1);
    }

    @Override
    @Transactional(readOnly = true)
    public DefenseResultResponse getResult(UUID thesisId, UUID defenseId) {
        Defense defense = defenseRepository.findById(defenseId)
                .orElseThrow(() -> new ResourceNotFoundException("Defense not found: " + defenseId));

        if (!defense.getThesis().getId().equals(thesisId)) {
            throw new BadRequestException("Defense does not belong to this thesis");
        }

        // Grades are sensitive: authorize against the underlying thesis (owner / assigned mentor
        // / seated committee member / STUDENT_SERVICE / ARCHIVE). Knowing the thesis or defense
        // UUID must never expose another student's grade. Mirrors requireRecordAccess.
        thesisReadAccessPolicy.requireReadAccess(defense.getThesis(), securityUtils.getCurrentUser());

        DefenseResult result = resultRepository.findByDefense(defense)
                .orElseThrow(() -> new ResourceNotFoundException("No result recorded yet for this defense"));

        return DefenseResultResponse.from(result);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] generateRecordPdf(UUID thesisId, UUID defenseId) {
        Defense defense = defenseRepository.findById(defenseId)
                .orElseThrow(() -> new ResourceNotFoundException("Defense not found: " + defenseId));

        Thesis thesis = defense.getThesis();
        // The defense must belong to the thesis in the path — otherwise the UUIDs are
        // being mixed and matched to probe records; reject rather than serve.
        if (!thesis.getId().equals(thesisId)) {
            throw new BadRequestException("Defense does not belong to this thesis");
        }

        // Server-side authorization is the security boundary (NOT the frontend button).
        // A caller who merely knows the defense UUID must NOT be able to download an
        // unrelated thesis's record.
        requireRecordAccess(thesis);

        // State rule: the record is the record of a *completed, graded* defense. It is only
        // available once a result exists — we never expose a record (with a fake/blank grade)
        // for a merely scheduled defense. Recording a result also archives the thesis, so a
        // present result guarantees the archive registration number is populated too.
        DefenseResult result = resultRepository.findByDefense(defense)
                .orElseThrow(() -> new BadRequestException(
                        "The defense record is available only after the defense has been graded"));

        List<CommitteeMember> committee = committeeRepository.findByThesis(thesis);

        return recordPdfService.generate(thesis, defense, result, committee);
    }

    /**
     * Access to a defense record is limited to parties genuinely related to the thesis:
     * the student owner, the assigned mentor, a professor holding a seat on THIS thesis's
     * committee, and the administrative roles STUDENT_SERVICE and ARCHIVE (who handle the
     * official record). A COMMITTEE-role user who is not on this committee is rejected —
     * this is deliberately stricter than the application-PDF endpoint, which allows any
     * COMMITTEE user (a known over-broad rule tracked separately).
     */
    private void requireRecordAccess(Thesis thesis) {
        User user = securityUtils.getCurrentUser();
        boolean allowed =
                (user.getRole() == Role.STUDENT && thesis.getStudent().getId().equals(user.getId()))
                || (user.getRole() == Role.MENTOR && thesis.getMentor() != null
                        && thesis.getMentor().getId().equals(user.getId()))
                || (user.getRole() == Role.STUDENT_SERVICE)
                || (user.getRole() == Role.ARCHIVE)
                || committeeRepository.existsByThesisAndProfessor(thesis, user);
        if (!allowed) {
            throw new UnauthorizedException("You do not have access to this defense record");
        }
    }

    // -------------------------------------------------------------------------
    // PRIVATE HELPERS
    // -------------------------------------------------------------------------

    /**
     * Write-side grading authorization: only a seated member of THIS thesis's committee may
     * record its defense grade. Uses {@link CommitteeMemberRepository#existsByThesisAndProfessor}
     * — the same thesis-scoped mechanism the read-access policy uses — so grading can never be
     * authorized by global role, by a seat on a different thesis, or by knowledge of the UUID.
     * Because committee seats are held only by professors, this inherently excludes
     * STUDENT_SERVICE, ARCHIVE, and the STUDENT owner (none of whom ever hold a seat).
     */
    private void requireCommitteeSeat(Thesis thesis, User recorder) {
        if (!committeeRepository.existsByThesisAndProfessor(thesis, recorder)) {
            throw new UnauthorizedException(
                    "Only a member of this thesis's defense committee can record the grade");
        }
    }

    private void validateGrade(Integer grade) {
        if (grade == null || grade < MIN_DEFENSE_GRADE || grade > MAX_DEFENSE_GRADE) {
            throw new BadRequestException(
                    "Defense grade must be between " + MIN_DEFENSE_GRADE + " and " + MAX_DEFENSE_GRADE + " (inclusive)");
        }
    }

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
