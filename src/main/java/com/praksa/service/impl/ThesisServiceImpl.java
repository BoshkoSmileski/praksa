package com.praksa.service.impl;

import com.praksa.dto.thesis.*;
import com.praksa.exception.BadRequestException;
import com.praksa.exception.ResourceNotFoundException;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.Thesis;
import com.praksa.model.ThesisStatusHistory;
import com.praksa.model.User;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.repository.UserRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.service.NotificationService;
import com.praksa.service.ThesisService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ThesisServiceImpl implements ThesisService {

    private final ThesisRepository thesisRepository;
    private final ThesisStatusHistoryRepository statusHistoryRepository;
    private final UserRepository userRepository;
    private final SecurityUtils securityUtils;
    private final NotificationService notificationService;

    // -------------------------------------------------------------------------
    // STEP 1: Student requests eligibility check
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public ThesisResponse createThesis(CreateThesisRequest request) {
        User student = securityUtils.getCurrentUser();

        // Only students can create a thesis
        requireRole(student, Role.STUDENT);

        // A student can only have one active thesis at a time.
        // We check: does this student have any thesis that is NOT archived?
        boolean hasActive = thesisRepository.findByStudent(student).stream()
                .anyMatch(t -> t.getStatus() != ThesisStatus.ARCHIVED);
        if (hasActive) {
            throw new BadRequestException("You already have an active thesis");
        }

        Thesis thesis = Thesis.builder()
                .student(student)
                .title(request.getTitle())
                .studentComment(request.getStudentComment())
                .status(ThesisStatus.PENDING_ELIGIBILITY_CHECK)
                .build();

        thesisRepository.save(thesis);

        // Every status change MUST be recorded — this is the first entry
        recordStatusChange(thesis, null, ThesisStatus.PENDING_ELIGIBILITY_CHECK, student);

        return ThesisResponse.from(thesis);
    }

    // -------------------------------------------------------------------------
    // STEP 1 result: Admin decides on eligibility
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public ThesisResponse decideEligibility(UUID thesisId, EligibilityDecisionRequest request) {
        User admin = securityUtils.getCurrentUser();
        requireRole(admin, Role.ADMIN);

        Thesis thesis = findThesis(thesisId);
        requireStatus(thesis, ThesisStatus.PENDING_ELIGIBILITY_CHECK);

        ThesisStatus newStatus = request.getApproved()
                ? ThesisStatus.TOPIC_SELECTION
                : ThesisStatus.ELIGIBILITY_REJECTED;

        transitionStatus(thesis, newStatus, admin);

        // Notify the student of the eligibility decision
        NotificationType notifType = request.getApproved()
                ? NotificationType.ELIGIBILITY_APPROVED
                : NotificationType.ELIGIBILITY_REJECTED;
        notificationService.notify(thesis.getStudent(), thesis, notifType);

        return ThesisResponse.from(thesis);
    }

    // -------------------------------------------------------------------------
    // STEP 2: Student selects a mentor and submits request
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public ThesisResponse submitMentorRequest(UUID thesisId, SubmitMentorRequestDto request) {
        User student = securityUtils.getCurrentUser();
        requireRole(student, Role.STUDENT);

        Thesis thesis = findThesis(thesisId);
        requireOwner(thesis, student);

        // Student can only send a mentor request when in TOPIC_SELECTION or MENTOR_REJECTED_TOPIC
        if (thesis.getStatus() != ThesisStatus.TOPIC_SELECTION
                && thesis.getStatus() != ThesisStatus.MENTOR_REJECTED_TOPIC) {
            throw new BadRequestException("Cannot submit mentor request in current status: " + thesis.getStatus());
        }

        User mentor = userRepository.findById(request.getMentorId())
                .orElseThrow(() -> new ResourceNotFoundException("Mentor not found"));

        requireRole(mentor, Role.MENTOR);

        // Business rule: mentor can supervise at most 10 active theses
        long activeMentorCount = thesisRepository.countActiveMentorTheses(mentor);
        if (activeMentorCount >= 10) {
            throw new BadRequestException("This mentor already has 10 active theses and cannot accept more");
        }

        thesis.setMentor(mentor);
        if (request.getStudentComment() != null) {
            thesis.setStudentComment(request.getStudentComment());
        }

        transitionStatus(thesis, ThesisStatus.PENDING_MENTOR_APPROVAL, student);

        // Notify the mentor they have a new topic request waiting
        notificationService.notify(mentor, thesis, NotificationType.MENTOR_REQUEST_RECEIVED);

        return ThesisResponse.from(thesis);
    }

    // -------------------------------------------------------------------------
    // STEP 2 result: Mentor accepts or rejects the topic
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public ThesisResponse decideMentorRequest(UUID thesisId, MentorDecisionRequest request) {
        User mentor = securityUtils.getCurrentUser();
        requireRole(mentor, Role.MENTOR);

        Thesis thesis = findThesis(thesisId);
        requireStatus(thesis, ThesisStatus.PENDING_MENTOR_APPROVAL);

        // Only the assigned mentor of THIS thesis can decide
        if (!mentor.getId().equals(thesis.getMentor().getId())) {
            throw new UnauthorizedException("You are not the assigned mentor for this thesis");
        }

        if (request.getMentorComment() != null) {
            thesis.setMentorComment(request.getMentorComment());
        }

        ThesisStatus newStatus = request.getAccepted()
                ? ThesisStatus.APPLICATION_SUBMITTED
                : ThesisStatus.MENTOR_REJECTED_TOPIC;

        // If rejected, clear the mentor so student can pick another
        if (!request.getAccepted()) {
            thesis.setMentor(null);
        }

        transitionStatus(thesis, newStatus, mentor);

        // Notify the student of the mentor's decision
        NotificationType decisionType = request.getAccepted()
                ? NotificationType.MENTOR_ACCEPTED_TOPIC
                : NotificationType.MENTOR_REJECTED_TOPIC;
        notificationService.notify(thesis.getStudent(), thesis, decisionType);

        return ThesisResponse.from(thesis);
    }

    // -------------------------------------------------------------------------
    // STEP 3: Student submits the formal application
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public ThesisResponse submitApplication(UUID thesisId) {
        User student = securityUtils.getCurrentUser();
        requireRole(student, Role.STUDENT);

        Thesis thesis = findThesis(thesisId);
        requireOwner(thesis, student);
        requireStatus(thesis, ThesisStatus.APPLICATION_SUBMITTED);

        transitionStatus(thesis, ThesisStatus.ADMINISTRATIVE_VALIDATION, student);

        return ThesisResponse.from(thesis);
    }

    // -------------------------------------------------------------------------
    // STEP 4: Admin validates documentation
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public ThesisResponse validateApplication(UUID thesisId) {
        User admin = securityUtils.getCurrentUser();
        requireRole(admin, Role.ADMIN);

        Thesis thesis = findThesis(thesisId);
        requireStatus(thesis, ThesisStatus.ADMINISTRATIVE_VALIDATION);

        transitionStatus(thesis, ThesisStatus.IN_PROGRESS, admin);

        // Notify the student their application passed validation
        notificationService.notify(thesis.getStudent(), thesis, NotificationType.APPLICATION_VALIDATED);

        return ThesisResponse.from(thesis);
    }

    // -------------------------------------------------------------------------
    // STEPS 5–7: Mentor approves the final thesis version
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public ThesisResponse approveFinalThesis(UUID thesisId) {
        User mentor = securityUtils.getCurrentUser();
        requireRole(mentor, Role.MENTOR);

        Thesis thesis = findThesis(thesisId);
        requireStatus(thesis, ThesisStatus.FINAL_SUBMITTED);

        if (!mentor.getId().equals(thesis.getMentor().getId())) {
            throw new UnauthorizedException("You are not the assigned mentor for this thesis");
        }

        transitionStatus(thesis, ThesisStatus.MENTOR_APPROVED, mentor);

        // Notify the student their thesis was approved by the mentor
        notificationService.notify(thesis.getStudent(), thesis, NotificationType.MENTOR_APPROVED_THESIS);

        return ThesisResponse.from(thesis);
    }

    // -------------------------------------------------------------------------
    // READ OPERATIONS
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public ThesisResponse getThesisById(UUID thesisId) {
        // @Transactional(readOnly = true) is important here:
        // It tells Hibernate this is a read-only operation — no dirty checking,
        // slightly faster, and keeps the session open so lazy fields can be accessed
        // safely while we build the DTO.
        Thesis thesis = findThesis(thesisId);
        return ThesisResponse.from(thesis);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ThesisResponse> getMyTheses() {
        User user = securityUtils.getCurrentUser();
        List<Thesis> theses;

        if (user.getRole() == Role.STUDENT) {
            theses = thesisRepository.findByStudent(user);
        } else if (user.getRole() == Role.MENTOR) {
            theses = thesisRepository.findByMentor(user);
        } else {
            // Admins and other roles see all theses
            theses = thesisRepository.findAll();
        }

        // .stream().map(...) converts each entity to a DTO INSIDE the transaction
        // This is critical — if you returned the entity list and mapped outside,
        // you'd get LazyInitializationException when accessing thesis.getStudent()
        return theses.stream()
                .map(ThesisResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ThesisStatusHistoryResponse> getStatusHistory(UUID thesisId) {
        Thesis thesis = findThesis(thesisId);
        return statusHistoryRepository.findByThesisOrderByChangedAtAsc(thesis)
                .stream()
                .map(ThesisStatusHistoryResponse::from)
                .toList();
    }

    // -------------------------------------------------------------------------
    // PRIVATE HELPERS — reusable guard clauses
    // -------------------------------------------------------------------------

    private Thesis findThesis(UUID id) {
        return thesisRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Thesis not found with id: " + id));
    }

    /**
     * Changes the status, saves the thesis, and records the change in history.
     * Every single status change in the system goes through here — never set
     * thesis.setStatus() directly anywhere else.
     */
    private void transitionStatus(Thesis thesis, ThesisStatus newStatus, User changedBy) {
        ThesisStatus oldStatus = thesis.getStatus();
        thesis.setStatus(newStatus);
        thesisRepository.save(thesis);
        recordStatusChange(thesis, oldStatus, newStatus, changedBy);
    }

    private void recordStatusChange(Thesis thesis, ThesisStatus oldStatus,
                                    ThesisStatus newStatus, User changedBy) {
        ThesisStatusHistory history = ThesisStatusHistory.builder()
                .thesis(thesis)
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .changedBy(changedBy)
                .build();
        statusHistoryRepository.save(history);
    }

    private void requireRole(User user, Role required) {
        if (user.getRole() != required) {
            throw new UnauthorizedException("This action requires role: " + required);
        }
    }

    private void requireStatus(Thesis thesis, ThesisStatus required) {
        if (thesis.getStatus() != required) {
            throw new BadRequestException(
                    "Invalid thesis status. Expected: " + required + ", but was: " + thesis.getStatus());
        }
    }

    private void requireOwner(Thesis thesis, User user) {
        if (!thesis.getStudent().getId().equals(user.getId())) {
            throw new UnauthorizedException("You do not own this thesis");
        }
    }
}
