package com.praksa.dto.user;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.praksa.model.User;
import com.praksa.model.enums.Role;
import lombok.Getter;

import java.util.UUID;

/**
 * Slim, PURPOSE-SPECIFIC user DTO for pickers/lists — no password, no timestamps.
 *
 * <p>The endpoint that produces this ({@code GET /api/users?role=}) serves two
 * distinct, separately-authorized use cases, and each gets only the fields it
 * actually needs (P2 user-enumeration / PII-leak fix, 2026-08-17):</p>
 * <ul>
 *   <li>{@link #mentorPicker(User)} — mentor lookup (student choosing a mentor, or
 *       a mentor proposing a committee). Exposes only non-sensitive identity:
 *       {@code id}, {@code fullName}, {@code role}. Email, index number and credits
 *       are deliberately left out.</li>
 *   <li>{@link #studentDetail(User)} — the STUDENT_SERVICE credit-management list.
 *       Adds the student-identifying {@code email} + {@code indexNumber} and the
 *       current {@code credits} so the credit UI needs no second call.</li>
 * </ul>
 *
 * <p>Serialized with {@code @JsonInclude(NON_NULL)}: the mentor-picker shape omits
 * the PII fields entirely from the JSON (they are never sent as {@code null}), so a
 * mentor lookup can never leak a professor's email.</p>
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserSummaryResponse {

    private final UUID id;
    private final String fullName;
    private final Role role;

    // Student-detail-only fields — null (and thus omitted from JSON) for the
    // mentor-picker use case.
    private final String email;
    private final String indexNumber;
    private final Integer credits;

    /**
     * Reduced, safe representation for mentor lookup: identity only, no PII.
     */
    public static UserSummaryResponse mentorPicker(User user) {
        return new UserSummaryResponse(
                user.getId(), user.getFullName(), user.getRole(),
                null, null, null);
    }

    /**
     * Full student summary for the STUDENT_SERVICE credit-management list.
     */
    public static UserSummaryResponse studentDetail(User user) {
        return new UserSummaryResponse(
                user.getId(), user.getFullName(), user.getRole(),
                user.getEmail(), user.getIndexNumber(), user.getCredits());
    }

    private UserSummaryResponse(UUID id, String fullName, Role role,
                                String email, String indexNumber, Integer credits) {
        this.id = id;
        this.fullName = fullName;
        this.role = role;
        this.email = email;
        this.indexNumber = indexNumber;
        this.credits = credits;
    }
}
