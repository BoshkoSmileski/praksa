package com.praksa.repository;

import com.praksa.model.ThesisComment;
import com.praksa.model.ThesisVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ThesisCommentRepository extends JpaRepository<ThesisComment, UUID> {
    List<ThesisComment> findByVersionOrderByCreatedAtAsc(ThesisVersion version);
}
