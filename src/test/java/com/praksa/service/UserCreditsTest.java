package com.praksa.service;

import com.praksa.dto.user.UpdateCreditsRequest;
import com.praksa.dto.user.UserDetailResponse;
import com.praksa.exception.BadRequestException;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.User;
import com.praksa.model.enums.Role;
import com.praksa.repository.UserRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.service.impl.UserServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for roadmap Item #5 — server-side authorization of the credit-update
 * endpoint. A STUDENT must never be able to award themselves (or anyone) credits.
 */
@ExtendWith(MockitoExtension.class)
class UserCreditsTest {

    @Mock private UserRepository userRepository;
    @Mock private SecurityUtils securityUtils;

    @InjectMocks private UserServiceImpl userService;

    private User user(Role role) {
        return User.builder().id(UUID.randomUUID()).email(role + "@t.com")
                .fullName(role + " User").role(role).build();
    }

    private UpdateCreditsRequest req(int credits) {
        UpdateCreditsRequest r = new UpdateCreditsRequest();
        r.setCredits(credits);
        return r;
    }

    @Test
    @DisplayName("A STUDENT cannot award credits (to themselves or anyone) — 403, no save")
    void student_cannotSelfAwardCredits() {
        User student = user(Role.STUDENT);
        when(securityUtils.getCurrentUser()).thenReturn(student);

        // Student tries to give some (their own or another) account 500 credits.
        assertThrows(UnauthorizedException.class,
                () -> userService.updateCredits(UUID.randomUUID(), req(500)));

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("A COMMITTEE user cannot modify credits — 403, no save")
    void committee_cannotModifyCredits() {
        User committee = user(Role.COMMITTEE);
        when(securityUtils.getCurrentUser()).thenReturn(committee);

        assertThrows(UnauthorizedException.class,
                () -> userService.updateCredits(UUID.randomUUID(), req(300)));

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("STUDENT_SERVICE can set a student's credits")
    void studentService_canSetCredits() {
        User service = user(Role.STUDENT_SERVICE);
        User target = user(Role.STUDENT);
        when(securityUtils.getCurrentUser()).thenReturn(service);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));

        UserDetailResponse res = userService.updateCredits(target.getId(), req(250));

        assertEquals(250, res.getCredits());
        verify(userRepository).save(target);
    }

    @Test
    @DisplayName("Credits cannot be set on a non-student target — 400, no save")
    void studentService_cannotSetCreditsOnNonStudent() {
        User service = user(Role.STUDENT_SERVICE);
        User mentorTarget = user(Role.MENTOR);
        when(securityUtils.getCurrentUser()).thenReturn(service);
        when(userRepository.findById(mentorTarget.getId())).thenReturn(Optional.of(mentorTarget));

        assertThrows(BadRequestException.class,
                () -> userService.updateCredits(mentorTarget.getId(), req(250)));

        verify(userRepository, never()).save(any());
    }
}
