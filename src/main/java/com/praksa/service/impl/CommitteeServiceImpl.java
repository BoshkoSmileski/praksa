package com.praksa.service.impl;

import com.praksa.dto.committee.CommitteeMemberResponse;
import com.praksa.dto.committee.ProposeCommitteeRequest;
import com.praksa.dto.committee.SubmitReviewNotesRequest;
import com.praksa.exception.BadRequestException;
import com.praksa.exception.ResourceNotFoundException;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.CommitteeMember;
import com.praksa.model.Thesis;
import com.praksa.model.ThesisStatusHistory;
import com.praksa.model.User;
import com.praksa.model.enums.MemberRole;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.CommitteeMemberRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.repository.UserRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.security.ThesisReadAccessPolicy;
import com.praksa.service.CommitteeService;
import com.praksa.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CommitteeServiceImpl implements CommitteeService {

    private final CommitteeMemberRepository committeeRepository;
    private final ThesisRepository thesisRepository;
    private final ThesisStatusHistoryRepository statusHistoryRepository;
    private final UserRepository userRepository;
    private final SecurityUtils securityUtils;
    private final NotificationService notificationService;
    private final ThesisReadAccessPolicy thesisReadAccessPolicy;

    // -------------------------------------------------------------------------
    // STEP 8a: Mentor proposes the committee
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public List<CommitteeMemberResponse> proposeCommittee(UUID thesisId, ProposeCommitteeRequest request) {
        User mentor = securityUtils.getCurrentUser();
        requireRole(mentor, Role.MENTOR);

        Thesis thesis = findThesis(thesisId);
        requireStatus(thesis, ThesisStatus.MENTOR_APPROVED);

        // Only the assigned mentor of this specific thesis can propose
        if (!mentor.getId().equals(thesis.getMentor().getId())) {
            throw new UnauthorizedException("Само назначениот ментор може да предложи комисија.");
        }

        // Prevent re-proposing if committee already exists
        if (committeeRepository.countByThesis(thesis) > 0) {
            throw new BadRequestException("Веќе е предложена комисија за оваа дипломска работа.");
        }

        List<UUID> professorIds = request.getProfessorIds();
        UUID externalId = request.getExternalProfessorId();
        validateProposalComposition(professorIds, externalId);

        List<CommitteeMember> members = new ArrayList<>();

        // 1. Auto-add the mentor as MENTOR_MEMBER.
        //    This is not in the request — it's a business rule enforced here.
        //    The mentor is always a voting member and can never be the external seat, because
        //    the mentor is never present in professorIds (see the duplicate-mentor check below).
        CommitteeMember mentorMember = CommitteeMember.builder()
                .thesis(thesis)
                .professor(mentor)
                .memberRole(MemberRole.MENTOR_MEMBER)
                .proposedBy(mentor)
                .build();
        members.add(committeeRepository.save(mentorMember));

        // 2. Add the 2 or 3 proposed formal members. Exactly one of them (matching
        //    externalId) is flagged as the external non-voting member when 3 are proposed.
        for (UUID professorId : professorIds) {
            User professor = userRepository.findById(professorId)
                    .orElseThrow(() -> new ResourceNotFoundException("Професорот не е пронајден: " + professorId));

            // Must be a mentor/professor role user
            if (professor.getRole() != Role.MENTOR) {
                throw new BadRequestException(
                        professor.getFullName() + " не е професор и не може да биде член на комисијата.");
            }

            // Cannot add the thesis mentor again as a formal member — this also guarantees the
            // mentor can never be marked as the external non-voting member (the mentor is never
            // in professorIds, so externalId can never resolve to the mentor).
            if (professor.getId().equals(mentor.getId())) {
                throw new BadRequestException("Менторот на дипломската работа веќе е член на комисијата и не може повторно да се додаде.");
            }

            // The unique constraint in the DB catches duplicates at the database level,
            // but we check here first to give a clear error message instead of a DB error
            if (committeeRepository.existsByThesisAndProfessor(thesis, professor)) {
                throw new BadRequestException(professor.getFullName() + " веќе е член на оваа комисија.");
            }

            boolean isExternal = professor.getId().equals(externalId);
            CommitteeMember formalMember = CommitteeMember.builder()
                    .thesis(thesis)
                    .professor(professor)
                    .memberRole(MemberRole.FORMAL_MEMBER)
                    .proposedBy(mentor)
                    .isExternalNonVoting(isExternal)
                    .build();
            members.add(committeeRepository.save(formalMember));
        }

        return members.stream().map(CommitteeMemberResponse::from).toList();
    }

    /**
     * Propose-time composition rule (official faculty procedure): 2 proposed professors give a
     * 3-member committee (mentor + 2 voting) with no external member; 3 proposed professors give
     * a 4-member committee (mentor + 2 voting + 1 external non-voting) and the caller MUST
     * designate which one of the 3 is external. Defense-in-depth alongside the DTO's
     * {@code @Size(min=2,max=3)} — a caller cannot mark an arbitrary professor external without
     * that id actually being one of the proposed professors.
     */
    private void validateProposalComposition(List<UUID> professorIds, UUID externalId) {
        int size = professorIds == null ? 0 : professorIds.size();
        if (size == 2) {
            if (externalId != null) {
                throw new BadRequestException(
                        "Комисија со 3 членови (2 дополнителни професори) не може да вклучува надворешен член без право на глас.");
            }
        } else if (size == 3) {
            if (externalId == null) {
                throw new BadRequestException(
                        "Комисија со 4 членови (3 дополнителни професори) мора да определи точно еден надворешен член без право на глас.");
            }
            if (!professorIds.contains(externalId)) {
                throw new BadRequestException("Надворешниот член мора да биде еден од предложените професори.");
            }
        } else {
            // Defense-in-depth: the DTO's @Size(min=2,max=3) already rejects this at the
            // controller boundary via bean validation, but the service layer never relies on
            // bean validation alone for a business rule.
            throw new BadRequestException("Мора да предложите 2 или 3 професори.");
        }
    }

    // -------------------------------------------------------------------------
    // STEP 8b: Admin officially approves and forms the committee
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public List<CommitteeMemberResponse> approveCommittee(UUID thesisId) {
        User admin = securityUtils.getCurrentUser();
        requireRole(admin, Role.STUDENT_SERVICE);

        Thesis thesis = findThesis(thesisId);
        requireStatus(thesis, ThesisStatus.MENTOR_APPROVED);

        // Safety check: ensure 3 or 4 members were proposed before approving.
        // This is the final enforcement of the "3 or 4" composition rule.
        long memberCount = committeeRepository.countByThesis(thesis);
        if (memberCount != 3 && memberCount != 4) {
            throw new BadRequestException(
                    "Комисијата мора да има 3 или 4 членови пред одобрувањето. Тековен број: " + memberCount);
        }

        List<CommitteeMember> members = committeeRepository.findByThesis(thesis);
        validateCommitteeComposition(members, memberCount);

        // Stamp approved_by and approved_at on all members
        for (CommitteeMember m : members) {
            m.setApprovedBy(admin);
            m.setApprovedAt(OffsetDateTime.now());
            committeeRepository.save(m);
        }

        // Advance thesis to COMMITTEE_REVIEW and stamp the start time —
        // the scheduled job uses this to auto-advance after 5 business days.
        thesis.setCommitteeReviewStartedAt(OffsetDateTime.now());
        transitionStatus(thesis, ThesisStatus.COMMITTEE_REVIEW, admin);

        // ─── Notifications (only after the committee is officially formed) ───
        // COMMITTEE_FORMED goes to every seated professor. The thesis mentor holds a
        // MENTOR_MEMBER seat, so iterating `members` already covers the mentor exactly
        // once — no separate mentor notify needed (avoids a duplicate).
        for (CommitteeMember m : members) {
            notificationService.notify(m.getProfessor(), thesis, NotificationType.COMMITTEE_FORMED);
        }
        // The student is informed with a student-appropriate custom message (the default
        // COMMITTEE_FORMED body is written for the seated members).
        notificationService.notify(thesis.getStudent(), thesis, NotificationType.COMMITTEE_FORMED,
                "Формирана е комисијата за одбрана на вашата дипломска работа и започна периодот на разгледување.");

        return members.stream().map(CommitteeMemberResponse::from).toList();
    }

    // -------------------------------------------------------------------------
    // STEP 8c: A committee member submits their review notes
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public CommitteeMemberResponse submitReviewNotes(UUID thesisId, UUID memberId,
                                                      SubmitReviewNotesRequest request) {
        User reviewer = securityUtils.getCurrentUser();

        Thesis thesis = findThesis(thesisId);
        requireStatus(thesis, ThesisStatus.COMMITTEE_REVIEW);

        CommitteeMember member = committeeRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Членот на комисијата не е пронајден: " + memberId));

        // Ensure this member belongs to this thesis
        if (!member.getThesis().getId().equals(thesisId)) {
            throw new BadRequestException("Овој член на комисијата не припаѓа на наведената дипломска работа.");
        }

        // Only the professor who IS this committee member can submit their own notes
        if (!member.getProfessor().getId().equals(reviewer.getId())) {
            throw new UnauthorizedException("Можете да поднесете забелешки само за своето членство во комисијата.");
        }

        // Notes can be null (they confirmed with no remarks)
        member.setNotes(request.getNotes());
        committeeRepository.save(member);

        return CommitteeMemberResponse.from(member);
    }

    // -------------------------------------------------------------------------
    // STEP 8d: Admin accepts the committee review → COMMITTEE_ACCEPTED
    // In a full implementation, a scheduled job would auto-advance after 5 business
    // days with no action. Here the admin triggers it manually.
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public void acceptCommitteeReview(UUID thesisId) {
        User admin = securityUtils.getCurrentUser();
        requireRole(admin, Role.STUDENT_SERVICE);

        Thesis thesis = findThesis(thesisId);
        requireStatus(thesis, ThesisStatus.COMMITTEE_REVIEW);

        // Advance through COMMITTEE_ACCEPTED into PENDING_DEFENSE_CHECK in one step.
        // In practice these could be two separate admin actions, but combining them
        // avoids an intermediate status that has no UI actions attached to it.
        transitionStatus(thesis, ThesisStatus.COMMITTEE_ACCEPTED, admin);
        transitionStatus(thesis, ThesisStatus.PENDING_DEFENSE_CHECK, admin);

        // ─── Notifications (only after the review is accepted) ───────────────
        // COMMITTEE_REVIEW_ACCEPTED tells everyone the review is complete and defense
        // scheduling can proceed. The mentor holds a committee seat, so iterating the
        // committee already notifies the mentor exactly once (no duplicate).
        // NOTE: the scheduled auto-accept path (ScheduledTasksService) intentionally uses
        // the distinct COMMITTEE_REVIEW_AUTO_ADVANCED type instead, because that audience
        // needs to know the acceptance was automatic (5 business days of silence).
        notificationService.notify(thesis.getStudent(), thesis, NotificationType.COMMITTEE_REVIEW_ACCEPTED);
        for (CommitteeMember m : committeeRepository.findByThesis(thesis)) {
            notificationService.notify(m.getProfessor(), thesis, NotificationType.COMMITTEE_REVIEW_ACCEPTED);
        }
    }

    // -------------------------------------------------------------------------
    // READ
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<CommitteeMemberResponse> getCommittee(UUID thesisId) {
        Thesis thesis = findThesis(thesisId);
        // Authorize against the underlying thesis: an unrelated COMMITTEE (or any other) user
        // must not be able to inspect another thesis's committee just by knowing its UUID.
        thesisReadAccessPolicy.requireReadAccess(thesis, securityUtils.getCurrentUser());
        return committeeRepository.findByThesis(thesis)
                .stream()
                .map(CommitteeMemberResponse::from)
                .toList();
    }

    // -------------------------------------------------------------------------
    // PRIVATE HELPERS
    // -------------------------------------------------------------------------

    /**
     * Approve-time composition rule (official faculty procedure — authoritative final gate,
     * mirroring the existing "safety check" pattern at this call site): a 3-member committee
     * must have zero external members (everyone votes); a 4-member committee must have exactly
     * one external non-voting member; the mentor's seat must always be a voting seat. This is
     * defense-in-depth against the propose-time validation — it re-derives the rule from the
     * actual persisted seats rather than trusting that proposeCommittee was the only path that
     * ever created them.
     */
    private void validateCommitteeComposition(List<CommitteeMember> members, long memberCount) {
        long externalCount = members.stream().filter(CommitteeMember::isExternalNonVoting).count();

        if (memberCount == 3 && externalCount != 0) {
            throw new BadRequestException(
                    "Комисија со 3 членови не може да вклучува надворешен член без право на глас. Пронајдени: " + externalCount);
        }
        if (memberCount == 4 && externalCount != 1) {
            throw new BadRequestException(
                    "Комисија со 4 членови мора да има точно 1 надворешен член без право на глас. Пронајдени: " + externalCount);
        }

        boolean mentorIsVoting = members.stream()
                .filter(m -> m.getMemberRole() == MemberRole.MENTOR_MEMBER)
                .anyMatch(m -> !m.isExternalNonVoting());
        if (!mentorIsVoting) {
            throw new BadRequestException("Менторот мора да остане член на комисијата со право на глас.");
        }
    }

    private Thesis findThesis(UUID id) {
        return thesisRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Дипломската работа не е пронајдена: " + id));
    }

    private void requireRole(User user, Role required) {
        if (user.getRole() != required) {
            throw new UnauthorizedException("Оваа акција бара улога: " + required);
        }
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
