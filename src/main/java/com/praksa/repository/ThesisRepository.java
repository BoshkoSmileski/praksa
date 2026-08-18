package com.praksa.repository;

import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.ThesisStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ThesisRepository extends JpaRepository<Thesis, UUID> {
    List<Thesis> findByStudent(User student);
    List<Thesis> findByMentor(User mentor);
    Optional<Thesis> findByStudentAndStatusNot(User student, ThesisStatus status);

    @Query("SELECT COUNT(t) FROM Thesis t WHERE t.mentor = :mentor AND t.status != 'ARCHIVED'")
    long countActiveMentorTheses(User mentor);

    List<Thesis> findByStatus(ThesisStatus status);

    // ---------- Archive metadata queries ----------

    /** Used by registration-number generator to find the next sequence within a year. */
    long countByArchiveRegistrationNumberStartingWith(String prefix);

    /** Exact lookup by registration number. */
    Optional<Thesis> findByArchiveRegistrationNumber(String registrationNumber);

    /**
     * Theses still in COMMITTEE_REVIEW whose review period started at or before the cutoff.
     * Used by the auto-advance scheduled job. The comparison is inclusive (&lt;=) so a review
     * that started exactly 5 business days ago is treated as stale (Item #9: "exactly 5
     * working days elapsed → automatically accepted").
     */
    @Query("SELECT t FROM Thesis t WHERE t.status = 'COMMITTEE_REVIEW' " +
           "AND t.committeeReviewStartedAt IS NOT NULL " +
           "AND t.committeeReviewStartedAt <= :cutoff")
    List<Thesis> findStaleCommitteeReviews(java.time.OffsetDateTime cutoff);

    /**
     * Theses on whose committee the given user (typically MENTOR-role) holds a seat.
     * Used to scope the Committee page for professors who serve on a formal committee.
     */
    @Query("SELECT DISTINCT t FROM Thesis t JOIN CommitteeMember c ON c.thesis = t " +
           "WHERE c.professor = :professor")
    List<Thesis> findByCommitteeMember(User professor);

    /**
     * Theses whose submission deadline has already passed while they are still in a
     * pre-application status (i.e. the formal application was never successfully
     * submitted in time). Used by the read-only expired-deadline reporting job.
     * Legacy rows with a null deadline are excluded (deadline IS NOT NULL by the
     * &lt; comparison), so they never appear here.
     */
    @Query("SELECT t FROM Thesis t WHERE t.submissionDeadline IS NOT NULL " +
           "AND t.submissionDeadline < :cutoff AND t.status IN :statuses")
    List<Thesis> findExpiredPendingApplications(java.time.OffsetDateTime cutoff,
                                                java.util.Collection<ThesisStatus> statuses);
}
