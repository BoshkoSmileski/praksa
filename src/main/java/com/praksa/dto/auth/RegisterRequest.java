package com.praksa.dto.auth;

import com.praksa.model.enums.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterRequest {

    @Email(message = "Invalid email format")
    @NotBlank(message = "Email is required")
    private String email;

    @NotBlank(message = "Password is required")
    private String password;

    @NotBlank(message = "Full name is required")
    private String fullName;

    // SECURITY (BUG-2 / P0.2): this field is IGNORED by the server. Public registration
    // always creates a STUDENT (see AuthServiceImpl.register). It is kept only for
    // backward compatibility with existing clients that still send it; a caller can no
    // longer choose a privileged role. @NotNull is retained so the request shape is
    // unchanged for current clients.
    @NotNull(message = "Role is required")
    private Role role;

    // Required for every public registration (all public registrations are students).
    private String indexNumber;
}
