package com.praksa.service.impl;

import com.praksa.dto.thesis.*;
import com.praksa.exception.BadRequestException;
import com.praksa.exception.ResourceNotFoundException;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.Thesis;
import com.praksa.model.ThesisStatusHistory;
import com.praksa.model.User;
import com.praksa.model.enums.MentorDecision;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.repository.UserRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.service.ApplicationPdfService;
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
    private final ApplicationPdfService applicationPdfService;

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
        requireRole(admin, Role.STUDENT_SERVICE);

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
    // STEP 2 result: Mentor accepts, rejects, or requests changes
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

        // Comment is REQUIRED when requesting changes (student needs to know what to change)
        if (request.getDecision() == MentorDecision.REQUEST_CHANGES
                && (request.getMentorComment() == null || request.getMentorComment().isBlank())) {
            throw new BadRequestException("A comment is required when requesting changes");
        }

        if (request.getMentorComment() != null && !request.getMentorComment().isBlank()) {
            thesis.setMentorComment(request.getMentorComment().trim());
        }

        switch (request.getDecision()) {
            case ACCEPT -> {
                transitionStatus(thesis, ThesisStatus.APPLICATION_SUBMITTED, mentor);
                notificationService.notify(thesis.getStudent(), thesis, NotificationType.MENTOR_ACCEPTED_TOPIC);
            }
            case REJECT -> {
                // Clear the mentor so the slot is freed; student picks another
                thesis.setMentor(null);
                transitionStatus(thesis, ThesisStatus.MENTOR_REJECTED_TOPIC, mentor);
                notificationService.notify(thesis.getStudent(), thesis, NotificationType.MENTOR_REJECTED_TOPIC);
            }
            case REQUEST_CHANGES -> {
                // Mentor stays assigned; mentor's slot remains consumed (counts toward 10-active)
                // Increment revision counter so the student / list views can show the cycle count
                thesis.setRevisionCount(thesis.getRevisionCount() + 1);
                transitionStatus(thesis, ThesisStatus.MENTOR_REQUESTED_CHANGES, mentor);
                notificationService.notify(thesis.getStudent(), thesis, NotificationType.MENTOR_REQUESTED_CHANGES);
            }
        }

        return ThesisResponse.from(thesis);
    }

    // -------------------------------------------------------------------------
    // STEP 2 (revision loop): Student revises title/idea and resubmits
    //
    // Same thesis row reused. Mentor stays assigned. Eligibility/archive/service
    // validations are NOT replayed. The status loop is bounded only by the
    // student giving up (and resetting to MENTOR_REJECTED_TOPIC manually if needed —
    // but that's not part of this flow).
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public ThesisResponse reviseProposal(UUID thesisId, ReviseProposalRequest request) {
        User student = securityUtils.getCurrentUser();
        requireRole(student, Role.STUDENT);

        Thesis thesis = findThesis(thesisId);
        requireOwner(thesis, student);
        requireStatus(thesis, ThesisStatus.MENTOR_REQUESTED_CHANGES);

        // Sanity check — mentor should still be assigned (request-changes never clears it)
        if (thesis.getMentor() == null) {
            throw new BadRequestException("Mentor is no longer assigned; cannot revise to same mentor");
        }

        thesis.setTitle(request.getTitle().trim());
        if (request.getStudentComment() != null) {
            thesis.setStudentComment(request.getStudentComment().trim());
        }

        transitionStatus(thesis, ThesisStatus.PENDING_MENTOR_APPROVAL, student);

        // Tell the mentor a new revision is waiting
        notificationService.notify(thesis.getMentor(), thesis, NotificationType.STUDENT_RESUBMITTED_PROPOSAL);

        return ThesisResponse.from(thesis);
    }

    // -------------------------------------------------------------------------
    // STEP 3: Student submits (or resubmits) the formal application
    //
    // Allowed source statuses:
    //   APPLICATION_SUBMITTED              → fresh submission
    //   APPLICATION_REJECTED_BY_ARCHIVE    → resubmit after archive rejection
    //   APPLICATION_REJECTED_BY_SERVICE    → resubmit after service rejection
    // In all cases the next status is PENDING_ARCHIVE_VALIDATION (always restart
    // at archive — keeps the invariant "archive always sees it first").
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public ThesisResponse submitApplication(UUID thesisId) {
        User student = securityUtils.getCurrentUser();
        requireRole(student, Role.STUDENT);

        Thesis thesis = findThesis(thesisId);
        requireOwner(thesis, student);

        ThesisStatus s = thesis.getStatus();
        if (s != ThesisStatus.APPLICATION_SUBMITTED
                && s != ThesisStatus.APPLICATION_REJECTED_BY_ARCHIVE
                && s != ThesisStatus.APPLICATION_REJECTED_BY_SERVICE) {
            throw new BadRequestException(
                    "Application can only be submitted from APPLICATION_SUBMITTED or a rejection status. Current: " + s);
        }

        // Generate the application PDF artifact — archive/service review this
        String pdfPath = applicationPdfService.generate(thesis);
        thesis.setApplicationPdfPath(pdfPath);

        transitionStatus(thesis, ThesisStatus.PENDING_ARCHIVE_VALIDATION, student);

        // Tell all archive users a new application is waiting
        notificationService.notifyRole(Role.ARCHIVE, thesis, NotificationType.APPLICATION_PENDING_ARCHIVE);

        return ThesisResponse.from(thesis);
    }

    // -------------------------------------------------------------------------
    // STEP 4a: Archive validates documentation
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public ThesisResponse archiveValidate(UUID thesisId, ValidationDecisionRequest request) {
        User archiveUser = securityUtils.getCurrentUser();
        requireRole(archiveUser, Role.ARCHIVE);

        Thesis thesis = findThesis(thesisId);
        requireStatus(thesis, ThesisStatus.PENDING_ARCHIVE_VALIDATION);

        // On rejection a comment is mandatory (so student knows what to fix)
        if (!request.getApproved() && (request.getComment() == null || request.getComment().isBlank())) {
            throw new BadRequestException("A rejection comment is required");
        }

        // Persist the note (either an approval note or a rejection reason)
        if (request.getComment() != null && !request.getComment().isBlank()) {
            thesis.setArchiveComment(request.getComment().trim());
        }

        if (request.getApproved()) {
            transitionStatus(thesis, ThesisStatus.PENDING_SERVICE_VALIDATION, archiveUser);
            // Tell service users it's their turn
            notificationService.notifyRole(Role.STUDENT_SERVICE, thesis, NotificationType.APPLICATION_PENDING_SERVICE);
        } else {
            transitionStatus(thesis, ThesisStatus.APPLICATION_REJECTED_BY_ARCHIVE, archiveUser);
            // Tell student about the rejection
            notificationService.notify(thesis.getStudent(), thesis, NotificationType.APPLICATION_REJECTED_BY_ARCHIVE);
        }

        return ThesisResponse.from(thesis);
    }

    // -------------------------------------------------------------------------
    // STEP 4b: Student Service validates documentation
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public ThesisResponse serviceValidate(UUID thesisId, ValidationDecisionRequest request) {
        User serviceUser = securityUtils.getCurrentUser();
        requireRole(serviceUser, Role.STUDENT_SERVICE);

        Thesis thesis = findThesis(thesisId);
        requireStatus(thesis, ThesisStatus.PENDING_SERVICE_VALIDATION);

        if (!request.getApproved() && (request.getComment() == null || request.getComment().isBlank())) {
            throw new BadRequestException("A rejection comment is required");
        }

        if (request.getComment() != null && !request.getComment().isBlank()) {
            thesis.setServiceComment(request.getComment().trim());
        }

        if (request.getApproved()) {
            transitionStatus(thesis, ThesisStatus.IN_PROGRESS, serviceUser);
            notificationService.notify(thesis.getStudent(), thesis, NotificationType.APPLICATION_VALIDATED);
        } else {
            transitionStatus(thesis, ThesisStatus.APPLICATION_REJECTED_BY_SERVICE, serviceUser);
            notificationService.notify(thesis.getStudent(), thesis, NotificationType.APPLICATION_REJECTED_BY_SERVICE);
        }

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
        } else if (user.getRole() == Role.ARCHIVE) {
            // Archive sees only theses in their queue (awaiting validation) + already archived
            theses = thesisRepository.findAll().stream()
                    .filter(t -> t.getStatus() == ThesisStatus.PENDING_ARCHIVE_VALIDATION
                              || t.getStatus() == ThesisStatus.ARCHIVED)
                    .toList();
        } else {
            // Student Service and Committee see all theses
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
    public org.springframework.core.io.Resource downloadApplicationPdf(UUID thesisId) {
        Thesis thesis = findThesis(thesisId);
        if (thesis.getApplicationPdfPath() == null) {
            throw new ResourceNotFoundException("No application PDF generated yet for this thesis");
        }
        // Access: student owner, assigned mentor, any STUDENT_SERVICE/ARCHIVE/COMMITTEE user
        User user = securityUtils.getCurrentUser();
        boolean allowed =
                (user.getRole() == Role.STUDENT && thesis.getStudent().getId().equals(user.getId())) ||
                (user.getRole() == Role.MENTOR  && thesis.getMentor() != null && thesis.getMentor().getId().equals(user.getId())) ||
                (user.getRole() == Role.STUDENT_SERVICE) ||
                (user.getRole() == Role.ARCHIVE) ||
                (user.getRole() == Role.COMMITTEE);
        if (!allowed) {
            throw new UnauthorizedException("You do not have access to this application PDF");
        }
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(thesis.getApplicationPdfPath()).toAbsolutePath().normalize();
            org.springframework.core.io.UrlResource r = new org.springframework.core.io.UrlResource(p.toUri());
            if (!r.exists() || !r.isReadable()) {
                throw new ResourceNotFoundException("Application PDF file is missing on disk");
            }
            return r;
        } catch (java.net.MalformedURLException e) {
            throw new RuntimeException("Malformed application PDF path", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ThesisResponse findByRegistrationNumber(String registrationNumber) {
        Thesis thesis = thesisRepository.findByArchiveRegistrationNumber(registrationNumber.trim())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No thesis found with registration number: " + registrationNumber));
        return ThesisResponse.from(thesis);
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
