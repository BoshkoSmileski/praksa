package com.praksa.security;

import com.praksa.exception.UnauthorizedException;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.Role;
import com.praksa.repository.CommitteeMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for "who may READ a thesis and its related resources".
 *
 * <p>This is the same policy that was already encoded, independently, in
 * {@code ThesisVersionServiceImpl.checkThesisReadAccess} and
 * {@code DefenseResultServiceImpl.requireRecordAccess}. It is factored out here so the
 * thesis-level read endpoints (thesis, status history, committee, defenses, defense result)
 * all authorize against the SAME underlying thesis rule instead of relying on
 * {@code SecurityConfig.anyRequest().authenticated()} — which would let any logged-in user
 * read another student's thesis, PII, committee, schedule, or grade by guessing a UUID.
 *
 * <p>Legitimate thesis-level readers:
 * <ul>
 *   <li>the STUDENT who owns the thesis;</li>
 *   <li>the assigned MENTOR;</li>
 *   <li>any user (MENTOR- or COMMITTEE-role) who holds a CommitteeMember seat on THIS thesis —
 *       membership is always checked against the requested thesis, never inferred from the role;</li>
 *   <li>STUDENT_SERVICE (administrative oversight of the whole workflow);</li>
 *   <li>ARCHIVE (official record keeping).</li>
 * </ul>
 *
 * <p>Everyone else — including a MENTOR who is not assigned and not seated, and a COMMITTEE-role
 * user with no seat on this thesis — is denied. Having a privileged role is NOT, by itself,
 * access to an arbitrary thesis.
 */
@Component
@RequiredArgsConstructor
public class ThesisReadAccessPolicy {

    private final CommitteeMemberRepository committeeMemberRepository;

    /**
     * @return true iff {@code user} is allowed to read {@code thesis} under the policy above.
     */
    public boolean hasReadAccess(Thesis thesis, User user) {
        if (user.getRole() == Role.STUDENT
                && thesis.getStudent().getId().equals(user.getId())) {
            return true;
        }
        if (user.getRole() == Role.MENTOR
                && thesis.getMentor() != null
                && thesis.getMentor().getId().equals(user.getId())) {
            return true;
        }
        if (user.getRole() == Role.STUDENT_SERVICE) {
            return true;
        }
        if (user.getRole() == Role.ARCHIVE) {
            return true;
        }
        // Seat on THIS thesis's committee — checked against the requested thesis, so a
        // COMMITTEE/MENTOR user cannot read a thesis they don't actually sit on.
        return committeeMemberRepository.existsByThesisAndProfessor(thesis, user);
    }

    /**
     * Throws {@link UnauthorizedException} (→ 403) if {@code user} may not read {@code thesis}.
     */
    public void requireReadAccess(Thesis thesis, User user) {
        if (!hasReadAccess(thesis, user)) {
            throw new UnauthorizedException("Немате пристап до оваа дипломска работа.");
        }
    }
}
