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
            throw new UnauthorizedException("Only the assigned mentor can propose a committee");
        }

        // Prevent re-proposing if committee already exists
        if (committeeRepository.countByThesis(thesis) > 0) {
            throw new BadRequestException("A committee has already been proposed for this thesis");
        }

        List<CommitteeMember> members = new ArrayList<>();

        // 1. Auto-add the mentor as MENTOR_MEMBER.
        //    This is not in the request — it's a business rule enforced here.
        CommitteeMember mentorMember = CommitteeMember.builder()
                .thesis(thesis)
                .professor(mentor)
                .memberRole(MemberRole.MENTOR_MEMBER)
                .proposedBy(mentor)
                .build();
        members.add(committeeRepository.save(mentorMember));

        // 2. Add the 2 proposed formal members
        for (UUID professorId : request.getProfessorIds()) {
            User professor = userRepository.findById(professorId)
                    .orElseThrow(() -> new ResourceNotFoundException("Professor not found: " + professorId));

            // Must be a mentor/professor role user
            if (professor.getRole() != Role.MENTOR) {
                throw new BadRequestException(
                        professor.getFullName() + " is not a professor and cannot be a committee member");
            }

            // Cannot add the thesis mentor again as a formal member
            if (professor.getId().equals(mentor.getId())) {
                throw new BadRequestException("The thesis mentor is already a committee member and cannot be added again");
            }

            // The unique constraint in the DB catches duplicates at the database level,
            // but we check here first to give a clear error message instead of a DB error
            if (committeeRepository.existsByThesisAndProfessor(thesis, professor)) {
                throw new BadRequestException(professor.getFullName() + " is already on this committee");
            }

            CommitteeMember formalMember = CommitteeMember.builder()
                    .thesis(thesis)
                    .professor(professor)
                    .memberRole(MemberRole.FORMAL_MEMBER)
                    .proposedBy(mentor)
                    .build();
            members.add(committeeRepository.save(formalMember));
        }

        return members.stream().map(CommitteeMemberResponse::from).toList();
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

        // Safety check: ensure exactly 3 members were proposed before approving.
        // This is the final enforcement of the "exactly 3" rule.
        long memberCount = committeeRepository.countByThesis(thesis);
        if (memberCount != 3) {
            throw new BadRequestException(
                    "Committee must have exactly 3 members before approval. Current count: " + memberCount);
        }

        // Stamp approved_by and approved_at on all members
        List<CommitteeMember> members = committeeRepository.findByThesis(thesis);
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
                "Your thesis defense committee has been formed and the review period has started.");

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
                .orElseThrow(() -> new ResourceNotFoundException("Committee member not found: " + memberId));

        // Ensure this member belongs to this thesis
        if (!member.getThesis().getId().equals(thesisId)) {
            throw new BadRequestException("This committee member does not belong to the specified thesis");
        }

        // Only the professor who IS this committee member can submit their own notes
        if (!member.getProfessor().getId().equals(reviewer.getId())) {
            throw new UnauthorizedException("You can only submit notes for your own committee membership");
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

    private Thesis findThesis(UUID id) {
        return thesisRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Thesis not found: " + id));
    }

    private void requireRole(User user, Role required) {
        if (user.getRole() != required) {
            throw new UnauthorizedException("This action requires role: " + required);
        }
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
