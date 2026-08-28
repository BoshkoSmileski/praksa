package com.praksa.repository;

import com.praksa.model.Defense;
import com.praksa.model.Thesis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DefenseRepository extends JpaRepository<Defense, UUID> {
    List<Defense> findByThesis(Thesis thesis);
    Optional<Defense> findByThesisAndIsCancelledFalse(Thesis thesis);

    /**
     * Every non-cancelled defense currently booked in a room. Used by the defense-request
     * approval flow to detect a double-booking. Cancelled defenses are excluded so they never
     * block room availability.
     */
    List<Defense> findByRoomAndIsCancelledFalse(String room);

    /**
     * Active defenses scheduled within the given time window for which no reminder has been sent.
     * Used by the scheduled job that sends the 24h-pre reminder.
     */
    @Query("SELECT d FROM Defense d WHERE d.isCancelled = false " +
           "AND d.reminderSentAt IS NULL " +
           "AND d.scheduledAt BETWEEN :windowStart AND :windowEnd")
    List<Defense> findUpcomingForReminder(OffsetDateTime windowStart, OffsetDateTime windowEnd);
}
