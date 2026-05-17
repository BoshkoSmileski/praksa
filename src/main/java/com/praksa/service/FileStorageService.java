package com.praksa.service;

import com.praksa.exception.BadRequestException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class FileStorageService {

    @Value("${file.upload-dir}")
    private String uploadDir;

    // Called once when the application starts.
    // Creates the base upload folder if it doesn't exist yet.
    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(Paths.get(uploadDir));
        } catch (IOException e) {
            throw new RuntimeException("Could not create upload directory: " + uploadDir, e);
        }
    }

    /**
     * Validates, saves, and returns the relative path of the stored file.
     *
     * @param file     the uploaded MultipartFile
     * @param thesisId used to create a per-thesis subdirectory
     * @param version  the version number, used as a filename prefix
     * @return relative path like "uploads/theses/{thesisId}/v2_uuid-filename.pdf"
     */
    public String storePdf(MultipartFile file, UUID thesisId, int version) {
        validatePdf(file);

        // Create the per-thesis directory: uploads/theses/{thesisId}/
        Path thesisDir = Paths.get(uploadDir, "theses", thesisId.toString());
        try {
            Files.createDirectories(thesisDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create thesis upload directory", e);
        }

        // Build a unique filename: v3_a1b2c3d4-original-name.pdf
        // UUID prefix prevents collisions if a student uploads two files with the same name
        String originalName = file.getOriginalFilename();
        String safeName = sanitizeFilename(originalName);
        String filename = "v" + version + "_" + UUID.randomUUID() + "-" + safeName;

        Path destination = thesisDir.resolve(filename);

        try {
            // REPLACE_EXISTING is a safety net; the UUID prefix makes collisions
            // practically impossible, but we keep it for defensive programming
            Files.copy(file.getInputStream(), destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file: " + filename, e);
        }

        // Return the relative path — this is what gets stored in the database
        return thesisDir.resolve(filename).toString();
    }

    /**
     * Deletes a file at the given path.
     * Used if a version record fails to save after the file was already written.
     */
    public void deleteFile(String filePath) {
        try {
            Files.deleteIfExists(Paths.get(filePath));
        } catch (IOException e) {
            // Log but don't throw — a leftover file is not a critical error
            System.err.println("Warning: could not delete file " + filePath);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void validatePdf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("File cannot be empty");
        }

        // Check the MIME type declared by the client
        String contentType = file.getContentType();
        if (contentType == null || !contentType.equals("application/pdf")) {
            throw new BadRequestException("Only PDF files are allowed");
        }

        // Also check the file extension — a second layer of validation
        // because a malicious client could set the content type to anything
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(".pdf")) {
            throw new BadRequestException("File must have a .pdf extension");
        }
    }

    private String sanitizeFilename(String name) {
        if (name == null) return "file.pdf";
        // Remove path separators and keep only safe characters
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
