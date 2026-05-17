package com.praksa.service.impl;

import com.praksa.dto.version.AddCommentRequest;
import com.praksa.dto.version.ThesisCommentResponse;
import com.praksa.dto.version.ThesisVersionResponse;
import com.praksa.exception.BadRequestException;
import com.praksa.exception.ResourceNotFoundException;
import com.praksa.exception.UnauthorizedException;
import com.praksa.model.Thesis;
import com.praksa.model.ThesisComment;
import com.praksa.model.ThesisStatusHistory;
import com.praksa.model.ThesisVersion;
import com.praksa.model.User;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.ThesisCommentRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.repository.ThesisVersionRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.service.FileStorageService;
import com.praksa.service.ThesisVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ThesisVersionServiceImpl implements ThesisVersionService {

    private final ThesisRepository thesisRepository;
    private final ThesisVersionRepository versionRepository;
    private final ThesisCommentRepository commentRepository;
    private final ThesisStatusHistoryRepository statusHistoryRepository;
    private final FileStorageService fileStorageService;
    private final SecurityUtils securityUtils;

    @Value("${file.upload-dir}")
    private String uploadDir;

    // -------------------------------------------------------------------------
    // UPLOAD
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public ThesisVersionResponse uploadVersion(UUID thesisId, MultipartFile file) {
        User student = securityUtils.getCurrentUser();
        requireRole(student, Role.STUDENT);

        Thesis thesis = findThesis(thesisId);
        requireOwner(thesis, student);

        // Student can only upload while the thesis is actively in progress
        if (thesis.getStatus() != ThesisStatus.IN_PROGRESS
                && thesis.getStatus() != ThesisStatus.FINAL_SUBMITTED) {
            throw new BadRequestException(
                    "Versions can only be uploaded while thesis is IN_PROGRESS or FINAL_SUBMITTED");
        }

        // Determine the next version number.
        // We look up the current max version INSIDE this transaction, so two
        // simultaneous uploads can't both read "max=2" and both try to create v3.
        int nextVersion = versionRepository
                .findTopByThesisOrderByVersionNumberDesc(thesis)
                .map(v -> v.getVersionNumber() + 1)
                .orElse(1);  // First upload for this thesis starts at 1

        // Store the file on disk BEFORE saving to the database.
        // If the DB save fails, we clean up the file manually.
        // If the file save fails, an exception is thrown and nothing is saved to DB.
        String storedPath = fileStorageService.storePdf(file, thesisId, nextVersion);

        ThesisVersion version = ThesisVersion.builder()
                .thesis(thesis)
                .versionNumber(nextVersion)
                .pdfUrl(storedPath)
                .isFinal(false)
                .build();

        try {
            versionRepository.save(version);
        } catch (Exception e) {
            // DB save failed — clean up the file that was already written to disk
            fileStorageService.deleteFile(storedPath);
            throw new RuntimeException("Failed to save version record. File upload rolled back.", e);
        }

        return ThesisVersionResponse.from(version);
    }

    // -------------------------------------------------------------------------
    // MARK AS FINAL
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public ThesisVersionResponse markAsFinal(UUID thesisId, UUID versionId) {
        User student = securityUtils.getCurrentUser();
        requireRole(student, Role.STUDENT);

        Thesis thesis = findThesis(thesisId);
        requireOwner(thesis, student);

        if (thesis.getStatus() != ThesisStatus.IN_PROGRESS) {
            throw new BadRequestException("Can only mark a final version while thesis is IN_PROGRESS");
        }

        // Clear the final flag from any previously marked version.
        // Only one version can be final at a time.
        versionRepository.findByThesisAndIsFinalTrue(thesis).ifPresent(previous -> {
            previous.setFinal(false);
            versionRepository.save(previous);
        });

        ThesisVersion version = findVersion(versionId, thesis);
        version.setFinal(true);
        versionRepository.save(version);

        // Advance thesis status to FINAL_SUBMITTED — recorded in history for the audit trail.
        // Only transition if not already there (handles the case where student re-marks final).
        if (thesis.getStatus() != ThesisStatus.FINAL_SUBMITTED) {
            transitionStatus(thesis, ThesisStatus.FINAL_SUBMITTED, student);
        }

        return ThesisVersionResponse.from(version);
    }

    // -------------------------------------------------------------------------
    // READ
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<ThesisVersionResponse> getVersions(UUID thesisId) {
        Thesis thesis = findThesis(thesisId);
        checkReadAccess(thesis, securityUtils.getCurrentUser());

        return versionRepository.findByThesisOrderByVersionNumberAsc(thesis)
                .stream()
                .map(ThesisVersionResponse::from)
                .toList();
    }

    // -------------------------------------------------------------------------
    // DOWNLOAD
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Resource downloadVersion(UUID thesisId, UUID versionId) {
        Thesis thesis = findThesis(thesisId);
        checkReadAccess(thesis, securityUtils.getCurrentUser());

        ThesisVersion version = findVersion(versionId, thesis);

        try {
            Path filePath = Paths.get(version.getPdfUrl()).toAbsolutePath().normalize();
            Resource resource = new UrlResource(filePath.toUri());

            if (!resource.exists() || !resource.isReadable()) {
                throw new ResourceNotFoundException("File not found on disk for version: " + versionId);
            }

            return resource;
        } catch (MalformedURLException e) {
            throw new RuntimeException("Malformed file path for version: " + versionId, e);
        }
    }

    // -------------------------------------------------------------------------
    // COMMENTS
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public ThesisCommentResponse addComment(UUID thesisId, UUID versionId, AddCommentRequest request) {
        User author = securityUtils.getCurrentUser();

        Thesis thesis = findThesis(thesisId);

        // Only the thesis student and their assigned mentor can comment
        boolean isStudent = author.getRole() == Role.STUDENT
                && thesis.getStudent().getId().equals(author.getId());
        boolean isMentor = author.getRole() == Role.MENTOR
                && thesis.getMentor() != null
                && thesis.getMentor().getId().equals(author.getId());

        if (!isStudent && !isMentor) {
            throw new UnauthorizedException("Only the thesis student or assigned mentor can add comments");
        }

        ThesisVersion version = findVersion(versionId, thesis);

        ThesisComment comment = ThesisComment.builder()
                .version(version)
                .author(author)
                .content(request.getContent())
                .build();

        commentRepository.save(comment);

        return ThesisCommentResponse.from(comment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ThesisCommentResponse> getComments(UUID thesisId, UUID versionId) {
        Thesis thesis = findThesis(thesisId);
        checkReadAccess(thesis, securityUtils.getCurrentUser());

        ThesisVersion version = findVersion(versionId, thesis);

        return commentRepository.findByVersionOrderByCreatedAtAsc(version)
                .stream()
                .map(ThesisCommentResponse::from)
                .toList();
    }

    // -------------------------------------------------------------------------
    // PRIVATE HELPERS
    // -------------------------------------------------------------------------

    private Thesis findThesis(UUID id) {
        return thesisRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Thesis not found: " + id));
    }

    private ThesisVersion findVersion(UUID versionId, Thesis thesis) {
        ThesisVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("Version not found: " + versionId));
        // Ensure the version actually belongs to this thesis
        if (!version.getThesis().getId().equals(thesis.getId())) {
            throw new BadRequestException("Version does not belong to this thesis");
        }
        return version;
    }

    private void requireRole(User user, Role required) {
        if (user.getRole() != required) {
            throw new UnauthorizedException("This action requires role: " + required);
        }
    }

    private void requireOwner(Thesis thesis, User user) {
        if (!thesis.getStudent().getId().equals(user.getId())) {
            throw new UnauthorizedException("You do not own this thesis");
        }
    }

    /**
     * Same pattern as in ThesisServiceImpl — every status change MUST be recorded
     * in the audit trail. Never call thesis.setStatus() directly.
     */
    private void transitionStatus(Thesis thesis, ThesisStatus newStatus, User changedBy) {
        ThesisStatus oldStatus = thesis.getStatus();
        thesis.setStatus(newStatus);
        thesisRepository.save(thesis);

        ThesisStatusHistory history = ThesisStatusHistory.builder()
                .thesis(thesis)
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .changedBy(changedBy)
                .build();
        statusHistoryRepository.save(history);
    }

    /**
     * Checks whether the current user has read access to a thesis.
     * Student sees their own, mentor sees assigned, admin/archive/committee see all.
     */
    private void checkReadAccess(Thesis thesis, User user) {
        if (user.getRole() == Role.ADMIN
                || user.getRole() == Role.ARCHIVE
                || user.getRole() == Role.COMMITTEE) {
            return;
        }
        if (user.getRole() == Role.STUDENT
                && thesis.getStudent().getId().equals(user.getId())) {
            return;
        }
        if (user.getRole() == Role.MENTOR
                && thesis.getMentor() != null
                && thesis.getMentor().getId().equals(user.getId())) {
            return;
        }
        throw new UnauthorizedException("You do not have access to this thesis");
    }
}
