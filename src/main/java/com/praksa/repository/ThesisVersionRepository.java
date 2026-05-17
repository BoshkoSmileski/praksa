package com.praksa.repository;

import com.praksa.model.Thesis;
import com.praksa.model.ThesisVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ThesisVersionRepository extends JpaRepository<ThesisVersion, UUID> {
    List<ThesisVersion> findByThesisOrderByVersionNumberAsc(Thesis thesis);
    Optional<ThesisVersion> findByThesisAndIsFinalTrue(Thesis thesis);
    Optional<ThesisVersion> findTopByThesisOrderByVersionNumberDesc(Thesis thesis);
}
