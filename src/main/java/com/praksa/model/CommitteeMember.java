package com.praksa.model;

import com.praksa.model.enums.MemberRole;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "committee_members",
        uniqueConstraints = @UniqueConstraint(columnNames = {"thesis_id", "professor_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommitteeMember {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thesis_id", nullable = false)
    private Thesis thesis;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "professor_id", nullable = false)
    private User professor;

    @Enumerated(EnumType.STRING)
    @Column(name = "member_role", nullable = false)
    private MemberRole memberRole;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proposed_by")
    private User proposedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private User approvedBy;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    // Committee member's review notes — nullable until they submit their review
    @Column(columnDefinition = "text")
    private String notes;

    /**
     * Official faculty procedure: a committee of 4 may include ONE external professional
     * from practice, who participates in the committee (review, defense) but is NOT a
     * voting member — they cannot record a defense grade. Normal (internal) members and
     * every existing pre-this-change row are {@code false}. This is a property of the
     * SEAT, not a system-wide user role (a MENTOR-role professor is simply flagged external
     * on this particular thesis's committee).
     *
     * {@code @ColumnDefault("false")} makes Hibernate emit {@code DEFAULT false} in the
     * generated {@code ALTER TABLE ... ADD COLUMN} under {@code ddl-auto=update}, so the new
     * NOT NULL column back-fills every existing committee-member row as a normal voting
     * member safely.
     */
    @Column(name = "is_external_non_voting", nullable = false)
    @ColumnDefault("false")
    private boolean isExternalNonVoting;
}
