package com.praksa.repository;

import com.praksa.model.DeadlineExtensionRequest;
import com.praksa.model.Thesis;
import com.praksa.model.enums.DeadlineExtensionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeadlineExtensionRequestRepository extends JpaRepository<DeadlineExtensionRequest, UUID> {

    // Used to enforce "at most one PENDING request per thesis" and to locate the request a
    // STUDENT_SERVICE decision applies to. Also used with APPROVED to enforce the conservative
    // "at most one approved extension per thesis" rule.
    Optional<DeadlineExtensionRequest> findByThesisAndStatus(Thesis thesis, DeadlineExtensionStatus status);

    // Full audit history (current + historical rejected/approved requests), newest first.
    List<DeadlineExtensionRequest> findByThesisOrderByCreatedAtDesc(Thesis thesis);
}
