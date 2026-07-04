package com.praksa.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.praksa.model.Thesis;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Generates the official thesis application form PDF.
 *
 * Approach: build a small HTML document with the thesis data filled in, hand it to
 * OpenHTMLtoPDF to render. The output is a single-page A4 PDF stored under
 * uploads/theses/{thesisId}/application/application.pdf.
 *
 * Why HTML→PDF instead of PDFBox/iText: we get CSS layout, easy iteration on look,
 * and the same template can produce HTML previews if needed later.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationPdfService {

    @Value("${file.upload-dir}")
    private String uploadDir;

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
            builder.withHtmlContent(html, null);
            builder.toStream(os);
            builder.run();
        } catch (Exception e) {
            throw new RuntimeException("Failed to render application PDF", e);
        }

        log.info("Generated application PDF for thesis {} at {}", thesis.getId(), destFile);
        return destFile.toString();
    }

    /**
     * Build the HTML body. Inline CSS keeps it self-contained — the PDF renderer
     * doesn't follow external stylesheets.
     */
    private String buildHtml(Thesis thesis) {
        String today = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy"));
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
                body { font-family: 'Helvetica', sans-serif; font-size: 11pt; color: #111; line-height: 1.4; }
                h1 { font-size: 20pt; text-align: center; margin: 0 0 6pt; letter-spacing: 1pt; }
                .subtitle { text-align: center; color: #555; font-size: 10pt; margin-bottom: 24pt; }
                .section { margin-top: 18pt; }
                .section h2 {
                  font-size: 11pt; text-transform: uppercase; letter-spacing: 1pt; color: #2563eb;
                  border-bottom: 1pt solid #2563eb; padding-bottom: 4pt; margin: 0 0 8pt;
                }
                table { width: 100%%; border-collapse: collapse; }
                td { padding: 4pt 6pt; vertical-align: top; }
                td.label { width: 35%%; color: #555; font-weight: bold; }
                .topic-box {
                  border: 1pt solid #ddd; padding: 8pt; background: #fafafa;
                  margin-top: 6pt; min-height: 60pt;
                }
                .topic-title { font-weight: bold; font-size: 13pt; margin-bottom: 6pt; }
                .signatures {
                  margin-top: 40pt; display: table; width: 100%%; table-layout: fixed;
                }
                .sig-col { display: table-cell; width: 33%%; text-align: center; padding: 0 8pt; }
                .sig-line { border-top: 1pt solid #333; margin-top: 36pt; padding-top: 4pt; font-size: 9pt; color: #555; }
                .footer { margin-top: 24pt; text-align: center; color: #888; font-size: 8pt; }
              </style>
            </head>
            <body>
              <h1>THESIS APPLICATION</h1>
              <p class="subtitle">Official application for diploma thesis</p>

              <div class="section">
                <h2>Student</h2>
                <table>
                  <tr><td class="label">Full name</td><td>%s</td></tr>
                  <tr><td class="label">Index number</td><td>%s</td></tr>
                  <tr><td class="label">Email</td><td>%s</td></tr>
                </table>
              </div>

              <div class="section">
                <h2>Mentor</h2>
                <table>
                  <tr><td class="label">Full name</td><td>%s</td></tr>
                </table>
              </div>

              <div class="section">
                <h2>Proposed thesis</h2>
                <div class="topic-box">
                  <div class="topic-title">%s</div>
                  <div>%s</div>
                </div>
              </div>

              <div class="section">
                <h2>Submission</h2>
                <table>
                  <tr><td class="label">Date submitted</td><td>%s</td></tr>
                  <tr><td class="label">Application ID</td><td>%s</td></tr>
                </table>
              </div>

              <div class="signatures">
                <div class="sig-col"><div class="sig-line">Student</div></div>
                <div class="sig-col"><div class="sig-line">Mentor</div></div>
                <div class="sig-col"><div class="sig-line">Student Service</div></div>
              </div>

              <div class="footer">
                This document was generated automatically by the Diploma Thesis Management System.
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
