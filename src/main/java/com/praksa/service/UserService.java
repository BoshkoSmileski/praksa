package com.praksa.service;

import com.praksa.dto.user.UpdateCreditsRequest;
import com.praksa.dto.user.UserDetailResponse;
import com.praksa.dto.user.UserSummaryResponse;
import com.praksa.model.enums.Role;

import java.util.List;
import java.util.UUID;

public interface UserService {

    /**
     * Lists users of the given role for pickers/lists, applying purpose-specific
     * authorization and returning only the fields the caller's use case needs.
     *
     * <p>Authorization (the security boundary — enforced BEFORE any repository
     * query so an unauthorized caller can never enumerate users):</p>
     * <ul>
     *   <li>{@code role=STUDENT} — only {@link Role#STUDENT_SERVICE} (credit list),
     *       returns id/fullName/role/email/indexNumber/credits.</li>
     *   <li>{@code role=MENTOR} — STUDENT, MENTOR or STUDENT_SERVICE (mentor picker),
     *       returns id/fullName/role only (no email/indexNumber/credits).</li>
     *   <li>any other requested role — 403 for everyone (no legitimate use case).</li>
     * </ul>
     * Throws {@link com.praksa.exception.UnauthorizedException} (→ HTTP 403) when the
     * caller is not permitted for the requested role.
     */
    List<UserSummaryResponse> getUsersByRole(Role role);

    /**
     * Returns the currently authenticated user's own detail (including credits).
     */
    UserDetailResponse getCurrentUser();

    /**
     * Sets/updates a student's credit balance.
     * Only STUDENT_SERVICE may call this; the target user must be a STUDENT.
     */
    UserDetailResponse updateCredits(UUID userId, UpdateCreditsRequest request);
}
