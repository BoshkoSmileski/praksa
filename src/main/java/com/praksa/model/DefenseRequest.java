package com.praksa.model;

import com.praksa.model.enums.DefenseRequestStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A student-proposed defense room/date/time, awaiting STUDENT_SERVICE review.
 *
 * A request never creates a {@link Defense} by itself — only an APPROVED decision does
 * (see DefenseServiceImpl.decideDefenseRequest). PENDING and REJECTED rows are kept
 * permanently for audit history; a rejected request is never overwritten or reused —
 * the student submits a brand new row for their next proposal.
 */
@Entity
@Table(name = "defense_requests", indexes = {
        @Index(name = "idx_defense_requests_thesis_status", columnList = "thesis_id, status"),
        @Index(name = "idx_defense_requests_room", columnList = "room")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DefenseRequest {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thesis_id", nullable = false)
    private Thesis thesis;

    // The student who submitted this proposal. Always equal to thesis.getStudent() at
    // creation time (ownership is enforced in the service), but stored explicitly for
    // audit — same convention as CommitteeMember.proposedBy / ThesisStatusHistory.changedBy.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by", nullable = false)
    private User requestedBy;

    @Column(nullable = false)
    private String room;

    @Column(name = "scheduled_at", nullable = false)
    private OffsetDateTime scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DefenseRequestStatus status;

    // Populated on rejection (mandatory reason) or when approval discovers a conflict.
    @Column(columnDefinition = "text")
    private String reason;

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
        if (status == null) status = DefenseRequestStatus.PENDING;
    }
}
