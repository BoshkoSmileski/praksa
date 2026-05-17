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
}
