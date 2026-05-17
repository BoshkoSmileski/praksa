package com.praksa.dto.user;

import com.praksa.model.User;
import com.praksa.model.enums.Role;
import lombok.Getter;

import java.util.UUID;

/**
 * Slim user DTO — no password, no timestamps.
 * Used in dropdowns, pickers, lists where we just need to identify a user.
 */
@Getter
public class UserSummaryResponse {

    private final UUID id;
    private final String email;
    private final String fullName;
    private final Role role;

    public static UserSummaryResponse from(User user) {
        return new UserSummaryResponse(user);
    }

    private UserSummaryResponse(User user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.fullName = user.getFullName();
        this.role = user.getRole();
    }
}
