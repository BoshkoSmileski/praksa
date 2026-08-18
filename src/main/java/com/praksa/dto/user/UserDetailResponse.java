package com.praksa.dto.user;

import com.praksa.model.User;
import com.praksa.model.enums.Role;
import lombok.Getter;

import java.util.UUID;

/**
 * Detailed user DTO for "who am I" and credit-management responses.
 * Includes the student's credit balance (null for non-students or when unset).
 * Never exposes the password hash.
 */
@Getter
public class UserDetailResponse {

    private final UUID id;
    private final String email;
    private final String fullName;
    private final Role role;
    private final String indexNumber;
    private final Integer credits;

    public static UserDetailResponse from(User user) {
        return new UserDetailResponse(user);
    }

    private UserDetailResponse(User user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.fullName = user.getFullName();
        this.role = user.getRole();
        this.indexNumber = user.getIndexNumber();
        this.credits = user.getCredits();
    }
}
