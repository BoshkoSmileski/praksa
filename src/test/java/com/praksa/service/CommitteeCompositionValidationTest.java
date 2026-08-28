package com.praksa.service;

import com.praksa.dto.committee.CommitteeMemberResponse;
import com.praksa.dto.committee.ProposeCommitteeRequest;
import com.praksa.exception.BadRequestException;
import com.praksa.model.CommitteeMember;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.MemberRole;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.CommitteeMemberRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.repository.UserRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.security.ThesisReadAccessPolicy;
import com.praksa.service.impl.CommitteeServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Official faculty procedure — a thesis defense committee may have 3 OR 4 members. A 4-member
 * committee must include exactly ONE external, non-voting professional from practice; a
 * 3-member committee must have none. The mentor is always the (voting) chair and can never be
 * the external seat.
 *
 * <p>Covers the two authoritative gates independently:
 * <ul>
 *   <li>{@code proposeCommittee} — where the seats are actually created, so composition must be
 *       valid at creation time (letters covering the {@link ProposeCommitteeRequest} shape);</li>
 *   <li>{@code approveCommittee} — the final safety-net re-check against the persisted seats
 *       (letters A-G from the task's composition checklist), mirroring the pre-existing
 *       "exactly 3" safety-check pattern this method already had.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CommitteeCompositionValidationTest {

    @Mock private CommitteeMemberRepository committeeRepository;
    @Mock private ThesisRepository thesisRepository;
    @Mock private ThesisStatusHistoryRepository statusHistoryRepository;
    @Mock private UserRepository userRepository;
    @Mock private SecurityUtils securityUtils;
    @Mock private NotificationService notificationService;
    @Mock private ThesisReadAccessPolicy thesisReadAccessPolicy;

    @InjectMocks private CommitteeServiceImpl committeeService;

    private User user(Role role) {
        return User.builder().id(UUID.randomUUID()).email(role + "-" + UUID.randomUUID() + "@t.com")
                .fullName(role + " User").role(role).build();
    }

    private Thesis mentorApprovedThesis(User student, User mentor) {
        return Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(student).mentor(mentor).status(ThesisStatus.MENTOR_APPROVED).build();
    }

    private CommitteeMember seat(Thesis thesis, User professor, MemberRole role, boolean external) {
        return CommitteeMember.builder().id(UUID.randomUUID()).thesis(thesis).professor(professor)
                .memberRole(role).isExternalNonVoting(external).build();
    }

    // =========================================================================
    // approveCommittee — composition re-validated against the PERSISTED seats
    // (letters A, C, D, E, F, G from the task's checklist)
    // =========================================================================

    @Test
    @DisplayName("A. 2 members → approveCommittee rejects (below minimum)")
    void approve_twoMembers_rejected() {
        User admin = user(Role.STUDENT_SERVICE);
        Thesis thesis = mentorApprovedThesis(user(Role.STUDENT), user(Role.MENTOR));

        when(securityUtils.getCurrentUser()).thenReturn(admin);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(committeeRepository.countByThesis(thesis)).thenReturn(2L);

        assertThrows(BadRequestException.class, () -> committeeService.approveCommittee(thesis.getId()));
        verify(committeeRepository, never()).findByThesis(thesis);
        verify(notificationService, never()).notify(any(), any(), any());
    }

    @Test
    @DisplayName("C. 4 members with exactly 1 external non-voting member → approveCommittee accepts")
    void approve_fourMembersOneExternal_accepted() {
        User admin = user(Role.STUDENT_SERVICE);
        User student = user(Role.STUDENT);
        User mentor = user(Role.MENTOR);
        Thesis thesis = mentorApprovedThesis(student, mentor);
        User p1 = user(Role.MENTOR), p2 = user(Role.MENTOR), external = user(Role.MENTOR);

        List<CommitteeMember> members = List.of(
                seat(thesis, mentor, MemberRole.MENTOR_MEMBER, false),
                seat(thesis, p1, MemberRole.FORMAL_MEMBER, false),
                seat(thesis, p2, MemberRole.FORMAL_MEMBER, false),
                seat(thesis, external, MemberRole.FORMAL_MEMBER, true));

        when(securityUtils.getCurrentUser()).thenReturn(admin);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(committeeRepository.countByThesis(thesis)).thenReturn(4L);
        when(committeeRepository.findByThesis(thesis)).thenReturn(members);

        List<CommitteeMemberResponse> result = committeeService.approveCommittee(thesis.getId());

        assertEquals(4, result.size());
        assertEquals(ThesisStatus.COMMITTEE_REVIEW, thesis.getStatus());
    }

    @Test
    @DisplayName("D. 4 members with 0 external members → approveCommittee rejects")
    void approve_fourMembersZeroExternal_rejected() {
        User admin = user(Role.STUDENT_SERVICE);
        User mentor = user(Role.MENTOR);
        Thesis thesis = mentorApprovedThesis(user(Role.STUDENT), mentor);
        User p1 = user(Role.MENTOR), p2 = user(Role.MENTOR), p3 = user(Role.MENTOR);

        List<CommitteeMember> members = List.of(
                seat(thesis, mentor, MemberRole.MENTOR_MEMBER, false),
                seat(thesis, p1, MemberRole.FORMAL_MEMBER, false),
                seat(thesis, p2, MemberRole.FORMAL_MEMBER, false),
                seat(thesis, p3, MemberRole.FORMAL_MEMBER, false));

        when(securityUtils.getCurrentUser()).thenReturn(admin);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(committeeRepository.countByThesis(thesis)).thenReturn(4L);
        when(committeeRepository.findByThesis(thesis)).thenReturn(members);

        assertThrows(BadRequestException.class, () -> committeeService.approveCommittee(thesis.getId()));
        assertEquals(ThesisStatus.MENTOR_APPROVED, thesis.getStatus());
        verify(notificationService, never()).notify(any(), any(), any());
    }

    @Test
    @DisplayName("E. 4 members with 2 external members → approveCommittee rejects")
    void approve_fourMembersTwoExternal_rejected() {
        User admin = user(Role.STUDENT_SERVICE);
        User mentor = user(Role.MENTOR);
        Thesis thesis = mentorApprovedThesis(user(Role.STUDENT), mentor);
        User p1 = user(Role.MENTOR), ext1 = user(Role.MENTOR), ext2 = user(Role.MENTOR);

        List<CommitteeMember> members = List.of(
                seat(thesis, mentor, MemberRole.MENTOR_MEMBER, false),
                seat(thesis, p1, MemberRole.FORMAL_MEMBER, false),
                seat(thesis, ext1, MemberRole.FORMAL_MEMBER, true),
                seat(thesis, ext2, MemberRole.FORMAL_MEMBER, true));

        when(securityUtils.getCurrentUser()).thenReturn(admin);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(committeeRepository.countByThesis(thesis)).thenReturn(4L);
        when(committeeRepository.findByThesis(thesis)).thenReturn(members);

        assertThrows(BadRequestException.class, () -> committeeService.approveCommittee(thesis.getId()));
        assertEquals(ThesisStatus.MENTOR_APPROVED, thesis.getStatus());
    }

    @Test
    @DisplayName("F. 5 members → approveCommittee rejects (above maximum)")
    void approve_fiveMembers_rejected() {
        User admin = user(Role.STUDENT_SERVICE);
        Thesis thesis = mentorApprovedThesis(user(Role.STUDENT), user(Role.MENTOR));

        when(securityUtils.getCurrentUser()).thenReturn(admin);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(committeeRepository.countByThesis(thesis)).thenReturn(5L);

        assertThrows(BadRequestException.class, () -> committeeService.approveCommittee(thesis.getId()));
        verify(committeeRepository, never()).findByThesis(thesis);
    }

    @Test
    @DisplayName("G. Mentor's seat marked external (corrupted/legacy data) → approveCommittee rejects "
            + "(the mentor must remain a voting member)")
    void approve_mentorMarkedExternal_rejected() {
        User admin = user(Role.STUDENT_SERVICE);
        User mentor = user(Role.MENTOR);
        Thesis thesis = mentorApprovedThesis(user(Role.STUDENT), mentor);
        User p1 = user(Role.MENTOR), p2 = user(Role.MENTOR);

        // Defensive scenario: the mentor's own seat is (incorrectly) flagged external — this can
        // never happen through proposeCommittee (the mentor is never in professorIds), but
        // approveCommittee re-derives the rule from the persisted rows rather than trusting that
        // proposeCommittee was the only path that ever created them.
        List<CommitteeMember> members = List.of(
                seat(thesis, mentor, MemberRole.MENTOR_MEMBER, true),
                seat(thesis, p1, MemberRole.FORMAL_MEMBER, false),
                seat(thesis, p2, MemberRole.FORMAL_MEMBER, false));

        when(securityUtils.getCurrentUser()).thenReturn(admin);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(committeeRepository.countByThesis(thesis)).thenReturn(3L);
        when(committeeRepository.findByThesis(thesis)).thenReturn(members);

        assertThrows(BadRequestException.class, () -> committeeService.approveCommittee(thesis.getId()));
        assertEquals(ThesisStatus.MENTOR_APPROVED, thesis.getStatus());
    }

    @Test
    @DisplayName("B. 3 normal voting members (0 external) → approveCommittee accepts (pre-existing behavior)")
    void approve_threeVotingMembers_accepted() {
        User admin = user(Role.STUDENT_SERVICE);
        User mentor = user(Role.MENTOR);
        Thesis thesis = mentorApprovedThesis(user(Role.STUDENT), mentor);
        User p1 = user(Role.MENTOR), p2 = user(Role.MENTOR);

        List<CommitteeMember> members = List.of(
                seat(thesis, mentor, MemberRole.MENTOR_MEMBER, false),
                seat(thesis, p1, MemberRole.FORMAL_MEMBER, false),
                seat(thesis, p2, MemberRole.FORMAL_MEMBER, false));

        when(securityUtils.getCurrentUser()).thenReturn(admin);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(committeeRepository.countByThesis(thesis)).thenReturn(3L);
        when(committeeRepository.findByThesis(thesis)).thenReturn(members);

        List<CommitteeMemberResponse> result = committeeService.approveCommittee(thesis.getId());
        assertEquals(3, result.size());
        assertEquals(ThesisStatus.COMMITTEE_REVIEW, thesis.getStatus());
    }

    // =========================================================================
    // proposeCommittee — composition validated at creation time
    // =========================================================================

    @Test
    @DisplayName("2 proposed professors, no external id → creates a 3-member committee, all voting")
    void propose_twoProfessorsNoExternal_createsThreeVotingMembers() {
        User mentor = user(Role.MENTOR);
        User student = user(Role.STUDENT);
        Thesis thesis = mentorApprovedThesis(student, mentor);
        User p1 = user(Role.MENTOR), p2 = user(Role.MENTOR);

        ProposeCommitteeRequest req = new ProposeCommitteeRequest();
        req.setProfessorIds(List.of(p1.getId(), p2.getId()));

        when(securityUtils.getCurrentUser()).thenReturn(mentor);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(committeeRepository.countByThesis(thesis)).thenReturn(0L);
        when(userRepository.findById(p1.getId())).thenReturn(Optional.of(p1));
        when(userRepository.findById(p2.getId())).thenReturn(Optional.of(p2));
        when(committeeRepository.save(any(CommitteeMember.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        List<CommitteeMemberResponse> result = committeeService.proposeCommittee(thesis.getId(), req);

        assertEquals(3, result.size());
        assertTrue(result.stream().noneMatch(CommitteeMemberResponse::isExternalNonVoting),
                "no member should be external when only 2 professors were proposed");
    }

    @Test
    @DisplayName("3 proposed professors + externalProfessorId → creates a 4-member committee "
            + "with exactly 1 external non-voting member")
    void propose_threeProfessorsWithExternal_createsFourMembersOneExternal() {
        User mentor = user(Role.MENTOR);
        User student = user(Role.STUDENT);
        Thesis thesis = mentorApprovedThesis(student, mentor);
        User p1 = user(Role.MENTOR), p2 = user(Role.MENTOR), external = user(Role.MENTOR);

        ProposeCommitteeRequest req = new ProposeCommitteeRequest();
        req.setProfessorIds(List.of(p1.getId(), p2.getId(), external.getId()));
        req.setExternalProfessorId(external.getId());

        when(securityUtils.getCurrentUser()).thenReturn(mentor);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(committeeRepository.countByThesis(thesis)).thenReturn(0L);
        when(userRepository.findById(p1.getId())).thenReturn(Optional.of(p1));
        when(userRepository.findById(p2.getId())).thenReturn(Optional.of(p2));
        when(userRepository.findById(external.getId())).thenReturn(Optional.of(external));
        when(committeeRepository.save(any(CommitteeMember.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        List<CommitteeMemberResponse> result = committeeService.proposeCommittee(thesis.getId(), req);

        assertEquals(4, result.size());
        long externalCount = result.stream().filter(CommitteeMemberResponse::isExternalNonVoting).count();
        assertEquals(1, externalCount);
        CommitteeMemberResponse externalResponse = result.stream()
                .filter(CommitteeMemberResponse::isExternalNonVoting).findFirst().orElseThrow();
        assertEquals(external.getId(), externalResponse.getProfessorId());
    }

    @Test
    @DisplayName("3 proposed professors but NO externalProfessorId → rejected "
            + "(a 4-member committee must designate exactly one external member)")
    void propose_threeProfessorsNoExternalId_rejected() {
        User mentor = user(Role.MENTOR);
        Thesis thesis = mentorApprovedThesis(user(Role.STUDENT), mentor);
        User p1 = user(Role.MENTOR), p2 = user(Role.MENTOR), p3 = user(Role.MENTOR);

        ProposeCommitteeRequest req = new ProposeCommitteeRequest();
        req.setProfessorIds(List.of(p1.getId(), p2.getId(), p3.getId()));
        // externalProfessorId left null

        when(securityUtils.getCurrentUser()).thenReturn(mentor);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(committeeRepository.countByThesis(thesis)).thenReturn(0L);

        assertThrows(BadRequestException.class, () -> committeeService.proposeCommittee(thesis.getId(), req));
        verify(committeeRepository, never()).save(any());
    }

    @Test
    @DisplayName("2 proposed professors + externalProfessorId set → rejected "
            + "(a 3-member committee cannot have an external member)")
    void propose_twoProfessorsWithExternalId_rejected() {
        User mentor = user(Role.MENTOR);
        Thesis thesis = mentorApprovedThesis(user(Role.STUDENT), mentor);
        User p1 = user(Role.MENTOR), p2 = user(Role.MENTOR);

        ProposeCommitteeRequest req = new ProposeCommitteeRequest();
        req.setProfessorIds(List.of(p1.getId(), p2.getId()));
        req.setExternalProfessorId(p1.getId());

        when(securityUtils.getCurrentUser()).thenReturn(mentor);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(committeeRepository.countByThesis(thesis)).thenReturn(0L);

        assertThrows(BadRequestException.class, () -> committeeService.proposeCommittee(thesis.getId(), req));
        verify(committeeRepository, never()).save(any());
    }

    @Test
    @DisplayName("externalProfessorId not among the proposed professorIds → rejected")
    void propose_externalIdNotInList_rejected() {
        User mentor = user(Role.MENTOR);
        Thesis thesis = mentorApprovedThesis(user(Role.STUDENT), mentor);
        User p1 = user(Role.MENTOR), p2 = user(Role.MENTOR), p3 = user(Role.MENTOR);

        ProposeCommitteeRequest req = new ProposeCommitteeRequest();
        req.setProfessorIds(List.of(p1.getId(), p2.getId(), p3.getId()));
        req.setExternalProfessorId(UUID.randomUUID()); // a random id, not one of the three

        when(securityUtils.getCurrentUser()).thenReturn(mentor);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(committeeRepository.countByThesis(thesis)).thenReturn(0L);

        assertThrows(BadRequestException.class, () -> committeeService.proposeCommittee(thesis.getId(), req));
        verify(committeeRepository, never()).save(any());
    }

    @Test
    @DisplayName("G (propose-time). externalProfessorId equal to the mentor's own id → rejected "
            + "(the mentor is auto-added and can never be re-added/marked external)")
    void propose_externalIdIsMentor_rejected() {
        User mentor = user(Role.MENTOR);
        Thesis thesis = mentorApprovedThesis(user(Role.STUDENT), mentor);
        User p1 = user(Role.MENTOR), p2 = user(Role.MENTOR);

        // Attempting to (mis)use the mentor's own id as a proposed professor and mark them
        // external. professorIds must contain 3 entries to reach the per-professor loop.
        ProposeCommitteeRequest req = new ProposeCommitteeRequest();
        req.setProfessorIds(List.of(p1.getId(), p2.getId(), mentor.getId()));
        req.setExternalProfessorId(mentor.getId());

        when(securityUtils.getCurrentUser()).thenReturn(mentor);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(committeeRepository.countByThesis(thesis)).thenReturn(0L);
        when(userRepository.findById(p1.getId())).thenReturn(Optional.of(p1));
        when(userRepository.findById(p2.getId())).thenReturn(Optional.of(p2));
        when(userRepository.findById(mentor.getId())).thenReturn(Optional.of(mentor));
        when(committeeRepository.save(any(CommitteeMember.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        assertThrows(BadRequestException.class, () -> committeeService.proposeCommittee(thesis.getId(), req));
    }

    @Test
    @DisplayName("H. Existing duplicate-professor validation still works with the 3-professor shape")
    void propose_duplicateProfessorAmongThree_rejected() {
        User mentor = user(Role.MENTOR);
        Thesis thesis = mentorApprovedThesis(user(Role.STUDENT), mentor);
        User p1 = user(Role.MENTOR), external = user(Role.MENTOR);

        ProposeCommitteeRequest req = new ProposeCommitteeRequest();
        req.setProfessorIds(List.of(p1.getId(), p1.getId(), external.getId()));
        req.setExternalProfessorId(external.getId());

        when(securityUtils.getCurrentUser()).thenReturn(mentor);
        when(thesisRepository.findById(thesis.getId())).thenReturn(Optional.of(thesis));
        when(committeeRepository.countByThesis(thesis)).thenReturn(0L);
        when(userRepository.findById(p1.getId())).thenReturn(Optional.of(p1));
        when(committeeRepository.save(any(CommitteeMember.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // The pre-existing duplicate check: once p1 has been added (first occurrence in the
        // list), existsByThesisAndProfessor reflects that on the second occurrence — the loop
        // throws there, before ever reaching the third (external) entry in the list, so no
        // stubbing is needed for the external professor's lookup.
        when(committeeRepository.existsByThesisAndProfessor(thesis, p1)).thenReturn(false, true);

        assertThrows(BadRequestException.class, () -> committeeService.proposeCommittee(thesis.getId(), req));
    }
}
