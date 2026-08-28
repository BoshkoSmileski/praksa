package com.praksa.model;

import com.praksa.model.enums.DeadlineExtensionStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Official faculty procedure: a student may request an extension of the defense deadline
 * ({@link Thesis#getDefenseDeadline()}), for a maximum of 15 additional days, with a written
 * explanation. Submitting a request never mutates the deadline directly — it creates a PENDING
 * row awaiting a STUDENT_SERVICE decision (see DeadlineExtensionServiceImpl). Approval extends
 * {@code Thesis.defenseDeadline} by exactly {@code requestedDays}; rejection leaves it unchanged.
 *
 * <p>PENDING and REJECTED rows are kept permanently for audit history — a rejected request is
 * never overwritten or reused, the student submits a brand new row for their next attempt. At
 * most one PENDING request may exist per thesis at a time, and (conservative design choice,
 * documented in CLAUDE.md) at most one APPROVED extension is permitted per thesis, so the total
 * extension granted can never silently exceed the 15-day-per-request cap.
 */
@Entity
@Table(name = "deadline_extension_requests", indexes = {
        @Index(name = "idx_deadline_ext_thesis_status", columnList = "thesis_id, status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeadlineExtensionRequest {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thesis_id", nullable = false)
    private Thesis thesis;

    // The student who submitted this request. Always equal to thesis.getStudent() at creation
    // time (ownership is enforced in the service), but stored explicitly for audit — same
    // convention as DefenseRequest.requestedBy / CommitteeMember.proposedBy.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by", nullable = false)
    private User requestedBy;

    // The student's written explanation for why more time is needed.
    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    // 1-15 inclusive (bean-validated on the DTO, re-validated in the service).
    @Column(name = "requested_days", nullable = false)
    private int requestedDays;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeadlineExtensionStatus status;

    // Populated only on rejection — the STUDENT_SERVICE decision reason, mandatory when rejecting.
    @Column(name = "decision_reason", columnDefinition = "text")
    private String decisionReason;

    // Audit snapshot — the thesis's defenseDeadline immediately before this decision.
    // Populated only once the request is decided (approved or rejected).
    @Column(name = "previous_deadline")
    private OffsetDateTime previousDeadline;

    // Audit snapshot — the thesis's defenseDeadline immediately after approval.
    // Remains null for a PENDING or REJECTED request (the deadline never changes on rejection).
    @Column(name = "new_deadline")
    private OffsetDateTime newDeadline;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private User decidedBy;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (status == null) status = DeadlineExtensionStatus.PENDING;
    }
}
