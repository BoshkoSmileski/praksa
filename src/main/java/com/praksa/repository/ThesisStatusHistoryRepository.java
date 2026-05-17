package com.praksa.repository;

import com.praksa.model.Thesis;
import com.praksa.model.ThesisStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ThesisStatusHistoryRepository extends JpaRepository<ThesisStatusHistory, UUID> {
    List<ThesisStatusHistory> findByThesisOrderByChangedAtAsc(Thesis thesis);
}
