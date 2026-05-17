package com.praksa.repository;

import com.praksa.model.Defense;
import com.praksa.model.Thesis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DefenseRepository extends JpaRepository<Defense, UUID> {
    List<Defense> findByThesis(Thesis thesis);
    Optional<Defense> findByThesisAndIsCancelledFalse(Thesis thesis);
}
