package com.praksa.service;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.praksa.model.CommitteeMember;
import com.praksa.model.Defense;
import com.praksa.model.DefenseResult;
import com.praksa.model.Thesis;
import com.praksa.model.enums.MemberRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Generates the official defense record ("записник за одбрана") PDF.
 *
 * Approach mirrors {@link ApplicationPdfService}: build a small self-contained HTML
 * document with the defense data filled in and hand it to OpenHTMLtoPDF (the project's
 * existing PDF engine — no second framework is introduced). Unlike the application form,
 * this record is generated on demand and streamed straight back to the caller as bytes;
 * it is NOT persisted to disk (there is nothing to re-download later that a fresh render
 * would not reproduce, and the archive registration number already lives on the thesis).
 *
 * Cyrillic support: the base-14 PDF fonts (Helvetica) do NOT contain Cyrillic glyphs, so
 * the document is typeset in Noto Sans (SIL Open Font License), bundled under
 * src/main/resources/fonts/. Both the regular (400) and bold (700) weights are registered
 * so headings render as real bold rather than faux/synthetic bold.
 */
@Slf4j
@Service
public class DefenseRecordPdfService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Render the defense record for the given defense + result and return the PDF bytes.
     * All arguments are expected to be non-null except {@code result} — a null result means
     * the defense has not been graded yet, which the caller (service layer) rejects before
     * reaching here. The committee list may be empty (rendered as "нема податоци").
     */
    public byte[] generate(Thesis thesis, Defense defense, DefenseResult result, List<CommitteeMember> committee) {
        String html = buildHtml(thesis, defense, result, committee);

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            // Register the Cyrillic-capable font in both weights. The FSSupplier reopens the
            // classpath stream lazily, so it is safe to call the loader inside the lambda.
            builder.useFont(() -> loadFont("NotoSans-Regular.ttf"), "Noto Sans",
                    400, BaseRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(() -> loadFont("NotoSans-Bold.ttf"), "Noto Sans",
                    700, BaseRendererBuilder.FontStyle.NORMAL, true);
            builder.withHtmlContent(html, null);
            builder.toStream(os);
            builder.run();
            log.info("Generated defense record PDF for defense {} (thesis {})",
                    defense.getId(), thesis.getId());
            return os.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to render defense record PDF", e);
        }
    }

    private InputStream loadFont(String name) {
        InputStream in = getClass().getResourceAsStream("/fonts/" + name);
        if (in == null) {
            // Fail loud rather than silently produce boxes for Cyrillic text.
            throw new IllegalStateException("Missing bundled font: /fonts/" + name);
        }
        return in;
    }

    private String buildHtml(Thesis thesis, Defense defense, DefenseResult result, List<CommitteeMember> committee) {
        String studentName = escape(thesis.getStudent().getFullName());
        String studentIndex = thesis.getStudent().getIndexNumber() != null
                ? escape(thesis.getStudent().getIndexNumber()) : "—";
        String studentEmail = escape(thesis.getStudent().getEmail());
        String title = escape(thesis.getTitle());
        String mentorName = thesis.getMentor() != null ? escape(thesis.getMentor().getFullName()) : "—";

        OffsetDateTime when = defense.getScheduledAt();
        String date = when != null ? when.format(DATE_FMT) : "—";
        String time = when != null ? when.format(TIME_FMT) : "—";
        String room = escape(defense.getRoom());

        String statusLabel = defense.isCancelled() ? "Откажано" : "Одбрането";

        String grade = result.getGrade() != null ? String.valueOf(result.getGrade()) : "—";
        String notes = result.getNotes() != null && !result.getNotes().isBlank()
                ? escape(result.getNotes()).replace("\n", "<br/>") : "—";
        String regNumber = thesis.getArchiveRegistrationNumber() != null
                ? escape(thesis.getArchiveRegistrationNumber()) : "—";
        String recordedBy = result.getRecordedBy() != null
                ? escape(result.getRecordedBy().getFullName()) : "—";

        String committeeRows = buildCommitteeRows(committee);
        String signatures = buildSignatures(committee, studentName);

        return """
            <html>
            <head>
              <meta charset="UTF-8"/>
              <style>
                @page { size: A4; margin: 2cm; }
                body { font-family: 'Noto Sans', sans-serif; font-size: 11pt; color: #111; line-height: 1.45; }
                h1 { font-size: 18pt; text-align: center; margin: 0 0 4pt; font-weight: 700; }
                .subtitle { text-align: center; color: #555; font-size: 10pt; margin-bottom: 22pt; }
                .section { margin-top: 16pt; }
                .section h2 {
                  font-size: 11pt; text-transform: uppercase; letter-spacing: 0.5pt; color: #1d4ed8;
                  border-bottom: 1pt solid #1d4ed8; padding-bottom: 3pt; margin: 0 0 8pt; font-weight: 700;
                }
                table { width: 100%%; border-collapse: collapse; }
                td { padding: 4pt 6pt; vertical-align: top; }
                td.label { width: 35%%; color: #555; font-weight: 700; }
                table.committee td, table.committee th {
                  border: 1pt solid #ddd; padding: 5pt 6pt; text-align: left; font-size: 10.5pt;
                }
                table.committee th { background: #f3f4f6; font-weight: 700; }
                table.committee td.num { width: 8%%; text-align: center; }
                .topic-box {
                  border: 1pt solid #ddd; padding: 8pt; background: #fafafa; margin-top: 4pt;
                }
                .topic-title { font-weight: 700; font-size: 12.5pt; }
                .grade-box { font-size: 13pt; font-weight: 700; }
                .signatures { margin-top: 34pt; display: table; width: 100%%; table-layout: fixed; }
                .sig-row { display: table-row; }
                .sig-col { display: table-cell; width: 50%%; padding: 0 10pt 26pt; text-align: center; }
                .sig-line { border-top: 1pt solid #333; margin-top: 30pt; padding-top: 4pt; font-size: 9pt; color: #444; }
                .footer { margin-top: 26pt; text-align: center; color: #888; font-size: 8pt; }
              </style>
            </head>
            <body>
              <h1>ЗАПИСНИК ЗА ОДБРАНА НА ДИПЛОМСКА РАБОТА</h1>
              <p class="subtitle">Официјален записник за одбрана на дипломска работа</p>

              <div class="section">
                <h2>Студент</h2>
                <table>
                  <tr><td class="label">Име и презиме</td><td>%s</td></tr>
                  <tr><td class="label">Индекс</td><td>%s</td></tr>
                  <tr><td class="label">Е-пошта</td><td>%s</td></tr>
                </table>
              </div>

              <div class="section">
                <h2>Дипломска работа</h2>
                <div class="topic-box">
                  <div class="topic-title">%s</div>
                </div>
                <table>
                  <tr><td class="label">Ментор</td><td>%s</td></tr>
                </table>
              </div>

              <div class="section">
                <h2>Комисија за одбрана</h2>
                <table class="committee">
                  <tr><th class="num">Бр.</th><th>Име и презиме</th><th>Улога</th></tr>
                  %s
                </table>
              </div>

              <div class="section">
                <h2>Одбрана</h2>
                <table>
                  <tr><td class="label">Датум</td><td>%s</td></tr>
                  <tr><td class="label">Време</td><td>%s</td></tr>
                  <tr><td class="label">Просторија</td><td>%s</td></tr>
                  <tr><td class="label">Статус</td><td>%s</td></tr>
                </table>
              </div>

              <div class="section">
                <h2>Резултат</h2>
                <table>
                  <tr><td class="label">Оценка</td><td class="grade-box">%s</td></tr>
                  <tr><td class="label">Забелешки</td><td>%s</td></tr>
                  <tr><td class="label">Архивски број</td><td>%s</td></tr>
                  <tr><td class="label">Записничар</td><td>%s</td></tr>
                </table>
              </div>

              <div class="section">
                <h2>Потписи</h2>
                <div class="signatures">%s</div>
              </div>

              <div class="footer">
                Овој документ е генериран автоматски од Системот за управување со дипломски работи.
              </div>
            </body>
            </html>
            """.formatted(
                studentName, studentIndex, studentEmail,
                title, mentorName,
                committeeRows,
                date, time, room, statusLabel,
                grade, notes, regNumber, recordedBy,
                signatures
            );
    }

    /** One table row per committee member; falls back to a single "no data" row. */
    private String buildCommitteeRows(List<CommitteeMember> committee) {
        if (committee == null || committee.isEmpty()) {
            return "<tr><td class=\"num\">—</td><td>нема податоци</td><td>—</td></tr>";
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (CommitteeMember m : committee) {
            sb.append("<tr><td class=\"num\">").append(i++).append("</td>")
              .append("<td>").append(escape(m.getProfessor().getFullName())).append("</td>")
              .append("<td>").append(roleLabel(m.getMemberRole())).append("</td></tr>");
        }
        return sb.toString();
    }

    /** One signature cell per committee member (name + role) plus the student. */
    private String buildSignatures(List<CommitteeMember> committee, String studentName) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"sig-row\">");
        int perRow = 0;
        if (committee != null) {
            for (CommitteeMember m : committee) {
                if (perRow == 2) { sb.append("</div><div class=\"sig-row\">"); perRow = 0; }
                sb.append("<div class=\"sig-col\"><div class=\"sig-line\">")
                  .append(roleLabel(m.getMemberRole())).append(" — ")
                  .append(escape(m.getProfessor().getFullName()))
                  .append("</div></div>");
                perRow++;
            }
        }
        if (perRow == 2) { sb.append("</div><div class=\"sig-row\">"); }
        sb.append("<div class=\"sig-col\"><div class=\"sig-line\">Студент — ")
          .append(studentName).append("</div></div>");
        sb.append("</div>");
        return sb.toString();
    }

    private String roleLabel(MemberRole role) {
        if (role == MemberRole.MENTOR_MEMBER) return "Ментор";
        return "Член на комисија";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
