package com.praksa.config;

import com.praksa.model.User;
import com.praksa.model.enums.Role;
import com.praksa.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the database with one user per role on first startup.
 * Safe to leave enabled — it checks if users already exist before inserting.
 *
 * Test credentials (all passwords are "password123"):
 *
 *   STUDENT          student@test.com      index: 2024/001
 *   MENTOR           mentor@test.com
 *   MENTOR           mentor2@test.com      (second mentor for committee testing)
 *   MENTOR           mentor3@test.com      (third mentor for committee testing)
 *   STUDENT_SERVICE  service@test.com
 *   COMMITTEE        committee@test.com
 *   ARCHIVE          archive@test.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private static final String DEFAULT_PASSWORD = "password123";

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            log.info("DataInitializer: users already exist, skipping seed.");
            return;
        }

        log.info("DataInitializer: seeding test users...");

        createUser("Test Student",    "student@test.com",   Role.STUDENT,   "2024/001");
        createUser("Test Mentor",     "mentor@test.com",    Role.MENTOR,    null);
        createUser("Test Mentor 2",   "mentor2@test.com",   Role.MENTOR,    null);
        createUser("Test Mentor 3",   "mentor3@test.com",   Role.MENTOR,    null);
        createUser("Test Service",    "service@test.com",   Role.STUDENT_SERVICE, null);
        createUser("Test Committee",  "committee@test.com", Role.COMMITTEE, null);
        createUser("Test Archive",    "archive@test.com",   Role.ARCHIVE,   null);

        log.info("""

                ╔══════════════════════════════════════════════════════════╗
                ║              TEST USERS CREATED                         ║
                ╠══════════════════════════════════════════════════════════╣
                ║  All passwords: password123                             ║
                ║                                                          ║
                ║  STUDENT          → student@test.com  (index: 2024/001) ║
                ║  MENTOR           → mentor@test.com                     ║
                ║  MENTOR 2         → mentor2@test.com                    ║
                ║  MENTOR 3         → mentor3@test.com                    ║
                ║  STUDENT_SERVICE  → service@test.com                    ║
                ║  COMMITTEE        → committee@test.com                  ║
                ║  ARCHIVE          → archive@test.com                    ║
                ╚══════════════════════════════════════════════════════════╝
                """);
    }

    private void createUser(String fullName, String email, Role role, String indexNumber) {
        User user = User.builder()
                .fullName(fullName)
                .email(email)
                .passwordHash(passwordEncoder.encode(DEFAULT_PASSWORD))
                .role(role)
                .indexNumber(indexNumber)
                .build();
        userRepository.save(user);
    }
}
