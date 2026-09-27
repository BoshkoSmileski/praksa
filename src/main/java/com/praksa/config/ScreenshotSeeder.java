package com.praksa.config;

import com.praksa.model.CommitteeMember;
import com.praksa.model.Defense;
import com.praksa.model.DefenseResult;
import com.praksa.model.Thesis;
import com.praksa.model.ThesisStatusHistory;
import com.praksa.model.ThesisVersion;
import com.praksa.model.User;
import com.praksa.model.enums.MemberRole;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.CommitteeMemberRepository;
import com.praksa.repository.DefenseRepository;
import com.praksa.repository.DefenseResultRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.repository.ThesisVersionRepository;
import com.praksa.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ONE-TIME, EASILY-REMOVABLE screenshot/demo seeder.
 *
 * <p><b>Not part of the application.</b> This class exists only to populate the local
 * database with realistic, ready-to-photograph example theses. It is guarded by
 * {@code @Profile("seed")}, so it NEVER runs in production, in tests, or during normal
 * {@code spring-boot:run} — it only activates when the {@code seed} profile is explicitly
 * enabled. To remove it after taking screenshots, simply delete this single file.
 *
 * <p><b>How to run it (local dev only):</b>
 * <pre>
 *   mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=seed"
 *   # or:  set SPRING_PROFILES_ACTIVE=seed  &amp;&amp;  mvnw.cmd spring-boot:run
 *   # or on a built jar:  java -jar target/praksa-0.0.1-SNAPSHOT.jar --spring.profiles.active=seed
 * </pre>
 *
 * <p><b>What it does NOT do:</b> it does not call any REST endpoint and does not touch
 * {@code ThesisServiceImpl}/{@code DefenseResultServiceImpl} (so it bypasses the 14/5/45-day
 * timing rules); it writes rows directly through the existing repositories. It is idempotent —
 * if the marker student already exists it skips, so a second run with the profile on inserts
 * nothing new.
 *
 * <p>Data created:
 * <ul>
 *   <li>THREE fully {@code ARCHIVED} theses (distinct students, a 3-member committee each, one
 *       {@code isFinal} version, a past-dated {@code Defense}, a {@code DefenseResult} of 10 / 8 / 7,
 *       an {@code archiveRegistrationNumber} continuing the {@code DT-2026-####} sequence, and a few
 *       {@code ThesisStatusHistory} rows);</li>
 *   <li>ONE additional thesis in {@code DEFENSE_SCHEDULED} (full committee, a future-dated
 *       {@code Defense}, no result) — so grade recording can be tested again without consuming any
 *       pre-existing example.</li>
 * </ul>
 */
@Slf4j
@Component
@Profile("seed")
@RequiredArgsConstructor
public class ScreenshotSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final ThesisRepository thesisRepository;
    private final ThesisVersionRepository thesisVersionRepository;
    private final CommitteeMemberRepository committeeMemberRepository;
    private final DefenseRepository defenseRepository;
    private final DefenseResultRepository defenseResultRepository;
    private final ThesisStatusHistoryRepository thesisStatusHistoryRepository;
    private final PasswordEncoder passwordEncoder;

    private static final String PWD = "password123";
    /** Presence of this student means the seed already ran — used for idempotency. */
    private static final String MARKER_EMAIL = "ana.stefanovska@seed.test";

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByEmail(MARKER_EMAIL)) {
            log.info("ScreenshotSeeder: marker {} already exists — demo data already seeded, skipping.", MARKER_EMAIL);
            return;
        }
        log.info("ScreenshotSeeder: profile 'seed' active — inserting screenshot demo data directly via repositories...");

        OffsetDateTime now = OffsetDateTime.now();

        // Shared staff — reuse the standard test accounts if present, otherwise create them so the
        // seed is self-contained regardless of ApplicationRunner ordering.
        User mentor = fetchOrCreate("Проф. д-р Ментор Менторски", "mentor@test.com", Role.MENTOR, null, null);
        User profA = fetchOrCreate("Проф. д-р Ана Комисиска", "mentor2@test.com", Role.MENTOR, null, null);
        User profB = fetchOrCreate("Проф. д-р Борис Комисиски", "mentor3@test.com", Role.MENTOR, null, null);
        User service = fetchOrCreate("Студентска служба", "service@test.com", Role.STUDENT_SERVICE, null, null);

        List<String> createdEmails = new ArrayList<>();
        List<String> createdReg = new ArrayList<>();

        // Continue the DT-2026-#### sequence after whatever already exists.
        int base = (int) thesisRepository.countByArchiveRegistrationNumberStartingWith("DT-2026-");

        // ---- THREE ARCHIVED theses (grades 10, 8, 7) --------------------------------------------
        String[][] archived = {
                {"Ана Стефановска", MARKER_EMAIL, "2021/101", "260",
                        "Систем за управување со библиотека базиран на микросервиси", "10"},
                {"Марко Петровски", "marko.petrovski@seed.test", "2021/102", "245",
                        "Мобилна апликација за следење на физичка активност", "8"},
                {"Елена Николовска", "elena.nikolovska@seed.test", "2021/103", "300",
                        "Веб-платформа за онлајн учење со систем за препораки", "7"},
        };

        for (int i = 0; i < archived.length; i++) {
            String[] row = archived[i];
            String regNumber = String.format("DT-2026-%04d", base + i + 1);

            User student = createStudent(row[0], row[1], row[2], Integer.parseInt(row[3]));

            OffsetDateTime createdAt = now.minusMonths(3).plusDays(i);
            OffsetDateTime reviewStarted = now.minusDays(40 - i);
            OffsetDateTime defenseAt = now.minusDays(14 - i);   // past defense
            OffsetDateTime archivedAt = now.minusDays(10 - i);

            Thesis thesis = thesisRepository.save(Thesis.builder()
                    .student(student)
                    .mentor(mentor)
                    .title(row[4])
                    .status(ThesisStatus.ARCHIVED)
                    .createdAt(createdAt)
                    .submissionDeadline(createdAt.plusMonths(1))
                    .applicationSubmittedAt(now.minusMonths(2))
                    .committeeReviewStartedAt(reviewStarted)
                    .lastVersionSubmittedAt(now.minusMonths(1))
                    .defenseDeadline(now.minusDays(5))
                    .archiveRegistrationNumber(regNumber)
                    .archiveDate(archivedAt)
                    .archivedBy(profA)
                    .archiveNotes("Успешно одбранета и архивирана дипломска работа (демо податоци за преглед).")
                    .build());

            saveCommittee(thesis, mentor, profA, profB, service, reviewStarted);

            thesisVersionRepository.save(ThesisVersion.builder()
                    .thesis(thesis)
                    .versionNumber(1)
                    .pdfUrl("seed/final/" + row[2].replace('/', '-') + "-final.pdf")
                    .isFinal(true)
                    .uploadedAt(now.minusMonths(1))
                    .build());

            Defense defense = defenseRepository.save(Defense.builder()
                    .thesis(thesis)
                    .room("Сала " + (100 + i))
                    .scheduledAt(defenseAt)
                    .isCancelled(false)
                    .createdAt(defenseAt.minusDays(7))
                    .build());

            defenseResultRepository.save(DefenseResult.builder()
                    .defense(defense)
                    .grade(Integer.parseInt(row[5]))
                    .notes("Одбраната е успешно положена.")
                    .recordedBy(profA)
                    .recordedAt(defenseAt)
                    .build());

            // 3 key status-history transitions
            saveHistory(thesis, null, ThesisStatus.PENDING_ELIGIBILITY_CHECK, null, createdAt);
            saveHistory(thesis, ThesisStatus.COMMITTEE_REVIEW, ThesisStatus.COMMITTEE_ACCEPTED, service, reviewStarted.plusDays(5));
            saveHistory(thesis, ThesisStatus.DEFENSE_SCHEDULED, ThesisStatus.ARCHIVED, profA, archivedAt);

            createdEmails.add(row[1]);
            createdReg.add(regNumber);
        }

        // ---- ONE DEFENSE_SCHEDULED thesis (no result yet — reusable for grade-entry tests) -------
        String scheduledEmail = "dejan.jovanovski@seed.test";
        User dejan = createStudent("Дејан Јовановски", scheduledEmail, "2021/104", 250);

        OffsetDateTime schedCreatedAt = now.minusMonths(1);
        OffsetDateTime schedReviewStarted = now.minusDays(12);
        OffsetDateTime futureDefense = now.plusDays(10);

        Thesis scheduled = thesisRepository.save(Thesis.builder()
                .student(dejan)
                .mentor(mentor)
                .title("Анализа на безбедносни ранливости во веб-апликации")
                .status(ThesisStatus.DEFENSE_SCHEDULED)
                .createdAt(schedCreatedAt)
                .submissionDeadline(schedCreatedAt.plusMonths(1))
                .applicationSubmittedAt(now.minusDays(20))
                .committeeReviewStartedAt(schedReviewStarted)
                .lastVersionSubmittedAt(now.minusDays(15))
                .defenseDeadline(now.plusDays(20))
                .build());

        saveCommittee(scheduled, mentor, profA, profB, service, schedReviewStarted);

        thesisVersionRepository.save(ThesisVersion.builder()
                .thesis(scheduled)
                .versionNumber(1)
                .pdfUrl("seed/final/2021-104-final.pdf")
                .isFinal(true)
                .uploadedAt(now.minusDays(15))
                .build());

        defenseRepository.save(Defense.builder()
                .thesis(scheduled)
                .room("Сала 200")
                .scheduledAt(futureDefense)
                .isCancelled(false)
                .createdAt(now.minusDays(2))
                .build());

        saveHistory(scheduled, null, ThesisStatus.PENDING_ELIGIBILITY_CHECK, null, schedCreatedAt);
        saveHistory(scheduled, ThesisStatus.PENDING_DEFENSE_SCHEDULING, ThesisStatus.DEFENSE_SCHEDULED, service, now.minusDays(2));

        createdEmails.add(scheduledEmail);

        // ---- Console summary --------------------------------------------------------------------
        StringBuilder sb = new StringBuilder("\n");
        sb.append("╔════════════════════════════════════════════════════════════════════╗\n");
        sb.append("║  ScreenshotSeeder — НОВИ ДЕМО ПОДАТОЦИ СОЗДАДЕНИ (лозинка: ").append(PWD).append(") ║\n");
        sb.append("╠════════════════════════════════════════════════════════════════════╣\n");
        sb.append("║  АРХИВИРАНИ дипломски (статус ARCHIVED):                            ║\n");
        sb.append(String.format("║   1) %-30s оценка 10  %s ║%n", "ana.stefanovska@seed.test", createdReg.get(0)));
        sb.append(String.format("║   2) %-30s оценка  8  %s ║%n", "marko.petrovski@seed.test", createdReg.get(1)));
        sb.append(String.format("║   3) %-30s оценка  7  %s ║%n", "elena.nikolovska@seed.test", createdReg.get(2)));
        sb.append("║                                                                    ║\n");
        sb.append("║  ЗАКАЖАНА одбрана (статус DEFENSE_SCHEDULED, без оценка):           ║\n");
        sb.append(String.format("║   4) %-30s одбрана за %s ║%n", scheduledEmail, futureDefense.toLocalDate()));
        sb.append("╚════════════════════════════════════════════════════════════════════╝");
        log.info(sb.toString());

        log.info("ScreenshotSeeder: нови е-пошти = {}", createdEmails);
        log.info("ScreenshotSeeder: нови архивски броеви = {}", createdReg);
    }

    // -------------------------------------------------------------------------

    private User fetchOrCreate(String fullName, String email, Role role, String indexNumber, Integer credits) {
        return userRepository.findByEmail(email).orElseGet(() -> userRepository.save(User.builder()
                .fullName(fullName)
                .email(email)
                .passwordHash(passwordEncoder.encode(PWD))
                .role(role)
                .indexNumber(indexNumber)
                .credits(credits)
                .build()));
    }

    private User createStudent(String fullName, String email, String indexNumber, Integer credits) {
        return userRepository.save(User.builder()
                .fullName(fullName)
                .email(email)
                .passwordHash(passwordEncoder.encode(PWD))
                .role(Role.STUDENT)
                .indexNumber(indexNumber)
                .credits(credits)
                .build());
    }

    /** Mentor (voting MENTOR_MEMBER) + two voting FORMAL_MEMBERs = a standard 3-member committee. */
    private void saveCommittee(Thesis thesis, User mentor, User profA, User profB, User approver, OffsetDateTime approvedAt) {
        committeeMemberRepository.save(CommitteeMember.builder()
                .thesis(thesis).professor(mentor).memberRole(MemberRole.MENTOR_MEMBER)
                .proposedBy(mentor).approvedBy(approver).approvedAt(approvedAt)
                .isExternalNonVoting(false).build());
        committeeMemberRepository.save(CommitteeMember.builder()
                .thesis(thesis).professor(profA).memberRole(MemberRole.FORMAL_MEMBER)
                .proposedBy(mentor).approvedBy(approver).approvedAt(approvedAt)
                .isExternalNonVoting(false).build());
        committeeMemberRepository.save(CommitteeMember.builder()
                .thesis(thesis).professor(profB).memberRole(MemberRole.FORMAL_MEMBER)
                .proposedBy(mentor).approvedBy(approver).approvedAt(approvedAt)
                .isExternalNonVoting(false).build());
    }

    private void saveHistory(Thesis thesis, ThesisStatus oldStatus, ThesisStatus newStatus, User changedBy, OffsetDateTime changedAt) {
        thesisStatusHistoryRepository.save(ThesisStatusHistory.builder()
                .thesis(thesis)
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .changedBy(changedBy)
                .changedAt(changedAt)
                .build());
    }
}
