package com.praksa.repository;

import com.praksa.model.DefenseRequest;
import com.praksa.model.Thesis;
import com.praksa.model.enums.DefenseRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DefenseRequestRepository extends JpaRepository<DefenseRequest, UUID> {

    // Used to enforce "at most one PENDING request per thesis" and to locate the
    // request a STUDENT_SERVICE decision applies to.
    Optional<DefenseRequest> findByThesisAndStatus(Thesis thesis, DefenseRequestStatus status);

    // Full audit history (current + historical rejected/approved requests), newest first.
    List<DefenseRequest> findByThesisOrderByCreatedAtDesc(Thesis thesis);
}
