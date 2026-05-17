package com.praksa.repository;

import com.praksa.model.Defense;
import com.praksa.model.DefenseResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DefenseResultRepository extends JpaRepository<DefenseResult, UUID> {
    Optional<DefenseResult> findByDefense(Defense defense);
}
