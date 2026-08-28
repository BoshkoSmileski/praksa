package com.praksa.model;

import com.praksa.model.enums.ThesisStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "theses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Thesis {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mentor_id")
    private User mentor;

    @Column(nullable = false)
    private String title;

    @Column(name = "student_comment", columnDefinition = "text")
    private String studentComment;

    @Column(name = "mentor_comment", columnDefinition = "text")
    private String mentorComment;

    // Last note left by Archive during validation (approval note or rejection reason)
    @Column(name = "archive_comment", columnDefinition = "text")
    private String archiveComment;

    // Last note left by Student Service during validation
    @Column(name = "service_comment", columnDefinition = "text")
    private String serviceComment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ThesisStatus status;

    /**
     * How many times the mentor has asked for proposal revisions on this thesis.
     * Incremented once each time mentor decides REQUEST_CHANGES.
     * Never decremented — preserves the audit signal.
     */
    @Builder.Default
    @Column(name = "revision_count", nullable = false)
    private int revisionCount = 0;

    /**
     * Stamped when the thesis transitions to COMMITTEE_REVIEW.
     * Used by the auto-advance scheduled job: after 5 business days the system
     * advances the thesis to COMMITTEE_ACCEPTED if the committee hasn't acted.
     */
    @Column(name = "committee_review_started_at")
    private OffsetDateTime committeeReviewStartedAt;

    /**
     * Path to the generated application form PDF on disk.
     * Created when the student calls submitApplication; never overwritten on resubmit.
     * Downloadable by all parties involved in the validation chain.
     */
    @Column(name = "application_pdf_path", length = 1024)
    private String applicationPdfPath;

    @Column(name = "submission_deadline")
    private OffsetDateTime submissionDeadline;

    /**
     * Timestamp of the most recent successful formal application submission
     * (ThesisServiceImpl#submitApplication). Re-stamped on every successful (re)submission,
     * including a resubmission after an archive/service rejection, since each is a fresh
     * formal submission of the application. Never touched by version uploads, comments,
     * or committee changes.
     *
     * <p>Used to enforce the official 14-day minimum waiting period before a defense may be
     * requested (see DefenseServiceImpl#createDefenseRequest).
     */
    @Column(name = "application_submitted_at")
    private OffsetDateTime applicationSubmittedAt;

    /**
     * Timestamp of the MOST RECENT thesis version upload. Stamped by
     * ThesisVersionServiceImpl.uploadVersion() on every successful upload — each new
     * version resets this clock. Used by the scheduled mentor-review-deadline reminder
     * job (45 days) to detect a submitted version the mentor has not yet acted on.
     */
    @Column(name = "last_version_submitted_at")
    private OffsetDateTime lastVersionSubmittedAt;

    /**
     * Deadline by which the student must complete the defense process, once Student Service
     * has verified defense eligibility. Stamped exactly once, in
     * ThesisServiceImpl#verifyDefenseEligibility (the PENDING_DEFENSE_CHECK ->
     * PENDING_DEFENSE_SCHEDULING transition), as {@code now + 1 month} — the same "+1 month"
     * convention already used for {@link #submissionDeadline}. Never set at thesis creation and
     * never touched by any other workflow step.
     *
     * <p>Official faculty procedure: a student may request an extension of this deadline, for a
     * maximum of 15 additional days, via {@code DeadlineExtensionServiceImpl}. Approval advances
     * this field by exactly the requested number of days; rejection leaves it untouched. This
     * field carries no automatic enforcement elsewhere (e.g. it does not gate
     * DefenseServiceImpl#createDefenseRequest) — it exists to track the deadline and support the
     * extension workflow; see CLAUDE.md for the full design rationale.
     */
    @Column(name = "defense_deadline")
    private OffsetDateTime defenseDeadline;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    // ---------- Archive metadata (populated when the thesis becomes ARCHIVED) ----------
    // All four fields are set in a single place: DefenseResultServiceImpl.recordResult().
    // Once assigned, the registration number is immutable.

    @Column(name = "archive_registration_number", unique = true, length = 50)
    private String archiveRegistrationNumber;

    @Column(name = "archive_date")
    private OffsetDateTime archiveDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "archived_by")
    private User archivedBy;

    @Column(name = "archive_notes", columnDefinition = "text")
    private String archiveNotes;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (status == null) status = ThesisStatus.PENDING_ELIGIBILITY_CHECK;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
