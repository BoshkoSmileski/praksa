package com.praksa.repository;

import com.praksa.model.Notification;
import com.praksa.model.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    List<Notification> findByUserOrderByCreatedAtDesc(User user);
    List<Notification> findByIsSentFalse();

    /**
     * P3.6 — count of UNREAD notifications belonging to a specific user. Scoped by the
     * {@code user} argument (the authenticated user, resolved server-side), so it can only
     * ever return the caller's own unread count — never a global or another user's count.
     */
    long countByUserAndIsReadFalse(User user);

    /**
     * P3.6 — bulk "mark all as read" for a SINGLE user. The {@code WHERE n.user = :user}
     * clause scopes the update to exactly the authenticated user's rows, so it can never
     * touch another user's notifications. Only currently-unread rows are updated (idempotent,
     * and it never rewrites already-read rows). Read/unread only — {@code isSent}/{@code sentAt}
     * and every other column are left untouched.
     *
     * @return the number of rows updated (unread → read)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.user = :user AND n.isRead = false")
    int markAllReadByUser(@Param("user") User user);

    /**
     * Retry query (P2.6). Returns a BOUNDED, oldest-first page of notifications that are
     * still unsent AND were created before {@code cutoff}. The cutoff (an age threshold)
     * skips brand-new rows whose original async send from the normal flow may still be
     * in flight, which avoids racing — and double-sending — a notification that the
     * normal path is about to mark sent. The {@link Pageable} bounds the batch so a large
     * backlog can never fan out an unbounded number of async emails in a single run.
     */
    List<Notification> findByIsSentFalseAndCreatedAtBeforeOrderByCreatedAtAsc(
            OffsetDateTime cutoff, Pageable pageable);
}
