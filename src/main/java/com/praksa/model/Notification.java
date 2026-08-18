package com.praksa.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thesis_id")
    private Thesis thesis;

    @Column(nullable = false)
    private String type;

    @Column(name = "is_sent", nullable = false)
    private boolean isSent;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    /**
     * P3.6 — Read/unread state of the notification in the APPLICATION UI.
     *
     * This is completely independent of {@link #isSent} (email delivery). A notification
     * can be sent-but-unread, unsent-but-read, etc. — all four combinations are valid.
     * New notifications default to unread (false); the user marks them read via the
     * mark-read endpoints. Nothing in the email/retry path ever touches this flag.
     *
     * {@code @ColumnDefault("false")} makes Hibernate emit {@code DEFAULT false} in the
     * generated {@code ALTER TABLE ... ADD COLUMN} under {@code ddl-auto=update}, so the
     * new NOT NULL column back-fills existing notification rows as unread safely.
     */
    @Column(name = "is_read", nullable = false)
    @ColumnDefault("false")
    private boolean isRead;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
