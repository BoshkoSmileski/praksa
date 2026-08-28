package com.praksa.service.impl;

import com.praksa.dto.user.UpdateCreditsRequest;
import com.praksa.dto.user.UserDetailResponse;
import com.praksa.dto.user.UserSummaryResponse;
import com.praksa.exception.BadRequestException;
import com.praksa.exception.ResourceNotFoundException;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.User;
import com.praksa.model.enums.Role;
import com.praksa.repository.UserRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final SecurityUtils securityUtils;

    @Override
    @Transactional(readOnly = true)
    public List<UserSummaryResponse> getUsersByRole(Role role) {
        // Authorization is the security boundary and runs BEFORE any repository
        // query, so a denied caller can never enumerate users. Each requested role
        // is a distinct, purpose-specific use case with its own allowed callers and
        // its own minimal response shape — swapping role= cannot widen access or
        // leak PII, because a role the caller is not authorized for throws 403.
        User caller = securityUtils.getCurrentUser();

        switch (role) {
            case STUDENT -> {
                // Credit management: only Student Service lists students, and it
                // receives the identity/credit fields the credit UI needs.
                requireRole(caller, Role.STUDENT_SERVICE);
                return userRepository.findByRole(Role.STUDENT).stream()
                        .map(UserSummaryResponse::studentDetail)
                        .toList();
            }
            case MENTOR -> {
                // Mentor picker: a student choosing a mentor, or a mentor proposing
                // a committee (STUDENT_SERVICE may also look mentors up). Returns
                // only non-sensitive identity — no email/index/credits.
                requireAnyRole(caller, Role.STUDENT, Role.MENTOR, Role.STUDENT_SERVICE);
                return userRepository.findByRole(Role.MENTOR).stream()
                        .map(UserSummaryResponse::mentorPicker)
                        .toList();
            }
            default -> throw new UnauthorizedException(
                    "Прикажување на корисници со улога " + role + " не е дозволено.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetailResponse getCurrentUser() {
        return UserDetailResponse.from(securityUtils.getCurrentUser());
    }

    @Override
    @Transactional
    public UserDetailResponse updateCredits(UUID userId, UpdateCreditsRequest request) {
        // Server-side authorization: only Student Service may change credits.
        // This is the security boundary — a STUDENT must never be able to award
        // themselves credits, so we never trust the client and never expose a
        // self-service credit endpoint.
        User caller = securityUtils.getCurrentUser();
        if (caller.getRole() != Role.STUDENT_SERVICE) {
            throw new UnauthorizedException("Само Студентската служба може да ги менува кредитите на студентите.");
        }

        User target = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Корисникот не е пронајден (id: " + userId + ")."));

        // Credits are a student-only concept.
        if (target.getRole() != Role.STUDENT) {
            throw new BadRequestException("Кредити можат да се поставуваат само за студенти.");
        }

        target.setCredits(request.getCredits());
        userRepository.save(target);

        return UserDetailResponse.from(target);
    }

    /**
     * Throws {@link UnauthorizedException} (→ HTTP 403) unless the caller has the
     * required role. Mirrors the service-layer {@code requireRole} idiom used in
     * ThesisServiceImpl / NotificationServiceImpl.
     */
    private void requireRole(User caller, Role required) {
        if (caller.getRole() != required) {
            throw new UnauthorizedException("Бара улога " + required);
        }
    }

    /**
     * Throws {@link UnauthorizedException} (→ HTTP 403) unless the caller's role is
     * one of the allowed roles.
     */
    private void requireAnyRole(User caller, Role... allowed) {
        for (Role r : allowed) {
            if (caller.getRole() == r) {
                return;
            }
        }
        throw new UnauthorizedException("Не е дозволено за улога " + caller.getRole());
    }
}
