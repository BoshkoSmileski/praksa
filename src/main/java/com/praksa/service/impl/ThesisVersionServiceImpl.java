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
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.CommitteeMemberRepository;
import com.praksa.repository.ThesisCommentRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.repository.ThesisVersionRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.service.FileStorageService;
import com.praksa.service.NotificationService;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ThesisVersionServiceImpl implements ThesisVersionService {

    private final ThesisRepository thesisRepository;
    private final ThesisVersionRepository versionRepository;
    private final ThesisCommentRepository commentRepository;
    private final ThesisStatusHistoryRepository statusHistoryRepository;
    private final CommitteeMemberRepository committeeMemberRepository;
    private final FileStorageService fileStorageService;
    private final SecurityUtils securityUtils;
    private final NotificationService notificationService;

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
                    "Верзии може да се прикачуваат само додека дипломската работа е во статус IN_PROGRESS или FINAL_SUBMITTED.");
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

        // Reset the 45-day mentor-review-deadline clock. Only stamped here — on a
        // genuinely successful upload — never on a mere read of a version.
        thesis.setLastVersionSubmittedAt(OffsetDateTime.now());
        thesisRepository.save(thesis);

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
            throw new BadRequestException("Финална верзија може да се означи само додека дипломската работа е во статус IN_PROGRESS.");
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

        // ─── Notification (only after the final version is persisted) ────────
        // Recipient is the assigned mentor only. markAsFinal runs while the thesis is
        // IN_PROGRESS — the defense committee is not formed until much later (after
        // MENTOR_APPROVED), so there are no committee members to notify at this point.
        // The mentor is guaranteed to be assigned by this stage; guarded for safety.
        if (thesis.getMentor() != null) {
            notificationService.notify(thesis.getMentor(), thesis, NotificationType.FINAL_VERSION_SUBMITTED);
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
        User user = securityUtils.getCurrentUser();
        checkThesisReadAccess(thesis, user);

        // Filter per-user visibility: committee members only see the final version.
        // This is enforced here — not just in the UI — so a hand-crafted request cannot
        // enumerate draft versions.
        return versionRepository.findByThesisOrderByVersionNumberAsc(thesis)
                .stream()
                .filter(v -> canSeeVersion(thesis, v, user))
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
        User user = securityUtils.getCurrentUser();
        checkThesisReadAccess(thesis, user);

        ThesisVersion version = findVersion(versionId, thesis);
        if (!canSeeVersion(thesis, version, user)) {
            throw new UnauthorizedException("Немате пристап до оваа верзија на дипломската работа.");
        }

        try {
            Path filePath = Paths.get(version.getPdfUrl()).toAbsolutePath().normalize();
            Resource resource = new UrlResource(filePath.toUri());

            if (!resource.exists() || !resource.isReadable()) {
                throw new ResourceNotFoundException("Датотеката не е пронајдена за верзијата: " + versionId);
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
            throw new UnauthorizedException("Само студентот или назначениот ментор на дипломската работа можат да додаваат коментари.");
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
        User user = securityUtils.getCurrentUser();
        checkThesisReadAccess(thesis, user);

        ThesisVersion version = findVersion(versionId, thesis);
        // Comments follow the visibility of the underlying version — a committee
        // member cannot read comments on a draft they aren't allowed to see.
        if (!canSeeVersion(thesis, version, user)) {
            throw new UnauthorizedException("Немате пристап до коментарите на оваа верзија.");
        }

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
                .orElseThrow(() -> new ResourceNotFoundException("Дипломската работа не е пронајдена: " + id));
    }

    private ThesisVersion findVersion(UUID versionId, Thesis thesis) {
        ThesisVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("Верзијата не е пронајдена: " + versionId));
        // Ensure the version actually belongs to this thesis
        if (!version.getThesis().getId().equals(thesis.getId())) {
            throw new BadRequestException("Верзијата не припаѓа на оваа дипломска работа.");
        }
        return version;
    }

    private void requireRole(User user, Role required) {
        if (user.getRole() != required) {
            throw new UnauthorizedException("Оваа акција бара улога: " + required);
        }
    }

    private void requireOwner(Thesis thesis, User user) {
        if (!thesis.getStudent().getId().equals(user.getId())) {
            throw new UnauthorizedException("Не сте сопственик на оваа дипломска работа.");
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
     * Coarse gate: may the user see this thesis's version area at all?
     *
     *   STUDENT (owner) / MENTOR (assigned) / STUDENT_SERVICE / ARCHIVE — allowed unconditionally.
     *   Any user holding a CommitteeMember seat on this thesis — allowed (final version only,
     *       enforced downstream by {@link #canSeeVersion}).
     *   COMMITTEE role users without a committee seat on this thesis — denied. The COMMITTEE
     *       role is only meaningful when the user is actually a member of the given committee.
     */
    private void checkThesisReadAccess(Thesis thesis, User user) {
        if (user.getRole() == Role.STUDENT
                && thesis.getStudent().getId().equals(user.getId())) return;
        if (user.getRole() == Role.MENTOR
                && thesis.getMentor() != null
                && thesis.getMentor().getId().equals(user.getId())) return;
        if (user.getRole() == Role.STUDENT_SERVICE) return;
        if (user.getRole() == Role.ARCHIVE) return;
        if (isCommitteeMember(thesis, user)) return;
        throw new UnauthorizedException("Немате пристап до оваа дипломска работа.");
    }

    /**
     * Fine-grained gate: may the user see this specific version?
     *
     *   Student owner / assigned mentor / STUDENT_SERVICE / ARCHIVE — every version.
     *   Committee members (of any Role) — the FINAL version only. If no version is marked
     *       final, or the requested version is a draft, they see nothing.
     *   Anyone else — no.
     */
    private boolean canSeeVersion(Thesis thesis, ThesisVersion version, User user) {
        if (user.getRole() == Role.STUDENT
                && thesis.getStudent().getId().equals(user.getId())) return true;
        if (user.getRole() == Role.MENTOR
                && thesis.getMentor() != null
                && thesis.getMentor().getId().equals(user.getId())) return true;
        if (user.getRole() == Role.STUDENT_SERVICE) return true;
        if (user.getRole() == Role.ARCHIVE) return true;
        if (isCommitteeMember(thesis, user)) return version.isFinal();
        return false;
    }

    private boolean isCommitteeMember(Thesis thesis, User user) {
        return committeeMemberRepository.existsByThesisAndProfessor(thesis, user);
    }
}
