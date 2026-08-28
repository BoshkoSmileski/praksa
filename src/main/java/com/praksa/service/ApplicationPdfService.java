package com.praksa.service;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.praksa.model.Thesis;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Generates the official thesis application form PDF ("Пријава на дипломска работа").
 *
 * Approach: build a small HTML document with the thesis data filled in, hand it to
 * OpenHTMLtoPDF to render. The output is a single-page A4 PDF stored under
 * uploads/theses/{thesisId}/application/application.pdf.
 *
 * Why HTML→PDF instead of PDFBox/iText: we get CSS layout, easy iteration on look,
 * and the same template can produce HTML previews if needed later.
 *
 * Cyrillic support: the base-14 PDF fonts (Helvetica) do NOT contain Cyrillic glyphs, so
 * the document is typeset in Noto Sans (SIL Open Font License), bundled under
 * src/main/resources/fonts/ — the same bundled font already used by
 * {@link DefenseRecordPdfService} for the Macedonian defense record.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationPdfService {

    @Value("${file.upload-dir}")
    private String uploadDir;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /**
     * Render the application form for the given thesis and return the relative path
     * where it was saved. The file is overwritten on repeated calls (idempotent).
     */
    public String generate(Thesis thesis) {
        Path destDir = Paths.get(uploadDir, "theses", thesis.getId().toString(), "application");
        try {
            Files.createDirectories(destDir);
        } catch (Exception e) {
            throw new RuntimeException("Could not create application PDF directory", e);
        }

        Path destFile = destDir.resolve("application.pdf");

        String html = buildHtml(thesis);

        try (OutputStream os = Files.newOutputStream(destFile)) {
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
        } catch (Exception e) {
            throw new RuntimeException("Failed to render application PDF", e);
        }

        log.info("Generated application PDF for thesis {} at {}", thesis.getId(), destFile);
        return destFile.toString();
    }

    private InputStream loadFont(String name) {
        InputStream in = getClass().getResourceAsStream("/fonts/" + name);
        if (in == null) {
            // Fail loud rather than silently produce boxes for Cyrillic text.
            throw new IllegalStateException("Missing bundled font: /fonts/" + name);
        }
        return in;
    }

    /**
     * Build the HTML body. Inline CSS keeps it self-contained — the PDF renderer
     * doesn't follow external stylesheets.
     */
    private String buildHtml(Thesis thesis) {
        String today = OffsetDateTime.now().format(DATE_FMT);
        String studentIndex = thesis.getStudent().getIndexNumber() != null
                ? thesis.getStudent().getIndexNumber() : "—";
        String mentorName = thesis.getMentor() != null ? thesis.getMentor().getFullName() : "—";

        // Escape user-controlled content to avoid breaking the HTML
        String title = escape(thesis.getTitle());
        String description = thesis.getStudentComment() != null
                ? escape(thesis.getStudentComment()) : "—";

        return """
            <html>
            <head>
              <meta charset="UTF-8"/>
              <style>
                @page { size: A4; margin: 2cm; }
                body { font-family: 'Noto Sans', sans-serif; font-size: 11pt; color: #111; line-height: 1.4; }
                h1 { font-size: 20pt; text-align: center; margin: 0 0 6pt; letter-spacing: 1pt; font-weight: 700; }
                .subtitle { text-align: center; color: #555; font-size: 10pt; margin-bottom: 24pt; }
                .section { margin-top: 18pt; }
                .section h2 {
                  font-size: 11pt; text-transform: uppercase; letter-spacing: 1pt; color: #2563eb;
                  border-bottom: 1pt solid #2563eb; padding-bottom: 4pt; margin: 0 0 8pt; font-weight: 700;
                }
                table { width: 100%%; border-collapse: collapse; }
                td { padding: 4pt 6pt; vertical-align: top; }
                td.label { width: 35%%; color: #555; font-weight: 700; }
                .topic-box {
                  border: 1pt solid #ddd; padding: 8pt; background: #fafafa;
                  margin-top: 6pt; min-height: 60pt;
                }
                .topic-title { font-weight: 700; font-size: 13pt; margin-bottom: 6pt; }
                .signatures {
                  margin-top: 40pt; display: table; width: 100%%; table-layout: fixed;
                }
                .sig-col { display: table-cell; width: 33%%; text-align: center; padding: 0 8pt; }
                .sig-line { border-top: 1pt solid #333; margin-top: 36pt; padding-top: 4pt; font-size: 9pt; color: #555; }
                .footer { margin-top: 24pt; text-align: center; color: #888; font-size: 8pt; }
              </style>
            </head>
            <body>
              <h1>ПРИЈАВА НА ДИПЛОМСКА РАБОТА</h1>
              <p class="subtitle">Официјална пријава за дипломска работа</p>

              <div class="section">
                <h2>Студент</h2>
                <table>
                  <tr><td class="label">Име и презиме</td><td>%s</td></tr>
                  <tr><td class="label">Индекс</td><td>%s</td></tr>
                  <tr><td class="label">Е-пошта</td><td>%s</td></tr>
                </table>
              </div>

              <div class="section">
                <h2>Ментор</h2>
                <table>
                  <tr><td class="label">Име и презиме</td><td>%s</td></tr>
                </table>
              </div>

              <div class="section">
                <h2>Предложена тема</h2>
                <div class="topic-box">
                  <div class="topic-title">%s</div>
                  <div>%s</div>
                </div>
              </div>

              <div class="section">
                <h2>Поднесување</h2>
                <table>
                  <tr><td class="label">Датум на поднесување</td><td>%s</td></tr>
                  <tr><td class="label">Број на пријава</td><td>%s</td></tr>
                </table>
              </div>

              <div class="signatures">
                <div class="sig-col"><div class="sig-line">Студент</div></div>
                <div class="sig-col"><div class="sig-line">Ментор</div></div>
                <div class="sig-col"><div class="sig-line">Студентска служба</div></div>
              </div>

              <div class="footer">
                Овој документ е генериран автоматски од Системот за управување со дипломски работи.
              </div>
            </body>
            </html>
            """.formatted(
                escape(thesis.getStudent().getFullName()),
                escape(studentIndex),
                escape(thesis.getStudent().getEmail()),
                escape(mentorName),
                title,
                description.replace("\n", "<br/>"),
                today,
                thesis.getId().toString()
            );
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
