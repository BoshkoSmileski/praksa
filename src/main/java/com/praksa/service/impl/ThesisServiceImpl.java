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
import com.praksa.security.ThesisReadAccessPolicy;
import com.praksa.service.ApplicationPdfService;
import com.praksa.service.NotificationService;
import com.praksa.service.ThesisService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ThesisServiceImpl implements ThesisService {

    /**
     * Minimum academic credits a student must hold before they may open a thesis
     * application. Enforced server-side in createThesis. Exactly 200 is allowed.
     */
    private static final int REQUIRED_CREDITS_FOR_THESIS = 200;

    private final ThesisRepository thesisRepository;
    private final ThesisStatusHistoryRepository statusHistoryRepository;
    private final UserRepository userRepository;
    private final SecurityUtils securityUtils;
    private final NotificationService notificationService;
    private final ApplicationPdfService applicationPdfService;
    private final ThesisReadAccessPolicy thesisReadAccessPolicy;

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
        // "Active" means any thesis that is not in a non-active terminal state.
        // ARCHIVED is a successful terminal state; ELIGIBILITY_REJECTED is a dead-end
        // terminal state that has no outgoing transition — a student whose eligibility
        // was rejected must be able to start over. DEFENSE_FAILED (grade 5 — official
        // faculty rule) is likewise a non-active terminal state: the defense concluded
        // (unsuccessfully), so the student must be free to rework the topic or open a
        // fresh application. All three are preserved as historical/audit rows (never
        // deleted or re-statused); they simply do not block a new thesis.
        boolean hasActive = thesisRepository.findByStudent(student).stream()
                .anyMatch(t -> isActiveStatus(t.getStatus()));
        if (hasActive) {
            throw new BadRequestException("Веќе имате активна дипломска работа.");
        }

        // 200-credit eligibility gate. A null (unknown) credit balance is treated as
        // NOT eligible — a student must have their credits recorded by Student Service first.
        // Enforced here in the service layer so it cannot be bypassed from the client.
        Integer credits = student.getCredits();
        if (credits == null || credits < REQUIRED_CREDITS_FOR_THESIS) {
            throw new BadRequestException(
                    "Потребни се најмалку " + REQUIRED_CREDITS_FOR_THESIS + " кредити за поднесување пријава за дипломска работа.");
        }

        // Anchor createdAt and the 1-month submission deadline to the same instant.
        // @PrePersist keeps an explicitly-set createdAt, so this is authoritative.
        // plusMonths(1) uses java.time month arithmetic — it correctly handles
        // months of 28–31 days rather than assuming a fixed 30-day span.
        OffsetDateTime now = OffsetDateTime.now();

        Thesis thesis = Thesis.builder()
                .student(student)
                .title(request.getTitle())
                .studentComment(request.getStudentComment())
                .status(ThesisStatus.PENDING_ELIGIBILITY_CHECK)
                .createdAt(now)
                .submissionDeadline(now.plusMonths(1))
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
            throw new BadRequestException("Не можете да поднесете барање до ментор во тековниот статус: " + thesis.getStatus());
        }

        User mentor = userRepository.findById(request.getMentorId())
                .orElseThrow(() -> new ResourceNotFoundException("Менторот не е пронајден."));

        requireRole(mentor, Role.MENTOR);

        // Business rule: mentor can supervise at most 15 active theses
        long activeMentorCount = thesisRepository.countActiveMentorTheses(mentor);
        if (activeMentorCount >= 15) {
            throw new BadRequestException("Овој ментор веќе има 15 активни дипломски работи и не може да прифати нови.");
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
            throw new UnauthorizedException("Не сте назначениот ментор на оваа дипломска работа.");
        }

        // Comment is REQUIRED when requesting changes (student needs to know what to change)
        if (request.getDecision() == MentorDecision.REQUEST_CHANGES
                && (request.getMentorComment() == null || request.getMentorComment().isBlank())) {
            throw new BadRequestException("Потребен е коментар кога се бараат измени.");
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
                // Mentor stays assigned; mentor's slot remains consumed (counts toward 15-active)
                // Increment revision counter so the student / list views can show the cycle count
                thesis.setRevisionCount(thesis.getRevisionCount() + 1);
                transitionStatus(thesis, ThesisStatus.MENTOR_REQUESTED_CHANGES, mentor);
                // Surface the mentor's feedback in the notification body so the student sees WHAT to
                // change without opening the thesis. Primitive String — nothing crosses the @Async boundary.
                String message = NotificationType.MENTOR_REQUESTED_CHANGES.getDefaultBody()
                        + "\n\nПовратна информација од менторот: " + thesis.getMentorComment();
                notificationService.notify(thesis.getStudent(), thesis,
                        NotificationType.MENTOR_REQUESTED_CHANGES, message);
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
            throw new BadRequestException("Менторот повеќе не е назначен на оваа дипломска работа.");
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
                    "Пријавата може да се поднесе само од статус APPLICATION_SUBMITTED или од статус на одбивање. Тековен статус: " + s);
        }

        // Enforce the 1-month submission deadline BEFORE doing any work (no PDF is
        // generated and no status transition happens if the deadline has passed).
        // Legacy theses created before this feature carry a null deadline and are
        // intentionally NOT blocked — see CLAUDE.md Item #5 for the legacy policy.
        OffsetDateTime deadline = thesis.getSubmissionDeadline();
        if (deadline != null && OffsetDateTime.now().isAfter(deadline)) {
            throw new BadRequestException(
                    "Рокот за поднесување истече; пријавата за дипломска работа повеќе не може да се поднесе.");
        }

        // Generate the application PDF artifact — archive/service review this
        String pdfPath = applicationPdfService.generate(thesis);
        thesis.setApplicationPdfPath(pdfPath);

        // Stamp the formal-submission timestamp. This starts the official 14-day minimum
        // waiting period that must elapse before a defense can later be requested (see
        // DefenseServiceImpl#createDefenseRequest). Re-stamped on every successful
        // (re)submission — including a resubmission after rejection — since each is a fresh
        // formal submission event. Never touched by version uploads, comments, or committee
        // changes (no other code path sets this field).
        thesis.setApplicationSubmittedAt(OffsetDateTime.now());

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
            throw new BadRequestException("Потребен е коментар за причината за одбивање.");
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
            // Tell student about the rejection — include the reason in the message body.
            // We pass a primitive String (not an entity) so nothing crosses the @Async boundary.
            String message = NotificationType.APPLICATION_REJECTED_BY_ARCHIVE.getDefaultBody()
                    + "\n\nПричина за одбивање: " + thesis.getArchiveComment();
            notificationService.notify(thesis.getStudent(), thesis,
                    NotificationType.APPLICATION_REJECTED_BY_ARCHIVE, message);
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
            throw new BadRequestException("Потребен е коментар за причината за одбивање.");
        }

        if (request.getComment() != null && !request.getComment().isBlank()) {
            thesis.setServiceComment(request.getComment().trim());
        }

        if (request.getApproved()) {
            transitionStatus(thesis, ThesisStatus.IN_PROGRESS, serviceUser);
            notificationService.notify(thesis.getStudent(), thesis, NotificationType.APPLICATION_VALIDATED);
        } else {
            transitionStatus(thesis, ThesisStatus.APPLICATION_REJECTED_BY_SERVICE, serviceUser);
            // Include the rejection reason in the message body (primitive String — no entity crosses @Async).
            String message = NotificationType.APPLICATION_REJECTED_BY_SERVICE.getDefaultBody()
                    + "\n\nПричина за одбивање: " + thesis.getServiceComment();
            notificationService.notify(thesis.getStudent(), thesis,
                    NotificationType.APPLICATION_REJECTED_BY_SERVICE, message);
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
            throw new UnauthorizedException("Не сте назначениот ментор на оваа дипломска работа.");
        }

        transitionStatus(thesis, ThesisStatus.MENTOR_APPROVED, mentor);

        // Notify the student their thesis was approved by the mentor
        notificationService.notify(thesis.getStudent(), thesis, NotificationType.MENTOR_APPROVED_THESIS);

        return ThesisResponse.from(thesis);
    }

    // -------------------------------------------------------------------------
    // P2.2: Archive adds/edits the free-text archive notes on an ARCHIVED thesis
    //
    // Reuses the existing Thesis.archiveNotes field (no new column, no schema change).
    // ARCHIVE-role only, enforced server-side. Editing notes NEVER changes the thesis
    // status (no transitionStatus call → no ThesisStatusHistory row) and NEVER emits a
    // notification — it is a pure record-annotation update. A null/blank note clears it.
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public ThesisResponse updateArchiveNotes(UUID thesisId, ArchiveNotesRequest request) {
        User archiveUser = securityUtils.getCurrentUser();
        // Role check FIRST — a non-ARCHIVE caller is rejected (403) before the thesis is
        // even loaded, so a thesis id cannot be probed for existence by an unauthorized user.
        requireRole(archiveUser, Role.ARCHIVE);

        Thesis thesis = findThesis(thesisId);
        // Notes belong to the official archive record, which only exists once the thesis is
        // ARCHIVED. Any other status → 400 (and the status is left untouched regardless).
        requireStatus(thesis, ThesisStatus.ARCHIVED);

        String notes = request.getNotes();
        // Normalize: a blank note clears the field; otherwise store the trimmed text.
        thesis.setArchiveNotes(notes == null || notes.isBlank() ? null : notes.trim());

        // Persist the annotation only. No transitionStatus() → no history row; no notification.
        thesisRepository.save(thesis);

        return ThesisResponse.from(thesis);
    }

    // -------------------------------------------------------------------------
    // ITEM #8: Student Service explicitly verifies the defense conditions
    //
    // Before a defense can be scheduled the student must have their defense
    // conditions confirmed. For this project the confirmation is a MANUAL pair of
    // booleans (exams + documentation) — no external examination system.
    //
    // The thesis must NOT advance out of PENDING_DEFENSE_CHECK until BOTH conditions
    // are explicitly true. On success this is the ONLY action that moves the thesis
    // into PENDING_DEFENSE_SCHEDULING; the student may then request the defense
    // (Item #6). If either condition is false the request is rejected with a 400 and
    // nothing changes — no transition, no history row, no success notification.
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public ThesisResponse verifyDefenseEligibility(UUID thesisId, DefenseEligibilityRequest request) {
        User serviceUser = securityUtils.getCurrentUser();
        requireRole(serviceUser, Role.STUDENT_SERVICE);

        Thesis thesis = findThesis(thesisId);
        requireStatus(thesis, ThesisStatus.PENDING_DEFENSE_CHECK);

        // BOTH conditions must be explicitly confirmed true. A null (missing) value is
        // treated as NOT confirmed. If either is false we reject BEFORE any state change,
        // so a failed verification leaves the thesis exactly as it was.
        boolean examsCompleted = Boolean.TRUE.equals(request.getExamsCompleted());
        boolean documentationComplete = Boolean.TRUE.equals(request.getDocumentationComplete());
        if (!examsCompleted || !documentationComplete) {
            throw new BadRequestException(
                    "Условите за одбрана не се потврдени: и потребните испити и потребната документација "
                            + "мора да бидат означени како комплетирани пред да може да продолжи одбраната.");
        }

        // Stamp the defense deadline: the student now has 1 month (mirroring the same
        // "+1 month" convention already used for submissionDeadline) to complete the defense
        // process. This is the ONLY place defenseDeadline is ever set from scratch — later
        // extended (never re-derived) by DeadlineExtensionServiceImpl on an approved request.
        thesis.setDefenseDeadline(OffsetDateTime.now().plusMonths(1));

        transitionStatus(thesis, ThesisStatus.PENDING_DEFENSE_SCHEDULING, serviceUser);

        // Tell the student their eligibility was verified and they may now request a defense.
        notificationService.notify(thesis.getStudent(), thesis, NotificationType.DEFENSE_ELIGIBILITY_VERIFIED);

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
        // READ-SIDE IDOR guard: a logged-in user may only read a thesis they are related to
        // (owner / assigned mentor / seated committee member / STUDENT_SERVICE / ARCHIVE).
        // Knowing the UUID is not enough.
        thesisReadAccessPolicy.requireReadAccess(thesis, securityUtils.getCurrentUser());
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
        } else if (user.getRole() == Role.COMMITTEE) {
            // COMMITTEE-role users participate only in defense grading. Scope their list
            // to theses they can act on (DEFENSE_SCHEDULED) plus theses whose defense they
            // already graded — ARCHIVED (grade 6-10, passed) or DEFENSE_FAILED (grade 5,
            // not passed) — so a just-graded thesis stays visible here for reference either way.
            theses = thesisRepository.findAll().stream()
                    .filter(t -> t.getStatus() == ThesisStatus.DEFENSE_SCHEDULED
                              || t.getStatus() == ThesisStatus.ARCHIVED
                              || t.getStatus() == ThesisStatus.DEFENSE_FAILED)
                    .toList();
        } else {
            // Student Service sees all theses
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
    public List<ThesisResponse> getCommitteeTheses() {
        User user = securityUtils.getCurrentUser();
        List<Thesis> theses;

        // Which statuses "belong" on the committee page — anywhere a committee action is
        // relevant: a mentor has approved the thesis and could propose members, service
        // approval / accepting review happens here, defense-check follows immediately.
        java.util.EnumSet<ThesisStatus> committeeStatuses = java.util.EnumSet.of(
                ThesisStatus.MENTOR_APPROVED,
                ThesisStatus.COMMITTEE_REVIEW,
                ThesisStatus.COMMITTEE_ACCEPTED,
                ThesisStatus.PENDING_DEFENSE_CHECK,
                ThesisStatus.PENDING_DEFENSE_SCHEDULING,
                ThesisStatus.DEFENSE_SCHEDULED);

        if (user.getRole() == Role.MENTOR) {
            // Union of theses they mentor and theses they serve on as a committee member.
            java.util.Map<UUID, Thesis> byId = new java.util.LinkedHashMap<>();
            for (Thesis t : thesisRepository.findByMentor(user)) byId.put(t.getId(), t);
            for (Thesis t : thesisRepository.findByCommitteeMember(user)) byId.put(t.getId(), t);
            theses = byId.values().stream()
                    .filter(t -> committeeStatuses.contains(t.getStatus()))
                    .toList();
        } else if (user.getRole() == Role.STUDENT_SERVICE) {
            theses = thesisRepository.findAll().stream()
                    .filter(t -> committeeStatuses.contains(t.getStatus()))
                    .toList();
        } else if (user.getRole() == Role.COMMITTEE) {
            // COMMITTEE-role users are involved in defense grading, not committee formation.
            // Show them the theses they might grade.
            theses = thesisRepository.findByStatus(ThesisStatus.DEFENSE_SCHEDULED);
        } else {
            throw new UnauthorizedException("Немате пристап до прегледот на комисии.");
        }

        return theses.stream().map(ThesisResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ThesisResponse> getDefenseTheses() {
        User user = securityUtils.getCurrentUser();
        List<Thesis> theses;

        // ARCHIVED (passed, grade 6-10) and DEFENSE_FAILED (not passed, grade 5) are both
        // defense OUTCOMES, so both stay visible here for reference alongside the
        // in-progress defense stages.
        java.util.EnumSet<ThesisStatus> defenseStatuses = java.util.EnumSet.of(
                ThesisStatus.PENDING_DEFENSE_CHECK,
                ThesisStatus.PENDING_DEFENSE_SCHEDULING,
                ThesisStatus.DEFENSE_SCHEDULED,
                ThesisStatus.ARCHIVED,
                ThesisStatus.DEFENSE_FAILED);

        if (user.getRole() == Role.STUDENT) {
            theses = thesisRepository.findByStudent(user).stream()
                    .filter(t -> defenseStatuses.contains(t.getStatus()))
                    .toList();
        } else if (user.getRole() == Role.MENTOR) {
            java.util.Map<UUID, Thesis> byId = new java.util.LinkedHashMap<>();
            for (Thesis t : thesisRepository.findByMentor(user)) byId.put(t.getId(), t);
            for (Thesis t : thesisRepository.findByCommitteeMember(user)) byId.put(t.getId(), t);
            theses = byId.values().stream()
                    .filter(t -> defenseStatuses.contains(t.getStatus()))
                    .toList();
        } else if (user.getRole() == Role.COMMITTEE) {
            theses = thesisRepository.findAll().stream()
                    .filter(t -> t.getStatus() == ThesisStatus.DEFENSE_SCHEDULED
                              || t.getStatus() == ThesisStatus.ARCHIVED
                              || t.getStatus() == ThesisStatus.DEFENSE_FAILED)
                    .toList();
        } else if (user.getRole() == Role.STUDENT_SERVICE) {
            theses = thesisRepository.findAll().stream()
                    .filter(t -> defenseStatuses.contains(t.getStatus()))
                    .toList();
        } else {
            throw new UnauthorizedException("Немате пристап до прегледот на одбрани.");
        }

        return theses.stream().map(ThesisResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public org.springframework.core.io.Resource downloadApplicationPdf(UUID thesisId) {
        Thesis thesis = findThesis(thesisId);
        // AUTHORIZATION FIRST — thesis-specific, before any PDF/file work. A user may download
        // the application PDF only if they are related to THIS thesis: the student owner, the
        // assigned mentor, a committee member seated on THIS thesis, STUDENT_SERVICE, or ARCHIVE.
        // The COMMITTEE role alone is NOT sufficient — an unseated COMMITTEE user, or one seated
        // on a different thesis, is denied. Reuses the shared read-access policy so this endpoint
        // authorizes against the SAME rule as the other thesis-level reads (no duplicate logic).
        // Knowing the UUID never bypasses this.
        thesisReadAccessPolicy.requireReadAccess(thesis, securityUtils.getCurrentUser());
        if (thesis.getApplicationPdfPath() == null) {
            throw new ResourceNotFoundException("Сè уште не е генерирана PDF-датотека на пријавата за оваа дипломска работа.");
        }
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(thesis.getApplicationPdfPath()).toAbsolutePath().normalize();
            org.springframework.core.io.UrlResource r = new org.springframework.core.io.UrlResource(p.toUri());
            if (!r.exists() || !r.isReadable()) {
                throw new ResourceNotFoundException("Датотеката со PDF на пријавата не е пронајдена.");
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
                        "Не е пронајдена дипломска работа со регистарски број: " + registrationNumber));
        // READ-SIDE IDOR guard — same rule as getThesisById. Registration numbers are
        // sequential (DT-YYYY-NNNN) and trivially enumerable, so without this check any
        // authenticated user could harvest every archived thesis (including the internal
        // student/mentor/archive/service comments carried by ThesisResponse) by walking the
        // number space. Only a user related to THIS thesis (owner / assigned mentor / seated
        // committee member / STUDENT_SERVICE / ARCHIVE) may read it — matching every other
        // thesis-level read. STUDENT_SERVICE and ARCHIVE (the legitimate archive-search users)
        // retain full access, so the archive lookup UI keeps working.
        thesisReadAccessPolicy.requireReadAccess(thesis, securityUtils.getCurrentUser());
        return ThesisResponse.from(thesis);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ThesisStatusHistoryResponse> getStatusHistory(UUID thesisId) {
        Thesis thesis = findThesis(thesisId);
        // Same underlying-thesis authorization as getThesisById — the status history is
        // sensitive workflow data and must not be readable by an unrelated user via UUID.
        thesisReadAccessPolicy.requireReadAccess(thesis, securityUtils.getCurrentUser());
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
                .orElseThrow(() -> new ResourceNotFoundException("Дипломската работа не е пронајдена (id: " + id + ")."));
    }

    /**
     * Whether a thesis in this status counts as the student's one "active" thesis for the
     * one-active-thesis rule in {@link #createThesis}. Non-active terminal states do NOT
     * block a new thesis:
     * <ul>
     *   <li>{@code ARCHIVED} — successfully completed and archived.</li>
     *   <li>{@code ELIGIBILITY_REJECTED} — a dead-end with no outgoing transition; the
     *       student was refused eligibility and must be allowed to submit a fresh thesis.
     *       The rejected row itself is left untouched as historical/audit data.</li>
     *   <li>{@code DEFENSE_FAILED} — official faculty rule: a defense graded 5 was NOT
     *       successfully defended. The defense already concluded, so it must not keep
     *       blocking the student from reworking the topic or opening a new application.
     *       The failed row is left untouched as historical/audit data (see
     *       DefenseResultServiceImpl#recordResult).</li>
     * </ul>
     * Every other status is considered active and enforces the one-active-thesis rule.
     */
    private boolean isActiveStatus(ThesisStatus status) {
        return status != ThesisStatus.ARCHIVED
                && status != ThesisStatus.ELIGIBILITY_REJECTED
                && status != ThesisStatus.DEFENSE_FAILED;
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
            throw new UnauthorizedException("Оваа акција бара улога: " + required);
        }
    }

    private void requireStatus(Thesis thesis, ThesisStatus required) {
        if (thesis.getStatus() != required) {
            throw new BadRequestException(
                    "Невалиден статус на дипломската работа. Очекуван: " + required + ", а тековен е: " + thesis.getStatus());
        }
    }

    private void requireOwner(Thesis thesis, User user) {
        if (!thesis.getStudent().getId().equals(user.getId())) {
            throw new UnauthorizedException("Не сте сопственик на оваа дипломска работа.");
        }
    }
}
