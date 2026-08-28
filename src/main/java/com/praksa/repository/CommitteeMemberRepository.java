package com.praksa.repository;

import com.praksa.model.CommitteeMember;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.MemberRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommitteeMemberRepository extends JpaRepository<CommitteeMember, UUID> {
    List<CommitteeMember> findByThesis(Thesis thesis);
    long countByThesis(Thesis thesis);

    // Prevents adding the same professor twice to the same committee
    boolean existsByThesisAndProfessor(Thesis thesis, User professor);

    // The actual seat row for a professor on a specific thesis — used where the caller needs
    // more than a yes/no answer, e.g. write-side grading authorization must also inspect
    // isExternalNonVoting (an external non-voting member holds a real seat but may not grade).
    Optional<CommitteeMember> findByThesisAndProfessor(Thesis thesis, User professor);

    // Finds the mentor's own committee record (to avoid re-adding them)
    Optional<CommitteeMember> findByThesisAndMemberRole(Thesis thesis, MemberRole memberRole);
}
