package com.praksa.service;

import com.praksa.model.CommitteeMember;
import com.praksa.model.Defense;
import com.praksa.model.DefenseResult;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.MemberRole;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Item #11 — exercises the real OpenHTMLtoPDF rendering path (no mocks), including loading
 * the bundled Cyrillic-capable Noto Sans font. If the font were missing or the renderer
 * misconfigured this test would throw, so it guards against the "Macedonian renders as
 * boxes / render crashes" failure modes. It does not attempt to parse glyphs out of the
 * PDF — it verifies a valid, non-trivial PDF is produced from Cyrillic input.
 */
class DefenseRecordPdfServiceTest {

    private final DefenseRecordPdfService pdfService = new DefenseRecordPdfService();

    private User user(Role role, String name, String index) {
        return User.builder().id(UUID.randomUUID()).email(role + "@t.com")
                .fullName(name).indexNumber(index).role(role).build();
    }

    @Test
    @DisplayName("generate() renders a valid, non-trivial PDF from Cyrillic input")
    void generate_producesValidPdf() {
        User student = user(Role.STUDENT, "Марко Марковски", "12345");
        User mentor = user(Role.MENTOR, "Проф. Ана Петрова", null);
        User member = user(Role.COMMITTEE, "Проф. Иван Стоев", null);

        Thesis thesis = Thesis.builder().id(UUID.randomUUID())
                .title("Систем за управување со дипломски работи")
                .student(student).mentor(mentor)
                .status(ThesisStatus.ARCHIVED)
                .archiveRegistrationNumber("DT-2026-0001")
                .build();

        Defense defense = Defense.builder().id(UUID.randomUUID()).thesis(thesis)
                .room("Б2").scheduledAt(OffsetDateTime.now().minusDays(1)).isCancelled(false).build();

        DefenseResult result = DefenseResult.builder().id(UUID.randomUUID()).defense(defense)
                .grade(10).notes("Одбраната е успешна.\nКомисијата ја оценува со највисока оценка.")
                .recordedBy(member).build();

        List<CommitteeMember> committee = List.of(
                CommitteeMember.builder().thesis(thesis).professor(mentor).memberRole(MemberRole.MENTOR_MEMBER).build(),
                CommitteeMember.builder().thesis(thesis).professor(member).memberRole(MemberRole.FORMAL_MEMBER).build());

        byte[] pdf = pdfService.generate(thesis, defense, result, committee);

        assertNotNull(pdf);
        assertTrue(pdf.length > 1000, "PDF should be non-trivial in size, was: " + pdf.length);
        String header = new String(pdf, 0, 5, StandardCharsets.ISO_8859_1);
        assertTrue(header.startsWith("%PDF-"), "Output should be a PDF, header was: " + header);
    }

    @Test
    @DisplayName("generate() tolerates an empty committee list")
    void generate_emptyCommittee() {
        User student = user(Role.STUDENT, "Елена Николова", "67890");
        Thesis thesis = Thesis.builder().id(UUID.randomUUID())
                .title("Тема без ментор").student(student).status(ThesisStatus.ARCHIVED).build();
        Defense defense = Defense.builder().id(UUID.randomUUID()).thesis(thesis)
                .room("В3").scheduledAt(OffsetDateTime.now()).isCancelled(false).build();
        DefenseResult result = DefenseResult.builder().id(UUID.randomUUID()).defense(defense)
                .grade(8).recordedBy(student).build();

        byte[] pdf = pdfService.generate(thesis, defense, result, List.of());

        assertNotNull(pdf);
        assertTrue(pdf.length > 500);
        assertTrue(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1).startsWith("%PDF-"));
    }
}
