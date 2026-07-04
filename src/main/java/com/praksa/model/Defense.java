package com.praksa.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "defenses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Defense {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thesis_id", nullable = false)
    private Thesis thesis;

    @Column(nullable = false)
    private String room;

    @Column(name = "scheduled_at", nullable = false)
    private OffsetDateTime scheduledAt;

    @Column(name = "is_cancelled", nullable = false)
    private boolean isCancelled;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    private User cancelledBy;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    /**
     * Stamped when the defense reminder email has been sent (24h before scheduledAt).
     * NULL means no reminder has gone out yet. Prevents duplicate reminders.
     */
    @Column(name = "reminder_sent_at")
    private OffsetDateTime reminderSentAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
