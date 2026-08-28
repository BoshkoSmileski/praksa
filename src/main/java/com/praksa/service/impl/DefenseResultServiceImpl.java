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

    /**
     * Official faculty rule: grade 5 means the thesis was NOT successfully defended.
     * Grades 6-10 are a successful defense. Only MIN_DEFENSE_GRADE is the failing grade —
     * every other valid grade (6-10) archives the thesis exactly as before.
     */
    private static final int FAILING_GRADE = 5;

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

        // ─── Write-side authorization (P1 grading IDOR / BUG-13 + external non-voting rule) ──
        // Recording a grade also archives the thesis, so it must be restricted to a professor
        // who ACTUALLY SITS on THIS thesis's committee AND holds a VOTING seat — never granted
        // by role alone. Merely holding the COMMITTEE role, sitting on some OTHER thesis's
        // committee, being the thesis owner, or knowing the thesis/defense UUID is NOT
        // sufficient. Official faculty procedure additionally excludes the optional external
        // professional: they hold a real seat (and may read/participate) but are explicitly
        // NON-VOTING, so they must never be able to record a defense grade even though they are
        // a genuine committee member. The membership+voting check uses the requested thesis and
        // the authenticated professor, reusing the same repository mechanism as the read-access
        // policy (findByThesisAndProfessor — the row-returning sibling of
        // existsByThesisAndProfessor, needed here because a yes/no answer is not enough: we must
        // also inspect isExternalNonVoting on the actual seat).
        //
        // Committee seats are only ever held by professors (the mentor as MENTOR_MEMBER + two or
        // three FORMAL_MEMBERs, at most one of which is external non-voting). Administrative
        // roles (STUDENT_SERVICE, ARCHIVE) and the STUDENT owner never hold a seat and are
        // therefore denied here — this resolves BUG-13 ("the spec says only the committee
        // grades") together with the previously un-scoped COMMITTEE check.
        //
        // Runs BEFORE the status check and before any DefenseResult save, status transition,
        // history row, archive metadata, or notification — so an unauthorized request (including
        // a genuinely-seated external member) mutates nothing.
        requireVotingCommitteeSeat(thesis, recorder);

        requireStatus(thesis, ThesisStatus.DEFENSE_SCHEDULED);

        Defense defense = defenseRepository.findById(defenseId)
                .orElseThrow(() -> new ResourceNotFoundException("Одбраната не е пронајдена: " + defenseId));

        // Ensure this defense belongs to the correct thesis
        if (!defense.getThesis().getId().equals(thesisId)) {
            throw new BadRequestException("Одбраната не припаѓа на оваа дипломска работа.");
        }

        // Cannot record a result for a cancelled defense
        if (defense.isCancelled()) {
            throw new BadRequestException("Не може да се внесе резултат за откажана одбрана.");
        }

        // Prevent recording a result twice for the same defense
        if (resultRepository.findByDefense(defense).isPresent()) {
            throw new BadRequestException("Веќе е внесен резултат за оваа одбрана.");
        }

        DefenseResult result = DefenseResult.builder()
                .defense(defense)
                .grade(request.getGrade())
                .notes(request.getNotes())
                .recordedBy(recorder)
                .build();

        resultRepository.save(result);

        // ─── Branch on the official faculty outcome rule ──────────────────
        // Grade 5 = defense NOT passed → DEFENSE_FAILED, no archive metadata, one
        // notification. Grades 6-10 = successful defense → ARCHIVED exactly as before.
        if (request.getGrade() == FAILING_GRADE) {
            recordDefenseFailure(thesis, recorder);
        } else {
            recordSuccessfulArchive(thesis, recorder, result);
        }

        return DefenseResultResponse.from(result);
    }

    /**
     * Grade 5 path. The thesis is NOT archived: no registration number, no archive date,
     * no archivedBy — those fields are exclusively populated by {@link #recordSuccessfulArchive}.
     * The DefenseResult (grade = 5) and the Defense/committee history are all preserved
     * untouched; only the status moves, through the same audited transitionStatus() helper.
     * Because DEFENSE_FAILED is excluded from ThesisServiceImpl#isActiveStatus, the student
     * is immediately free to submit a new thesis application (or, if the workflow allows it
     * for the future topic, continue reworking) without being blocked by the one-active-
     * thesis rule.
     */
    private void recordDefenseFailure(Thesis thesis, User recorder) {
        transitionStatus(thesis, ThesisStatus.DEFENSE_FAILED, recorder);

        // Exactly one notification for a failed defense — no THESIS_GRADED / THESIS_ARCHIVED
        // (those are reserved for a successful archive). Primitive String only.
        notificationService.notify(thesis.getStudent(), thesis, NotificationType.DEFENSE_FAILED_CAN_REAPPLY,
                "Одбраната на вашата дипломска работа беше оценета со 5 и не е успешно положена. Може да ја "
                        + "преработите темата или да поднесете нова пријава за дипломска работа согласно факултетската процедура.");
    }

    /**
     * Grades 6-10 path — the pre-existing successful archive behavior, unchanged.
     */
    private void recordSuccessfulArchive(Thesis thesis, User recorder, DefenseResult result) {
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
                "Одбраната на вашата дипломска работа е оценета. Оценка: " + result.getGrade() + ".");
        notificationService.notify(thesis.getStudent(), thesis, NotificationType.THESIS_ARCHIVED,
                "Честитки! Вашата дипломска работа е одбранета и архивирана под регистарски број "
                        + thesis.getArchiveRegistrationNumber() + ".");
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
                .orElseThrow(() -> new ResourceNotFoundException("Одбраната не е пронајдена: " + defenseId));

        if (!defense.getThesis().getId().equals(thesisId)) {
            throw new BadRequestException("Одбраната не припаѓа на оваа дипломска работа.");
        }

        // Grades are sensitive: authorize against the underlying thesis (owner / assigned mentor
        // / seated committee member / STUDENT_SERVICE / ARCHIVE). Knowing the thesis or defense
        // UUID must never expose another student's grade. Mirrors requireRecordAccess.
        thesisReadAccessPolicy.requireReadAccess(defense.getThesis(), securityUtils.getCurrentUser());

        DefenseResult result = resultRepository.findByDefense(defense)
                .orElseThrow(() -> new ResourceNotFoundException("Сè уште нема внесено резултат за оваа одбрана."));

        return DefenseResultResponse.from(result);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] generateRecordPdf(UUID thesisId, UUID defenseId) {
        Defense defense = defenseRepository.findById(defenseId)
                .orElseThrow(() -> new ResourceNotFoundException("Одбраната не е пронајдена: " + defenseId));

        Thesis thesis = defense.getThesis();
        // The defense must belong to the thesis in the path — otherwise the UUIDs are
        // being mixed and matched to probe records; reject rather than serve.
        if (!thesis.getId().equals(thesisId)) {
            throw new BadRequestException("Одбраната не припаѓа на оваа дипломска работа.");
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
                        "Записникот за одбраната е достапен дури откако одбраната ќе биде оценета."));

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
            throw new UnauthorizedException("Немате пристап до овој записник за одбрана.");
        }
    }

    // -------------------------------------------------------------------------
    // PRIVATE HELPERS
    // -------------------------------------------------------------------------

    /**
     * Write-side grading authorization: only a VOTING seated member of THIS thesis's committee
     * may record its defense grade. Uses
     * {@link CommitteeMemberRepository#findByThesisAndProfessor} — the same thesis-scoped
     * mechanism the read-access policy's existence check is built on, but returning the actual
     * seat row so its voting eligibility can be inspected — so grading can never be authorized
     * by global role, by a seat on a different thesis, or by knowledge of the UUID. Because
     * committee seats are held only by professors, this inherently excludes STUDENT_SERVICE,
     * ARCHIVE, and the STUDENT owner (none of whom ever hold a seat). A genuinely seated but
     * external non-voting member (official faculty procedure) is rejected too — holding a seat
     * is necessary but not sufficient; the seat must also be a voting one.
     */
    private void requireVotingCommitteeSeat(Thesis thesis, User recorder) {
        CommitteeMember seat = committeeRepository.findByThesisAndProfessor(thesis, recorder)
                .orElseThrow(() -> new UnauthorizedException(
                        "Само член на комисијата за одбрана на оваа дипломска работа може да ја внесе оценката."));
        if (seat.isExternalNonVoting()) {
            throw new UnauthorizedException(
                    "Надворешниот член на комисијата без право на глас не може да внесе оценка за одбрана.");
        }
    }

    private void validateGrade(Integer grade) {
        if (grade == null || grade < MIN_DEFENSE_GRADE || grade > MAX_DEFENSE_GRADE) {
            throw new BadRequestException(
                    "Оценката за одбрана мора да биде помеѓу " + MIN_DEFENSE_GRADE + " и " + MAX_DEFENSE_GRADE + " (вклучително).");
        }
    }

    private Thesis findThesis(UUID id) {
        return thesisRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Дипломската работа не е пронајдена: " + id));
    }

    private void requireStatus(Thesis thesis, ThesisStatus required) {
        if (thesis.getStatus() != required) {
            throw new BadRequestException(
                    "Невалиден статус. Очекуван: " + required + ", тековен: " + thesis.getStatus());
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
