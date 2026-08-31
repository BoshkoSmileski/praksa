# CLAUDE.md — Diploma Thesis Management System Handoff

> This is a HANDOFF DOCUMENT for a future Claude Code session.
> Read this once, then start work from the "CURRENT NEXT STEP" section at the bottom.
> Verified against the source code on 2026-08-16. Trust the code if anything here disagrees.

---

## 1. PROJECT OVERVIEW

**What it is.** A web application that runs the full workflow of a university diploma thesis — from a student's initial eligibility request through archiving of a defended thesis.

**Two separate repositories on disk:**
- `C:\Users\bosko\Desktop\praksa` — Spring Boot backend (Java 21)
- `C:\Users\bosko\Desktop\praksa-frontend` — React + Vite frontend (TypeScript)

**Backend architecture** (`com.praksa.*`):
- Layered: `controller → service → repository → model`
- DTOs are used at controller boundaries; entities never cross the transaction boundary
- Every status change goes through a `transitionStatus()` helper that also writes a `ThesisStatusHistory` row
- Async work (email) is dispatched on a named `emailTaskExecutor` thread pool
- Scheduled background jobs live in `ScheduledTasksService`

**Frontend architecture** (`praksa-frontend/src/`):
- Vite + React 19 + TypeScript
- Tailwind CSS 3 for styling; `lucide-react` icons; `sonner` toasts
- Zustand `authStore` (with `persist` middleware → `localStorage`)
- Axios instance with request interceptor (adds `Bearer` header) and response interceptor (401/403 → logout + redirect)
- React Router v7; path alias `@/` → `src/`

**Database.** PostgreSQL 14+ (local instance on `localhost:5432/diploma_system`, user `postgres`, password `123`). Hibernate `ddl-auto=update` — schema auto-migrates from JPA entities.

**Authentication.** Local JWT (HMAC-SHA symmetric). As of BUG-15 (2026-08-17) the signing key is NOT committed — `jwt.secret` comes from env `JWT_SECRET` (or the git-ignored local file) with no inline default and fail-fast startup. External-auth scaffold exists behind a flag but is disabled; when enabled it uses its own separate `auth.external-jwt-secret`, never the local key.

**Key runtime versions:**
- Spring Boot 3.5.14, Java 21
- jjwt 0.12.6, SpringDoc 2.8.6, OpenHTMLtoPDF 1.0.10
- React 19, Vite 8, TypeScript ~6

---

## 2. CURRENT IMPLEMENTATION

### Backend entities (`com.praksa.model`)
| Entity | Purpose |
|---|---|
| `User` | Person in the system. UUID pk. Fields: `email` (unique), `passwordHash`, `role`, `indexNumber` (unique nullable — students only), `fullName`, `createdAt`. |
| `Thesis` | Central workflow entity. See §6 for full field list. `@PrePersist` sets `createdAt/updatedAt` and defaults status to `PENDING_ELIGIBILITY_CHECK`. |
| `ThesisVersion` | PDF upload row. Unique constraint on `(thesis_id, version_number)`. `isFinal` flag. |
| `ThesisComment` | Comment on a specific version. Author is a `User`. |
| `CommitteeMember` | Row per committee seat. Unique constraint on `(thesis_id, professor_id)`. `memberRole` = MENTOR_MEMBER or FORMAL_MEMBER. `approvedBy`, `approvedAt`, `notes`. |
| `Defense` | A single ACTUAL scheduled defense event (only created on an APPROVED `DefenseRequest`). `room`, `scheduledAt`, `isCancelled`, `cancelledBy/At`, `reminderSentAt`. |
| `DefenseRequest` | (added 2026-08-27) A student-proposed room/date/time awaiting a Student Service decision. `thesis`, `requestedBy`, `room`, `scheduledAt`, `status` (PENDING/APPROVED/REJECTED), `reason`, `createdAt`, `decidedAt`, `decidedBy`. Never overwritten — a rejected request stays for audit and the student creates a brand-new row for the next proposal. |
| `DefenseResult` | One-to-one with Defense. `grade` (Integer 5-10), `notes`, `recordedBy`. |
| `DeadlineExtensionRequest` | (added 2026-08-28) A student-requested extension of `Thesis.defenseDeadline`, up to 15 additional days, awaiting a Student Service decision. `thesis`, `requestedBy`, `reason`, `requestedDays` (1-15), `status` (PENDING/APPROVED/REJECTED), `decisionReason`, `previousDeadline`, `newDeadline`, `createdAt`, `decidedAt`, `decidedBy`. Approval extends `Thesis.defenseDeadline` by exactly `requestedDays`; rejection leaves it unchanged. Never mutates thesis `status`. |
| `Notification` | Row per notification. `user`, `thesis` (nullable), `type` (String enum name), `isSent`, `sentAt`. |
| `ThesisStatusHistory` | Audit row for every status change. `oldStatus`, `newStatus`, `changedBy` (nullable — null = system action). |

### Enums (`com.praksa.model.enums`)
- **`Role`**: `STUDENT, MENTOR, STUDENT_SERVICE, COMMITTEE, ARCHIVE` (5)
- **`ThesisStatus`** (20): `PENDING_ELIGIBILITY_CHECK, ELIGIBILITY_REJECTED, TOPIC_SELECTION, PENDING_MENTOR_APPROVAL, MENTOR_REQUESTED_CHANGES, MENTOR_REJECTED_TOPIC, APPLICATION_SUBMITTED, PENDING_ARCHIVE_VALIDATION, APPLICATION_REJECTED_BY_ARCHIVE, PENDING_SERVICE_VALIDATION, APPLICATION_REJECTED_BY_SERVICE, IN_PROGRESS, FINAL_SUBMITTED, MENTOR_APPROVED, COMMITTEE_REVIEW, COMMITTEE_ACCEPTED, PENDING_DEFENSE_CHECK, PENDING_DEFENSE_SCHEDULING, DEFENSE_SCHEDULED, ARCHIVED`
  - `PENDING_DEFENSE_SCHEDULING` (added Item #6): sits between `PENDING_DEFENSE_CHECK` and `DEFENSE_SCHEDULED`. As of Item #8 its meaning is "STUDENT_SERVICE has verified defense eligibility; student may request; awaiting STUDENT_SERVICE scheduling". The `PENDING_DEFENSE_CHECK → PENDING_DEFENSE_SCHEDULING` transition is now owned by the explicit eligibility verification (`verifyDefenseEligibility`), not by the student request.
  - ⚠️ Naming quirk: `APPLICATION_SUBMITTED` is actually "mentor accepted, application NOT yet submitted". `submitApplication` moves it to `PENDING_ARCHIVE_VALIDATION`.
- **`MentorDecision`**: `ACCEPT, REJECT, REQUEST_CHANGES`
- **`MemberRole`**: `MENTOR_MEMBER, FORMAL_MEMBER`
- **`NotificationType`** (24): all workflow events; each carries a `subject` and `defaultBody` used for emails. `DEFENSE_REQUESTED` added in Item #6 (student→STUDENT_SERVICE); `DEFENSE_ELIGIBILITY_VERIFIED` added in Item #8 (STUDENT_SERVICE→student). As of Item #4 the 7 previously-dead types are all emitted; every type is now used.

### Controllers (8, all under `/api`)
| Path | Controller | Notable endpoints |
|---|---|---|
| `/api/auth` | `AuthController` | `POST /register`, `POST /login` — both public. |
| `/api/users` | `UserController` | `GET ?role=…` — **per-role authorized in `UserService`** (2026-08-17 P2 fix): `role=STUDENT` → STUDENT_SERVICE-only, full student fields (id/fullName/role/email/indexNumber/credits) for the credit list; `role=MENTOR` → mentor picker (STUDENT/MENTOR/STUDENT_SERVICE), identity only (id/fullName/role, **no** email/index/credits); any other role → 403. `GET /me`; `PATCH /{id}/credits` (STUDENT_SERVICE-only, target must be STUDENT). |
| `/api/theses` | `ThesisController` | `POST` create; `GET /my`; `GET /{id}`; `GET /by-registration-number/{n}`; `GET /{id}/application-pdf`; `GET /{id}/history`; `PATCH /{id}/eligibility`; `PATCH /{id}/mentor-request`; `PATCH /{id}/mentor-decision`; `PATCH /{id}/revise-proposal`; `PATCH /{id}/submit-application`; `PATCH /{id}/archive-validate`; `PATCH /{id}/service-validate`; `PATCH /{id}/approve-final`; `PATCH /{id}/defense-eligibility` (Item #8); `POST /{id}/deadline-extension-request`, `PATCH /{id}/deadline-extension-decision`, `GET /{id}/deadline-extension-requests` (added 2026-08-28) |
| `/api/theses/{id}/versions` | `ThesisVersionController` | multipart `POST`; `GET`; `PATCH /{versionId}/mark-final`; `GET /{versionId}/download`; `POST /{versionId}/comments`; `GET /{versionId}/comments` |
| `/api/theses/{id}/committee` | `CommitteeController` | `GET`; `POST /propose`; `POST /approve`; `PATCH /{memberId}/review`; `POST /accept-review` |
| `/api/theses/{id}/defenses` | `DefenseController` | `POST /request` (STUDENT — request defense); `POST` (STUDENT_SERVICE — schedule); `GET`; `GET /active`; `PATCH /cancel`; `GET /{did}/record-pdf` (Item #11 — defense record PDF, after grading) |
| `/api/theses/{id}/defenses/{did}/result` | `DefenseResultController` | `POST`; `GET` |
| `/api/notifications` | `NotificationController` | `GET /my`; `GET /unsent` |

### Services (implementation classes in `service/impl/*`)
- **`ThesisServiceImpl`** — every step of the core workflow. Uses `SecurityUtils.getCurrentUser()`, `requireRole/requireStatus/requireOwner` guards, and a local `transitionStatus()` helper.
- **`ThesisVersionServiceImpl`** — upload/mark-final/comment. File stored first; DB write second; cleans up file on DB failure.
- **`CommitteeServiceImpl`** — propose (auto-adds mentor + validates 2 formal members), approve (requires exactly 3 members), submit notes, accept review (fast-forwards through `COMMITTEE_ACCEPTED → PENDING_DEFENSE_CHECK`).
- **`DefenseServiceImpl`** — request (STUDENT only, from `PENDING_DEFENSE_CHECK` → `PENDING_DEFENSE_SCHEDULING`, no Defense row created), schedule (STUDENT_SERVICE only, from `PENDING_DEFENSE_SCHEDULING` or `DEFENSE_SCHEDULED`), cancel (student or mentor). Cancellation does NOT roll status back. **Mentors can no longer schedule** (Item #6).
- **`DefenseResultServiceImpl`** — grade recording, generates `DT-YYYY-NNNN` registration number, sets archive metadata, transitions to `ARCHIVED`.
- **`NotificationServiceImpl`** — saves row synchronously (in caller's tx), captures primitives, then dispatches async email. `notifyRole()` fans out to every user with a given role.
- **`AuthServiceImpl`** — local login/register, issues JWT. Blocks duplicate email / index number; students must supply `indexNumber`.
- **`EmailService`** — `@Async("emailTaskExecutor")` + own `@Transactional`. Marks notification as sent on success; logs and swallows `MailException` on failure.
- **`ApplicationPdfService`** — builds inline-CSS HTML with thesis details, renders via OpenHTMLtoPDF, saves to `uploads/theses/{id}/application/application.pdf`.
- **`DefenseRecordPdfService`** (Item #11) — builds inline-CSS HTML for the Macedonian defense record ("записник за одбрана"), renders via OpenHTMLtoPDF using the bundled Cyrillic Noto Sans font, returns PDF bytes in-memory (not persisted).
- **`FileStorageService`** — MIME + extension check for PDF, stores under `uploads/theses/{thesisId}/v{n}_UUID-name.pdf`.
- **`ScheduledTasksService`** — four `@Scheduled` jobs (see §Scheduled jobs below): three `fixedDelay = 30 min` (committee auto-advance, defense reminders, expired-application report) plus the P2.6 notification-retry job (`fixedDelay = 15 min`, configurable).

### Repositories (Spring Data JPA)
- `UserRepository` — `findByEmail`, `existsByEmail`, `existsByIndexNumber`, `findByRole`
- `ThesisRepository` — `findByStudent`, `findByMentor`, `findByStatus`, `countActiveMentorTheses` (JPQL), `countByArchiveRegistrationNumberStartingWith`, `findByArchiveRegistrationNumber`, `findStaleCommitteeReviews` (JPQL with cutoff)
- `ThesisVersionRepository` — `findByThesisOrderByVersionNumberAsc`, `findTopByThesisOrderByVersionNumberDesc`, `findByThesisAndIsFinalTrue`
- `ThesisCommentRepository` — `findByVersionOrderByCreatedAtAsc`
- `CommitteeMemberRepository` — `findByThesis`, `countByThesis`, `existsByThesisAndProfessor`, `findByThesisAndMemberRole`
- `DefenseRepository` — `findByThesis`, `findByThesisAndIsCancelledFalse`, `findUpcomingForReminder` (JPQL, filters by `reminderSentAt IS NULL` and window)
- `DefenseResultRepository` — `findByDefense`
- `NotificationRepository` — `findByUserOrderByCreatedAtDesc`, `findByIsSentFalse`, `findByIsSentFalseAndCreatedAtBeforeOrderByCreatedAtAsc(cutoff, Pageable)` (P2.6 — bounded, age-filtered, oldest-first retry query)
- `ThesisStatusHistoryRepository` — `findByThesisOrderByChangedAtAsc`

### DTOs (`com.praksa.dto.*`)
- Common wrapper: `ApiResponse<T>` with `success`, `message`, `data`.
- Category subfolders: `auth/`, `thesis/`, `version/`, `committee/`, `defense/`, `notification/`, `user/`.
- Response DTOs use `static from(entity)` factories that pull needed fields inside the transaction.
- `ThesisResponse` exposes flat `studentId/studentName`, `mentorId/mentorName`, comments, timestamps, archive metadata, and `hasApplicationPdf` boolean.

### JWT/security
- `JwtUtil` — HMAC symmetric signing (jjwt), 24h expiration (`jwt.expiration-ms=86400000`). Extracts `email`, `role`, `name`, `index_number` claims.
- `JwtAuthFilter` — reads `Authorization: Bearer …`, validates, loads user via `UserDetailsService`. When `auth.external-enabled=true` and email not found, auto-provisions from token claims.
- `PasswordEncoderConfig` — separate `@Configuration` for `BCryptPasswordEncoder` bean (breaks a cycle: `SecurityConfig → JwtAuthFilter → PasswordEncoder`).
- `SecurityConfig` — CSRF disabled, sessionless, permits `/api/auth/**`, `/swagger-ui/**`, `/v3/api-docs/**`, `/actuator/health`; requires auth for everything else.
- `UserDetailsServiceImpl` — loads user by email, returns Spring `User` with authority `ROLE_<enumName>`.
- `SecurityUtils.getCurrentUser()` — resolves current authentication to a `User` entity from the DB.

### Notifications & async email
- `NotificationServiceImpl.notify(recipient, thesis, type)` writes a `Notification` row synchronously (participates in caller's transaction), then calls `EmailService.sendAsync()` with primitive args.
- `NotificationServiceImpl.notifyRole(role, thesis, type)` loops all users of a role and calls `notify()` per user.
- `EmailService.sendAsync()` runs on `emailTaskExecutor` (2 core / 5 max / 50 queue). On success, marks the notification `isSent=true` + `sentAt`. On failure, logs and returns — row stays `isSent=false`.

### PDF generation
- `ApplicationPdfService.generate(thesis)` — builds inline-CSS A4 HTML, renders via `PdfRendererBuilder`, saves to `uploads/theses/{thesisId}/application/application.pdf`. Called from `ThesisServiceImpl.submitApplication`.
- Escapes user input (`escape()` helper) — `title`, `studentComment`, names.
- ⚠️ Path stored as *absolute* path (from `Paths.get(...).toAbsolutePath()`) — see field `Thesis.applicationPdfPath`.

### File uploads
- `FileStorageService.storePdf` accepts MultipartFile, validates content-type and extension, writes to `uploads/theses/{thesisId}/v{n}_{uuid}-{safeName}`.
- `spring.servlet.multipart.max-file-size=20MB` in `application.properties`.
- `deleteFile()` used to compensate DB save failures.

### Versioning
- Version numbers start at 1 and increment via `findTopByThesisOrderByVersionNumberDesc` inside the upload transaction (avoids race).
- Only one `isFinal=true` version per thesis; `markAsFinal` clears any previous final.
- Marking final transitions thesis to `FINAL_SUBMITTED` (only if not already there).

### Comments
- On a specific version. Only the thesis student and their assigned mentor can post. Anyone with read access to the thesis can list.

### Committee workflow
- Only mentor of the thesis can `proposeCommittee` (from `MENTOR_APPROVED`, and only once).
- Payload: 2 professor UUIDs. Mentor is auto-added as `MENTOR_MEMBER`; the 2 professors become `FORMAL_MEMBER`. Total must be exactly 3 before `approveCommittee` will succeed.
- `approveCommittee` (STUDENT_SERVICE) stamps `approvedBy/At` on all members, sets `thesis.committeeReviewStartedAt = now()`, transitions thesis to `COMMITTEE_REVIEW`.
- `submitReviewNotes` (per member): only the professor for that seat can submit; notes column, can be null.
  - Note: this endpoint does NOT check the caller's `Role`; it checks that the caller *is* the professor on that CommitteeMember row. This is intentional — MENTOR-role users hold the actual seats. The `COMMITTEE` role is used only for defense-grading and reading versions.
- `acceptCommitteeReview` (STUDENT_SERVICE) does `COMMITTEE_REVIEW → COMMITTEE_ACCEPTED → PENDING_DEFENSE_CHECK` in one call (two transitions in history).

### Defense workflow
- `scheduleDefense` (MENTOR only, must be the assigned mentor). Allowed from `PENDING_DEFENSE_CHECK` or `DEFENSE_SCHEDULED` (rescheduling). Refuses if an active (non-cancelled) defense already exists — student/mentor must cancel first.
- On first schedule: thesis → `DEFENSE_SCHEDULED`. Rescheduling: thesis stays at `DEFENSE_SCHEDULED`.
- `cancelDefense` (student-owner or assigned mentor): marks defense cancelled, stamps `cancelledBy/At`. Thesis status does NOT go backwards.
- `recordResult` (seated committee member of THIS thesis only — `requireCommitteeSeat` via `existsByThesisAndProfessor`; STUDENT_SERVICE no longer allowed, BUG-13 fixed 2026-08-17): allowed only from `DEFENSE_SCHEDULED`, defense must not be cancelled, must not already have a result. Creates `DefenseResult`, assigns registration number + `archiveDate` + `archivedBy`, transitions to `ARCHIVED`.

### Archive workflow
- Two-stage validation for applications: `archiveValidate` (role ARCHIVE) then `serviceValidate` (role STUDENT_SERVICE).
- Rejection at either stage requires a mandatory comment; sets `archiveComment` or `serviceComment`; transitions to `APPLICATION_REJECTED_BY_ARCHIVE` / `APPLICATION_REJECTED_BY_SERVICE`.
- Student resubmits via `submitApplication` (from either rejection status or the initial `APPLICATION_SUBMITTED`).
- Final archiving happens automatically inside `recordResult` when the defense grade is recorded — `DT-YYYY-NNNN` registration number generated by counting existing `DT-YYYY-` prefixes (uniqueness guaranteed by DB unique constraint).
- `Thesis.archiveNotes` field exists but no endpoint sets it (see Known bugs).

### Scheduled jobs (`ScheduledTasksService`)
- **`autoAdvanceStaleCommitteeReviews`** — `@Scheduled(fixedDelay = 30min, initialDelay = 60s)`. Finds theses in `COMMITTEE_REVIEW` whose `committeeReviewStartedAt` is `<=` (Item #9: inclusive) 5 *business* days ago (`ThesisRepository.findStaleCommitteeReviews`). Advances through `COMMITTEE_ACCEPTED → PENDING_DEFENSE_CHECK` (history entries have `changedBy = null`). Notifies student + all committee members with `COMMITTEE_REVIEW_AUTO_ADVANCED` (the mentor holds a committee seat, so is notified exactly once via the committee loop — no duplicate; matches the manual `acceptCommitteeReview` flow). Idempotent: after advancing, the thesis leaves `COMMITTEE_REVIEW` so the query no longer returns it; plus an in-loop defensive status re-check.
- **`sendDefenseReminders`** — `@Scheduled(fixedDelay = 30min, initialDelay = 90s)`. Finds active defenses with `reminderSentAt IS NULL` scheduled in the window `[now+23h, now+25h]`. Sends `DEFENSE_REMINDER` to the student and to every committee member. The mentor holds a `MENTOR_MEMBER` committee seat (a defense only exists after committee formation + review), so the committee loop notifies the mentor **exactly once** — no separate explicit mentor notify (fixed 2026-08-17; matches `scheduleDefense`/`cancelDefense`). Stamps `reminderSentAt = now()`.
- **`retryUnsentNotifications`** (P2.6) — `@Scheduled(fixedDelayString = "${notification.retry.interval-ms:900000}", initialDelayString = "${notification.retry.initial-delay-ms:150000}")` (default 15 min, first run 150s after startup). NOT `@Transactional` — it delegates to `NotificationServiceImpl.retryUnsentNotifications(batchSize, cutoff)` (which owns the read tx and the lazy loads). Computes `cutoff = now − notification.retry.min-age-ms` (default 2 min), then the service loads a **bounded** oldest-first page (`notification.retry.batch-size`, default 100) of rows still `isSent=false` created before the cutoff, rebuilds each email from the persisted row, and re-dispatches via the SAME `EmailService.sendAsync`. Creates NO new `Notification` row and marks NOTHING sent itself — `EmailService` stays the sole delivery authority (mail-enabled guard, send, `isSent=true` on success only). Per-notification try/catch isolates a bad row; a top-level catch keeps the scheduled thread alive. Duplicate protection: `findByIsSentFalse…` never returns sent rows; the age threshold avoids racing the normal flow's original async send; single-threaded `fixedDelay` means retry runs never overlap within one instance. Multi-instance dedup would need a shared lock (e.g. ShedLock) — not present, documented limitation.
- Business-days math is a simple `minusDays + skip Sat/Sun` loop.

### Swagger / OpenAPI
- SpringDoc 2.8.6.
- `OpenApiConfig` defines the API info block and global Bearer security scheme.
- `AuthController` uses `@SecurityRequirements` to opt out of the global security requirement.
- UI: `/swagger-ui.html`. Spec: `/v3/api-docs`.

### Actuator
- Exposed endpoints: `health`, `info` (see `application.properties`).
- `/actuator/health` public (in SecurityConfig permitAll list); `show-details=when-authorized`.
- `management.health.mail.enabled=false` — because SMTP credentials are placeholders.

### Database initialization/seeding
- `DataInitializer` implements `ApplicationRunner`. Guards with `if (userRepository.count() > 0) return;` so it only runs on empty DB.
- Seeds 7 test users (see §12).

### Tests
- `src/test/java/com/praksa/PraksaApplicationTests.java` — default Spring Boot context load test.
- `src/test/java/com/praksa/integration/AuthIntegrationTest.java` — only real integration test in the project.
- **No coverage** for the thesis workflow, versions, committee, defense, or scheduled jobs.

---

## 3. COMPLETE THESIS WORKFLOW (as coded, not as spec'd)

Legend: **[Actor]** endpoint · from-status → to-status · notifications sent.

```
[STUDENT]  POST   /api/theses
           (fresh thesis, no source status)  →  PENDING_ELIGIBILITY_CHECK
           Rules: caller must be STUDENT with no active thesis (any status != ARCHIVED).
           History: null → PENDING_ELIGIBILITY_CHECK
           No notification.

[STUDENT_SERVICE]  PATCH  /api/theses/{id}/eligibility  { approved: bool }
           PENDING_ELIGIBILITY_CHECK  →  TOPIC_SELECTION | ELIGIBILITY_REJECTED
           Notifies student (ELIGIBILITY_APPROVED / ELIGIBILITY_REJECTED).

[STUDENT]  PATCH  /api/theses/{id}/mentor-request  { mentorId, studentComment? }
           TOPIC_SELECTION or MENTOR_REJECTED_TOPIC  →  PENDING_MENTOR_APPROVAL
           Rules: mentor must be role=MENTOR; must have <10 active theses (countActiveMentorTheses).
           Assigns thesis.mentor.
           Notifies mentor (MENTOR_REQUEST_RECEIVED).

[MENTOR]   PATCH  /api/theses/{id}/mentor-decision  { decision, mentorComment? }
           PENDING_MENTOR_APPROVAL  →  branches on decision:
             ACCEPT           → APPLICATION_SUBMITTED
                              (⚠️ status name is misleading — see below)
                              Notifies student (MENTOR_ACCEPTED_TOPIC).
             REJECT           → MENTOR_REJECTED_TOPIC; mentor is cleared.
                              Notifies student (MENTOR_REJECTED_TOPIC).
             REQUEST_CHANGES  → MENTOR_REQUESTED_CHANGES; revisionCount++;
                              mentor stays assigned; mentorComment is REQUIRED.
                              Notifies student (MENTOR_REQUESTED_CHANGES).
           Rules: caller must be the assigned mentor.

[STUDENT]  PATCH  /api/theses/{id}/revise-proposal  { title, studentComment? }
           MENTOR_REQUESTED_CHANGES  →  PENDING_MENTOR_APPROVAL
           Updates title (and optionally comment). Mentor unchanged.
           Notifies mentor (STUDENT_RESUBMITTED_PROPOSAL).

[STUDENT]  PATCH  /api/theses/{id}/submit-application
           APPLICATION_SUBMITTED, APPLICATION_REJECTED_BY_ARCHIVE, or
           APPLICATION_REJECTED_BY_SERVICE  →  PENDING_ARCHIVE_VALIDATION
           Side effect: ApplicationPdfService.generate() → writes application.pdf
           and stores absolute path in thesis.applicationPdfPath.
           Notifies role ARCHIVE (APPLICATION_PENDING_ARCHIVE) — fan-out.

[ARCHIVE]  PATCH  /api/theses/{id}/archive-validate  { approved, comment? }
           PENDING_ARCHIVE_VALIDATION  →  PENDING_SERVICE_VALIDATION | APPLICATION_REJECTED_BY_ARCHIVE
           On rejection: comment REQUIRED, stored in thesis.archiveComment.
           On approval: notifies role STUDENT_SERVICE (APPLICATION_PENDING_SERVICE).
           On rejection: notifies student (APPLICATION_REJECTED_BY_ARCHIVE).

[STUDENT_SERVICE]  PATCH  /api/theses/{id}/service-validate  { approved, comment? }
           PENDING_SERVICE_VALIDATION  →  IN_PROGRESS | APPLICATION_REJECTED_BY_SERVICE
           On rejection: comment REQUIRED, stored in thesis.serviceComment.
           On approval: notifies student (APPLICATION_VALIDATED).
           On rejection: notifies student (APPLICATION_REJECTED_BY_SERVICE).

[STUDENT]  POST   /api/theses/{id}/versions  (multipart file)
           IN_PROGRESS or FINAL_SUBMITTED (any number of times).
           Validates PDF (MIME + extension). Writes to uploads/, then saves DB row.
           No status change. No notification.

[STUDENT or MENTOR]  POST /api/theses/{id}/versions/{vid}/comments  { content }
           Any status (once the version exists). Only student-owner or assigned mentor.
           No status change. No notification.

[STUDENT]  PATCH  /api/theses/{id}/versions/{vid}/mark-final
           Only from IN_PROGRESS. Clears prior final flag. Sets version.isFinal = true.
           Transitions IN_PROGRESS → FINAL_SUBMITTED.
           No notification. (Compare NotificationType.FINAL_VERSION_SUBMITTED — defined but never sent.)

[MENTOR]   PATCH  /api/theses/{id}/approve-final
           FINAL_SUBMITTED  →  MENTOR_APPROVED
           Rules: must be assigned mentor.
           Notifies student (MENTOR_APPROVED_THESIS).

[MENTOR]   POST   /api/theses/{id}/committee/propose  { professorIds: [uuid, uuid] }
           Requires thesis at MENTOR_APPROVED. Requires no existing committee.
           Auto-adds mentor as MENTOR_MEMBER + 2 FORMAL_MEMBERs.
           No status change. No notification. (COMMITTEE_FORMED is defined but only sent on approval — see next step.)

[STUDENT_SERVICE]  POST /api/theses/{id}/committee/approve
           MENTOR_APPROVED  →  COMMITTEE_REVIEW
           Requires exactly 3 committee members. Stamps approvedBy/At on all.
           Sets thesis.committeeReviewStartedAt = now (starts 5-business-day clock).
           ⚠️ Does NOT send COMMITTEE_FORMED notifications (defined but unused).

[COMMITTEE MEMBER PROFESSOR]  PATCH /api/theses/{id}/committee/{memberId}/review  { notes }
           COMMITTEE_REVIEW (only). Only the professor on that CommitteeMember row.
           Notes can be null. No status change. No notification.

[STUDENT_SERVICE]  POST /api/theses/{id}/committee/accept-review
           COMMITTEE_REVIEW  →  COMMITTEE_ACCEPTED  →  PENDING_DEFENSE_CHECK
           Two history rows written in one call.
           ⚠️ Does NOT send COMMITTEE_REVIEW_ACCEPTED notification (defined but unused).

[SYSTEM, scheduled]  autoAdvanceStaleCommitteeReviews (every 30min)
           Any thesis still in COMMITTEE_REVIEW whose committeeReviewStartedAt is
           <= 5 business days ago (inclusive — Item #9):
             COMMITTEE_REVIEW → COMMITTEE_ACCEPTED → PENDING_DEFENSE_CHECK
           Notifies student + all committee members (COMMITTEE_REVIEW_AUTO_ADVANCED).
           Mentor is notified once via the committee seat (no duplicate).

[STUDENT_SERVICE]  PATCH /api/theses/{id}/defense-eligibility  { examsCompleted, documentationComplete }   (Item #8)
           PENDING_DEFENSE_CHECK → PENDING_DEFENSE_SCHEDULING — ONLY if BOTH booleans are true.
           Either false → 400, no transition, no history row, no notification.
           On success notifies student (DEFENSE_ELIGIBILITY_VERIFIED). Requires role STUDENT_SERVICE.
           This is now the ONLY action that moves a thesis out of PENDING_DEFENSE_CHECK.

[STUDENT]  POST /api/theses/{id}/defenses/request  { room, scheduledAt }  (redesigned 2026-08-27)
           STUDENT PROPOSES the actual room/date/time — not a signal, an actual proposal.
           Allowed when PENDING_DEFENSE_SCHEDULING (post-verification, first proposal) OR
           DEFENSE_SCHEDULED with no active Defense (rebooking after a cancellation) + thesis
           owner (else 400/403). Rejects a second proposal while one is already PENDING.
           scheduledAt must be 5-15 days (inclusive) after this request's own createdAt.
           Creates a DefenseRequest(PENDING). Does NOT create a Defense, does NOT change
           thesis status. Notifies role STUDENT_SERVICE (DEFENSE_REQUESTED) — fan-out.

[STUDENT_SERVICE]  PATCH /api/theses/{id}/defenses/request/decision  { approved, reason? }
           Operates on the thesis's current PENDING DefenseRequest (400 if none exists).
           REJECT: reason REQUIRED. Marks REJECTED, stores reason, notifies student
           (DEFENSE_REQUEST_REJECTED, carries the reason). No Defense, no status change.
           APPROVE: re-validates the 5-15 day window (against the request's ORIGINAL
           createdAt) and room availability (fresh DB query) — if either now fails, the
           request is REJECTED with the conflict as the reason instead (no 500, no Defense).
           If both pass: creates exactly one Defense (room/scheduledAt copied from the
           request), transitions PENDING_DEFENSE_SCHEDULING → DEFENSE_SCHEDULED (only on
           first scheduling), notifies student + every committee member (incl. mentor, once)
           DEFENSE_SCHEDULED with room + time in the message body.
           ⚠️ MENTOR can no longer schedule directly (Item #6, unchanged) — 403.

[Anyone with thesis read access]  GET /api/theses/{id}/defenses/request
           Full proposal history (current + past rejected/approved), newest first.
           Thesis-scoped via the same ThesisReadAccessPolicy as every other thesis read.

[STUDENT or MENTOR]  PATCH /api/theses/{id}/defenses/cancel
           Cancels the active defense. Thesis status unchanged (stays DEFENSE_SCHEDULED) —
           this is exactly what lets the student submit a brand-new DefenseRequest afterward.
           ⚠️ Does NOT send DEFENSE_CANCELLED notification (defined but unused).

[SYSTEM, scheduled]  sendDefenseReminders (every 30min)
           For each active defense with reminderSentAt=null scheduled in [now+23h, now+25h]:
             Notify student + all committee members (DEFENSE_REMINDER).
             Mentor is notified once via the committee seat (no duplicate).
             Stamp reminderSentAt = now().

[SEATED COMMITTEE MEMBER of THIS thesis]  POST /api/theses/{id}/defenses/{did}/result  { grade 5-10, notes? }
           (BUG-13 fixed 2026-08-17: requireCommitteeSeat via existsByThesisAndProfessor;
            role alone is NOT enough — STUDENT_SERVICE/ARCHIVE/owner/unseated COMMITTEE → 403.)
           DEFENSE_SCHEDULED (only). Defense must not be cancelled, no prior result.
           Creates DefenseResult.
           Generates DT-YYYY-NNNN registration number (counts prefix + 1).
           Sets thesis.archiveDate, thesis.archivedBy.
           Transitions to ARCHIVED.
           ⚠️ Does NOT send THESIS_GRADED or THESIS_ARCHIVED notification (defined but unused).
```

### Notification types that are DEFINED but NEVER SENT
Grep confirms these enum values exist in `NotificationType.java` but no service calls `notificationService.notify(…, TYPE)` with them: `COMMITTEE_FORMED`, `COMMITTEE_REVIEW_ACCEPTED`, `DEFENSE_SCHEDULED`, `DEFENSE_CANCELLED`, `THESIS_GRADED`, `THESIS_ARCHIVED`, `FINAL_VERSION_SUBMITTED`. This is a real notification gap — see Known bugs.

---

## 4. ROLES AND PERMISSIONS

**STUDENT**
- Sees: own theses only (`findByStudent`).
- Can: create thesis, submit mentor request (from TOPIC_SELECTION or MENTOR_REJECTED_TOPIC), revise proposal (from MENTOR_REQUESTED_CHANGES), submit application (fresh or after either rejection), upload versions (IN_PROGRESS or FINAL_SUBMITTED), mark final (IN_PROGRESS), add comments (on own thesis versions), **propose a defense room/date/time (from PENDING_DEFENSE_SCHEDULING, i.e. after STUDENT_SERVICE has verified eligibility — Item #8; or again after a cancellation — redesigned 2026-08-27, creates a `DefenseRequest`, does NOT itself schedule anything)**, cancel own defense, download own application PDF.
- Rules: only one active (non-ARCHIVED) thesis at a time.

**MENTOR**
- Sees: theses assigned to them (`findByMentor`).
- Can: decide mentor request (only for their assigned thesis), approve final thesis, propose committee (from MENTOR_APPROVED), cancel defense on their thesis, add comments on assigned thesis versions.
- **CANNOT schedule a defense** (changed in Item #6 — scheduling is STUDENT_SERVICE-only, enforced backend-side).
- Auto-included on the committee they propose (as MENTOR_MEMBER).
- Enforced business rule: max 10 active theses at once (`countActiveMentorTheses`).

**STUDENT_SERVICE**
- Sees: ALL theses (`findAll` fallthrough in `getMyTheses`).
- Can: decide eligibility, service-validate applications, approve committee, accept committee review, **verify defense eligibility (PENDING_DEFENSE_CHECK → PENDING_DEFENSE_SCHEDULING — Item #8)**, **approve or reject a student's proposed defense room/date/time (redesigned 2026-08-27 — no longer enters the room/time directly; approving a valid, non-conflicting `DefenseRequest` is what creates the real `Defense` and schedules the thesis)**. **CANNOT record a defense grade** (BUG-13 fixed 2026-08-17 — grading is committee-seat-scoped; STUDENT_SERVICE retains full read/oversight but not the grade write).
- Read access to all versions/comments.

**COMMITTEE**
- Sees: ALL theses (falls into the `else { findAll() }` branch — see bug §8).
- Can: submit review notes ONLY if they hold a CommitteeMember row on the thesis (endpoint checks membership, not role). Record a defense grade ONLY if seated on that thesis's committee (BUG-13 fixed 2026-08-17 — `requireCommitteeSeat`; role alone no longer suffices). Note: seats are held by MENTOR-role professors, so the dedicated COMMITTEE-role user grades only if genuinely seated.
- Read access to all versions/comments via `checkReadAccess`.

**ARCHIVE**
- Sees: only theses in `PENDING_ARCHIVE_VALIDATION` + `ARCHIVED` (explicit filter in `getMyTheses`).
- Can: archive-validate applications. Search archived theses by registration number.
- Read access to all versions/comments (⚠️ probably too broad — see bug §8).

### Known permission issues (details in §8)
- COMMITTEE sees every thesis (should be scoped to committees they're on).
- COMMITTEE reads every version/comment (should be limited to the final version).
- ARCHIVE has read access to all versions (probably shouldn't).
- `submitReviewNotes` intentionally does NOT check `Role`; this is by design because MENTOR-role users hold seats, but worth noting for readers.
- ~~`recordResult` allows STUDENT_SERVICE in addition to COMMITTEE~~ — **FIXED 2026-08-17 (BUG-13)**: grading is now restricted to a seated committee member of the specific thesis (`requireCommitteeSeat`); STUDENT_SERVICE can no longer grade.
- `POST /api/auth/register` allows arbitrary role selection — anyone can register as ARCHIVE or STUDENT_SERVICE.

---

## 5. FRONTEND

### Routes actually registered (`src/routes/AppRouter.tsx`)
- `/login` — LoginPage (AuthLayout)
- `/dashboard` — DashboardPage (AppLayout, ProtectedRoute)
- `/theses` — ThesesListPage
- `/theses/new` — CreateThesisPage
- `/theses/:id` — ThesisDetailPage
- `/notifications` — NotificationsPage
- `/committee` — CommitteePage (MENTOR, STUDENT_SERVICE, COMMITTEE only)
- `/defenses` — DefensesPage (STUDENT, MENTOR, COMMITTEE, STUDENT_SERVICE only)
- `/archive` — ArchivePage (ARCHIVE, STUDENT_SERVICE only)
- `/students` — ManageCreditsPage (STUDENT_SERVICE only — Item #13; find a student + set credits)
- `/` → redirects to `/dashboard`
- `*` → redirects to `/dashboard` (catch-all)

### Pages
- **LoginPage** (`pages/auth/LoginPage.tsx`) — email/password form. Sets Zustand `login()` on success.
- **DashboardPage** — 4 role-filtered quick-action cards (View Theses, Notifications, Committee Management, Defense Scheduling).
- **ThesesListPage** — filtered list; ARCHIVE users get a registration-number search box.
- **CreateThesisPage** — student-only title + comment form.
- **ThesisDetailPage** — the "kitchen sink" page. Contains all workflow UI: header card, archive record card (if ARCHIVED), application-PDF download button, comments block, versions section (via `features/versions/VersionsSection`), committee section (via `features/committee/CommitteeSection`), defense section (via `features/defense/DefenseSection`), mentor tri-decision buttons, revise-proposal modal (via `features/revise-proposal/`), status history timeline.
- **NotificationsPage** — history of the current user's notifications.

### Feature folders
- `features/mentor-picker/MentorPickerModal.tsx`
- `features/revise-proposal/ReviseProposalModal.tsx`
- `features/versions/VersionUploader.tsx`, `CommentList.tsx`, `VersionsSection.tsx`
- `features/committee/ProposeCommitteeModal.tsx`, `CommitteeSection.tsx`
- `features/defense/ProposeDefenseModal.tsx`, `RejectDefenseRequestModal.tsx`, `RecordGradeModal.tsx`, `DefenseSection.tsx`

### Layouts
- `AuthLayout.tsx` — centered card layout for login.
- `AppLayout.tsx` — Sidebar + Header + Outlet.
- `components/layout/Sidebar.tsx` — role-filtered nav.
- `components/layout/Header.tsx` — top bar with user info.

### API client structure
- `src/api/client.ts` — Axios instance with `baseURL='/api'` (uses Vite dev proxy). Request interceptor attaches `Bearer` token from Zustand. Response interceptor: 401/403 → logout + redirect; 5xx → generic error toast; 400 with backend `message` → toast that message.
- Per-domain modules:
  - `authApi.ts` — login, register
  - `userApi.ts` — getByRole
  - `thesisApi.ts` — 12 methods covering the full thesis controller
  - `versionApi.ts`
  - `committeeApi.ts`
  - `defenseApi.ts`
  - `notificationApi.ts`

### Zustand / auth state
- `src/store/authStore.ts` — `token`, `user`, `login()`, `logout()`, `isAuthenticated()`.
- Persisted via `persist` middleware to `localStorage` key `praksa-auth`.

### JWT storage
- Held in Zustand state (persisted to localStorage).
- Read by axios interceptor via `useAuthStore.getState().token` (works outside React because Zustand `getState()` is sync).

### Role-based UI
- Sidebar and DashboardPage compute `visibleItems` from `roles: Role[]` fields.
- ThesisDetailPage branches on `user.role` + `thesis.status` to decide which action buttons to render.

### Important TypeScript types (`src/types/api.ts`)
- Mirror all backend DTOs. Enums as string unions. `Role`, `ThesisStatus` (all 19), `MemberRole`, `MentorDecision`.
- `ApiResponse<T>` envelope.
- `Thesis` includes `revisionCount`, `archiveComment`, `serviceComment`, all archive metadata fields, and `hasApplicationPdf`.

### UI patterns / components
- `components/ui/PageHeader.tsx`, `StatusBadge.tsx`, `Modal.tsx`, `EmptyState.tsx`, `Skeleton.tsx`
- Toasts via `sonner`.
- Utility: `utils/cn.ts` (clsx + tailwind-merge), `utils/date.ts`.

---

## 6. DATABASE

### Tables → Entities (`ddl-auto=update` on PostgreSQL)

| Table | Entity | Notable fields |
|---|---|---|
| `users` | `User` | pk=`id` (UUID); `email` UNIQUE; `password_hash`; `role` (varchar, enum name); `index_number` UNIQUE (nullable); `full_name`; `created_at` |
| `theses` | `Thesis` | pk=`id`; FK `student_id` (nn); FK `mentor_id` (nullable); `title`; `student_comment` text; `mentor_comment` text; **`archive_comment`** text; **`service_comment`** text; `status` (varchar); **`revision_count`** int nn default 0; **`committee_review_started_at`**; **`application_pdf_path`** varchar(1024); `submission_deadline` (1-month application deadline, enforced); `application_submitted_at`; `last_version_submitted_at`; **`defense_deadline`** (added 2026-08-28 — 1-month defense-completion deadline, stamped at `verifyDefenseEligibility`, extendable up to 15 days via `DeadlineExtensionRequest`; NOT itself enforced anywhere — see the dated entry); `created_at`; `updated_at`; **`archive_registration_number`** varchar(50) UNIQUE; **`archive_date`**; FK **`archived_by`**; **`archive_notes`** text |
| `thesis_versions` | `ThesisVersion` | pk=`id`; FK `thesis_id`; `version_number` int; UNIQUE (`thesis_id`, `version_number`); `pdf_url`; `is_final` bool; `uploaded_at` |
| `thesis_comments` | `ThesisComment` | pk=`id`; FK `version_id`; FK `author_id`; `content` text; `created_at` |
| `committee_members` | `CommitteeMember` | pk=`id`; FK `thesis_id`; FK `professor_id`; UNIQUE (`thesis_id`, `professor_id`); `member_role` (varchar enum); FK `proposed_by`; FK `approved_by`; `approved_at`; `notes` text |
| `defenses` | `Defense` | pk=`id`; FK `thesis_id`; `room` (indexed); `scheduled_at`; `is_cancelled` bool; FK `cancelled_by`; `cancelled_at`; `created_at`; **`reminder_sent_at`** |
| `defense_requests` | `DefenseRequest` | (added 2026-08-27) pk=`id`; FK `thesis_id`; FK `requested_by`; `room`; `scheduled_at`; `status` varchar (PENDING/APPROVED/REJECTED); `reason` text nullable; `created_at`; `decided_at` nullable; FK `decided_by` nullable. Index on `(thesis_id, status)` + `room`. |
| `defense_results` | `DefenseResult` | pk=`id`; FK `defense_id` UNIQUE (one-to-one); `grade` int; `notes` text; FK `recorded_by`; `recorded_at` |
| `deadline_extension_requests` | `DeadlineExtensionRequest` | (added 2026-08-28) pk=`id`; FK `thesis_id`; FK `requested_by`; `reason` text; `requested_days` int (1-15); `status` varchar (PENDING/APPROVED/REJECTED); `decision_reason` text nullable; `previous_deadline` timestamptz nullable; `new_deadline` timestamptz nullable (only ever populated on APPROVED); `created_at`; `decided_at` nullable; FK `decided_by` nullable. Index on `(thesis_id, status)`. |
| `notifications` | `Notification` | pk=`id`; FK `user_id`; FK `thesis_id` nullable; `type` varchar (enum name as string); `is_sent` bool; `sent_at`; `created_at` |
| `thesis_status_history` | `ThesisStatusHistory` | pk=`id`; FK `thesis_id`; `old_status` varchar (nullable — null on creation); `new_status` varchar; FK `changed_by` nullable (null = system); `changed_at` |

Bolded fields were added in the two-stage-validation / archive-metadata / revision-tracking / reminder / PDF-generation refactors.

### Unique constraints
- `users.email`
- `users.index_number`
- `theses.archive_registration_number`
- `thesis_versions (thesis_id, version_number)`
- `committee_members (thesis_id, professor_id)`
- `defense_results.defense_id` (from `@OneToOne(unique=true)`)

### Enum persistence
- All `@Enumerated(EnumType.STRING)` — DB stores enum names as varchar. Safe against reordering.
- `Notification.type` is a plain `String` column populated with `NotificationType.name()` (no `@Enumerated`).

### Fields defined but unused
- `Thesis.submissionDeadline` — column exists, never set, never read.
- `Thesis.archiveNotes` — column exists; DTO exposes it; no endpoint sets or updates it.
- `NotificationType`: 7 enum values are defined but never used in any `notify(…)` call (see §3).

### Indexes
- Only default (PK + UNIQUE) indexes. No custom `@Index` annotations. For a school-scale dataset this is fine.

---

## 7. AUTHENTICATION

**Mode:** Local JWT. `auth.external-enabled=false` in `application.properties`.

**Login (`POST /api/auth/login`):**
1. `AuthenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, password))` — throws if invalid.
2. Load user by email; generate JWT via `JwtUtil.generateToken(email, role)`.
3. Response: `{ token, userId, email, fullName, role }`.

**Register (`POST /api/auth/register`):**
- Public, unrestricted role selection.
- Rejects duplicate email; requires `indexNumber` for STUDENT and rejects duplicates; other roles ignore `indexNumber`.
- Returns a JWT immediately.

**JWT generation (`JwtUtil.generateToken`):**
- HMAC symmetric using `Keys.hmacShaKeyFor(secret.getBytes(UTF_8))`.
- Claims: `sub=email`, `role=<enum name>`, `iat`, `exp`.
- Expiration: 24h (`jwt.expiration-ms=86400000`).

**Filter (`JwtAuthFilter`):**
- Runs `OncePerRequest`, before `UsernamePasswordAuthenticationFilter`.
- Reads `Authorization: Bearer …`. Validates. Extracts email. Loads via `UserDetailsService`.
- If user not found and `auth.external-enabled=true`: `autoProvision(email, token)` creates a new `User` row from JWT claims (`role`, `name`, `index_number`), with a random BCrypt hash as password.
- Sets `SecurityContext` authentication.

**Frontend token storage:**
- Zustand `authStore.token` + `authStore.user`, persisted to `localStorage` under key `praksa-auth`.

**Axios interceptor:**
- Request: attaches `Authorization: Bearer <token>` if token exists.
- Response: on 401/403 → clear store + redirect to `/login`; on 5xx → toast; on 4xx with `message` → toast that message.

**External auth scaffold:**
- `auth.external-enabled` flag. `JwtAuthFilter` autoprovision path is fully implemented but never exercised.
- **BUG-15 (2026-08-17):** `JwtUtil` now selects its HMAC key by mode — local mode uses `jwt.secret`, external mode uses the SEPARATE `auth.external-jwt-secret`. Each mode is self-consistent (signs & verifies with the same key), so external mode never touches the local/dev key. Still symmetric HMAC only — no JWKS/public-key support; real external integration would still need `JwtUtil` upgraded to fetch a public key.

**Configuration flags relevant to auth:**
- `jwt.secret` (local key, required), `auth.external-jwt-secret` (external key, required when external enabled), `jwt.expiration-ms`, `auth.external-enabled`.

**Security concerns:**
1. ~~JWT secret is committed to `application.properties`.~~ **FIXED (BUG-15, 2026-08-17)** — no inline JWT/DB secret defaults remain; both come from env / git-ignored local file and fail fast if missing; external key is separated.
2. `POST /api/auth/register` accepts any role — no admin check. *(Note: FIXED separately under BUG-2 — register now hard-codes STUDENT.)*
3. Symmetric HMAC only; no key rotation.
4. No refresh tokens; user re-logs in after 24h.

---

## 8. KNOWN BUGS / PROBLEMS

### Critical
- **BUG-1: FIXED (2026-08-16).** `/committee`, `/defenses`, `/archive` routes now exist in `AppRouter.tsx` with role guards; new pages `CommitteePage.tsx`, `DefensesPage.tsx`, `ArchivePage.tsx` are backed by new endpoints `GET /api/theses/committee` and `GET /api/theses/defenses`.
- **BUG-2: FIXED (2026-08-17).** `POST /api/auth/register` no longer trusts the requested role. `AuthServiceImpl.register` now hard-codes `role = Role.STUDENT` and ignores `request.getRole()`, so a public/unauthenticated caller can only ever create a STUDENT. Privileged roles (MENTOR, STUDENT_SERVICE, COMMITTEE, ARCHIVE) remain provisioned only via `DataInitializer`/admin mechanisms. See the dated entry at the bottom for full details, tests, and enforcement location.

### High
- **BUG-3: PARTIALLY FIXED (2026-08-16).** `ThesisServiceImpl.getMyTheses` no longer returns every thesis to COMMITTEE — it now scopes to `DEFENSE_SCHEDULED + ARCHIVED` (their grading scope). The `/api/theses/committee` and `/api/theses/defenses` endpoints properly scope MENTOR to theses they mentor OR serve on as a committee member. `ThesisVersionServiceImpl.checkReadAccess` still lets COMMITTEE read every version (see BUG-4).
- **BUG-4: PARTIALLY FIXED (2026-08-16).** `ThesisVersionServiceImpl` now splits access into a coarse `checkThesisReadAccess` (may this user see the version area at all?) and a fine-grained `canSeeVersion` (final-only for committee members). Applied to `getVersions`, `downloadVersion`, and `getComments`. Committee-member identification uses `CommitteeMemberRepository.existsByThesisAndProfessor` (works for both MENTOR-role formal members and any future COMMITTEE-role member). A COMMITTEE-role user who is NOT on the committee is now denied entirely. ARCHIVE still reads all versions (task said keep existing behavior — revisit under a future roadmap item if needed).
- **BUG-5: FIXED (2026-08-16, Item #4).** All seven types (`COMMITTEE_FORMED`, `COMMITTEE_REVIEW_ACCEPTED`, `DEFENSE_SCHEDULED`, `DEFENSE_CANCELLED`, `THESIS_GRADED`, `THESIS_ARCHIVED`, `FINAL_VERSION_SUBMITTED`) are now emitted from `CommitteeServiceImpl`, `DefenseServiceImpl`, `DefenseResultServiceImpl`, and `ThesisVersionServiceImpl`. See the CURRENT NEXT STEP section for the full event→recipient→type table.
- **BUG-6: FIXED (2026-08-17, Item #15).** SMTP is now production-safe and provider-agnostic. `application.properties` holds NO mail credentials — host/port/username/password/auth/starttls are all `${ENV:default}` placeholders (username/password default to empty). A new master switch `app.mail.enabled` (`${MAIL_ENABLED:false}`) gates delivery: when false (default, no SMTP configured) `EmailService.sendAsync` skips the send cleanly and leaves the row `isSent=false` (never pretends to have sent); when true it sends via whatever SMTP provider is configured. Real creds go in the git-ignored `application-local.properties` (loaded via `spring.config.import`) or env vars. See the Item #15 dated entry below.

### Medium
- **BUG-7: RESOLVED (2026-08-17, Item #8).** An explicit STUDENT_SERVICE "check defense conditions" step now exists (`PATCH /api/theses/{id}/defense-eligibility`), gating `PENDING_DEFENSE_CHECK → PENDING_DEFENSE_SCHEDULING`. Note: the check was placed at the `PENDING_DEFENSE_CHECK` boundary (per the Item #8 spec), so `acceptCommitteeReview` and the auto-advance job still fast-forward `COMMITTEE_REVIEW → COMMITTEE_ACCEPTED → PENDING_DEFENSE_CHECK` — `COMMITTEE_ACCEPTED` remains a pass-through; the explicit human gate is at the next boundary instead.
- **BUG-8: FIXED (2026-08-16, Item #6).** Student-initiated defense request now exists: `POST /api/theses/{id}/defenses/request` moves `PENDING_DEFENSE_CHECK → PENDING_DEFENSE_SCHEDULING`; STUDENT_SERVICE then schedules. Mentors can no longer schedule directly. See the CURRENT NEXT STEP section for full details.
- **BUG-9: No defense-protocol PDF.** Spec step 11 (student downloads a protocol PDF before defense) is not implemented.
- **BUG-10: No credit gate on eligibility.** `User` has no `credits` field. Spec calls for a 200-credit check; the code just treats eligibility as an admin decision on nothing.
- **BUG-11: `Thesis.submissionDeadline` is dead code.** Column exists, never populated, never checked. Spec has a 1-month deadline concept.
- **BUG-12: FIXED (2026-08-18, P2.2).** `Thesis.archiveNotes` is now editable by the ARCHIVE role via `PATCH /api/theses/{id}/archive-notes` (role ARCHIVE + status ARCHIVED; reuses the existing field, no schema change; never changes status, never notifies). Frontend editor lives in the archive record card on `ThesisDetailPage`. See the dated combined P2.2+P3.3+P3.4 entry near the top.
- **BUG-13: FIXED (2026-08-17).** `DefenseResultServiceImpl.recordResult` is now scoped to the thesis's committee. Grading requires the caller to hold a `CommitteeMember` seat on THIS thesis (`committeeRepository.existsByThesisAndProfessor`); role alone is never sufficient. Since seats are held only by professors, `STUDENT_SERVICE` (and `ARCHIVE`, and the STUDENT owner) can no longer record a grade — matching the "only the committee grades" spec. See the dated write-side grading IDOR entry at the bottom.
- **BUG-14: FIXED (2026-08-18, P3.3).** Configuration-driven CORS added in `SecurityConfig` (`.cors(...)` + `CorsConfigurationSource` bean) via `app.cors.allowed-origins` (`${CORS_ALLOWED_ORIGINS:http://localhost:5173}`) — explicit origin list (never `*`), `allowCredentials=false` (Bearer-header auth, not cookies), Authorization allowed, Content-Disposition exposed. Local dev unaffected (proxy). See the dated combined P2.2+P3.3+P3.4 entry near the top.

### Low
- **BUG-15: PARTIALLY FIXED (2026-08-17, Item #15).** SMTP credentials are fully removed from tracked source (env/local-file only). The DB password and JWT secret are now env-overridable (`SPRING_DATASOURCE_PASSWORD`, `JWT_SECRET`), but the tracked `application.properties` still ships their LOCAL-DEV-ONLY values as `${ENV:default}` fallbacks so one-command local dev keeps working — production MUST set the env vars (or use `application-local.properties`). Fully removing those two defaults (no inline fallback) is deferred to keep dev usable and stay within Item #15's focused scope. Same JWT key still used for local + external tokens (blocks external-auth productionization).
- **BUG-16: Only 1 real integration test** (`AuthIntegrationTest`). Zero coverage for thesis workflow, versions, committee, defense.
- **BUG-17: Stale comment in `ThesisVersionServiceImpl.checkReadAccess`** (line 281): says "admin/archive/committee". "admin" is legacy — the actual role is `STUDENT_SERVICE`. Code is right; comment lies.
- **BUG-18: `Notification.type` is a raw `String` column** rather than `@Enumerated(EnumType.STRING)`. Works, but a typo in a `notify()` call would silently write a bad value.
- **BUG-19: FIXED (2026-08-17).** `NotificationServiceImpl.getUnsentNotifications()` now calls `requireRole(securityUtils.getCurrentUser(), Role.STUDENT_SERVICE)` as its FIRST statement — before `notificationRepository.findByIsSentFalse()`. Any non-STUDENT_SERVICE authenticated caller (STUDENT, MENTOR, COMMITTEE, ARCHIVE) gets `UnauthorizedException` → HTTP 403 and the repository is never queried. Unauthenticated callers are still rejected by Spring Security (401) upstream. Enforced at the service authorization boundary (mirrors the `requireRole` style used across `ThesisServiceImpl`); no controller, SecurityConfig, or frontend change. See the dated entry at the bottom.

---

## 9. CURRENT FRONTEND/BACKEND COMPLETENESS

### IMPLEMENTED (verified in code)
- Full thesis lifecycle backend: create → eligibility → mentor request/decide → revise → application → two-stage validation → in-progress → versions → mark final → mentor approval → committee proposal → committee approval → committee notes → accept review → schedule/cancel defense → grade → archive.
- Application PDF generation on `submitApplication` (OpenHTMLtoPDF).
- Archive registration number generator (`DT-YYYY-NNNN`, per-year sequence with DB unique constraint).
- Revision counter on Thesis, incremented on every `REQUEST_CHANGES`.
- Two scheduled jobs: committee auto-advance (5 business days), defense reminder (24h window, idempotent via `reminderSentAt`).
- Notification records persisted in caller transaction; async email dispatch on named executor.
- JWT auth (local) + JwtAuthFilter + external-auth scaffold.
- Auto data seeding (7 test users).
- Full Swagger UI at `/swagger-ui.html` with Bearer auth.
- Actuator health at `/actuator/health`.
- 9 REST controllers, 8 services, 8 repositories.
- Frontend: login, dashboard, theses list (with reg-number search for ARCHIVE), create thesis, thesis detail (full workflow UI), notifications page, role-filtered sidebar, protected routes, Axios interceptor with auto-logout on 401.
- Frontend feature modules: mentor picker, revise proposal, versions section (upload/list/mark-final/comments), committee section (propose/approve/notes/accept), defense section (schedule/cancel/grade).
- Complete TypeScript type mirror of backend DTOs.

### PARTIALLY IMPLEMENTED
| Feature | Backend | Frontend |
|---|---|---|
| Committee page | ✅ endpoints | ❌ no `/committee` route (nav link is broken) |
| Defenses page | ✅ endpoints | ❌ no `/defenses` route (nav link is broken) |
| Archive page | ✅ endpoints | ❌ no `/archive` route (nav link is broken) |
| User registration | ✅ endpoint (unrestricted role — BUG-2) | ✅ `/register` page (Item #12, STUDENT-only UI) |
| External JWT auth | ✅ scaffold + config flag | ❌ flag is off; no external IdP wired |
| Email delivery | ✅ SMTP client wired; env-backed, `app.mail.enabled` gate (Item #15) | disabled by default in dev — rows stay `isSent=false` honestly; set creds + `MAIL_ENABLED=true` to send |
| Notifications for late-stage events | ⚠️ 7 types defined but never emitted | — |
| Archive notes | ✅ column + DTO field | ❌ no endpoint to set |

### NOT IMPLEMENTED (vs. original workflow spec)
- ~~200-credit eligibility gate~~ — DONE (Item #5).
- ~~Explicit "Student Service checks defense conditions" step~~ — DONE (Item #8: `PATCH /api/theses/{id}/defense-eligibility`).
- ~~Student-initiated defense request~~ — DONE (Item #6, now gated behind Item #8 verification).
- ~~Defense record PDF ("записник за одбрана")~~ — DONE (Item #11: `GET /api/theses/{thesisId}/defenses/{defenseId}/record-pdf`, available after grading).
- 1-month `submissionDeadline` enforcement (field exists but unused).
- Refresh tokens.
- CORS config for production.
- Unit/integration tests for the thesis/committee/defense flows.
- Frontend register page.
- Committee-only view of the final version (currently sees all).
- Notification retry job (unsent notifications sit forever).

---

## 10. CURRENT ROADMAP (based on the actual current code)

### P0 — required before this can be called complete

- **P0.1: Fix broken sidebar links.** Either add three real pages (`/committee`, `/defenses`, `/archive`) or remove those nav items. Files: `src/routes/AppRouter.tsx`, `src/components/layout/Sidebar.tsx`, `src/pages/DashboardPage.tsx`. Reason: users see dead-end nav.
- **P0.2: DONE (2026-08-17).** Locked down `POST /api/auth/register` — `AuthServiceImpl.register` forces `role = STUDENT` and ignores the requested role (public registration = STUDENT-only). See the dated entry at the bottom.
- **P0.3: DONE (2026-08-16, Item #4).** Emitted the 7 missing notifications (COMMITTEE_FORMED on approve, COMMITTEE_REVIEW_ACCEPTED on accept-review, DEFENSE_SCHEDULED on schedule, DEFENSE_CANCELLED on cancel, THESIS_GRADED + THESIS_ARCHIVED on recordResult, FINAL_VERSION_SUBMITTED on markAsFinal). Wired into the relevant service methods with correct recipients. See CURRENT NEXT STEP for details.

### P1 — important
- **P1.1: Scope COMMITTEE `getMyTheses` to committees they're on.** Change `ThesisServiceImpl.getMyTheses` to query `CommitteeMemberRepository` for the user's `professor_id` and return the parent theses (union with anything else if the role should also see, say, `DEFENSE_SCHEDULED`).
- **P1.2: Restrict COMMITTEE version visibility to final only.** In `ThesisVersionServiceImpl.checkReadAccess`, gate the `COMMITTEE` bypass on `version.isFinal() == true`. Requires refactoring `getVersions`/`downloadVersion` to filter per-role.
- **P1.3: DONE (2026-08-17, Item #15).** SMTP is env-backed and provider-agnostic with an `app.mail.enabled` gate; disabled cleanly by default in dev (rows stay `isSent=false`), enabled + real creds sends. See the Item #15 dated entry.
- **P1.4: Split step 12 properly.** Add an explicit "check defense conditions" endpoint that transitions `COMMITTEE_ACCEPTED → PENDING_DEFENSE_CHECK`, and change `acceptCommitteeReview` to stop at `COMMITTEE_ACCEPTED`.
- **P1.5: Add student-initiated defense request** (spec step 13). Minimum viable: a status field or a flag that mentor sees as a signal to schedule.
- **P1.6: DONE (2026-08-17, Item #12).** Public `/register` page added (`RegisterPage.tsx`), STUDENT-only UI, wired to the existing `POST /api/auth/register`. ⚠️ The backend was NOT locked down — BUG-2 / P0.2 (endpoint still accepts arbitrary roles) remains OPEN and must be done separately.

### P2 — useful
- **P2.1: Defense protocol PDF.** Similar to `ApplicationPdfService`, generated on schedule; student downloads.
- **P2.2: DONE (2026-08-18).** ARCHIVE role sets/edits `Thesis.archiveNotes` after archiving via `PATCH /api/theses/{id}/archive-notes` (reuses the existing field; no schema change; no status change; no notification). Frontend editor in the archive record card. See the dated combined P2.2+P3.3+P3.4 entry near the top.
- **P2.3: `submissionDeadline` enforcement.** Populate on eligibility approval; scheduled job rejects overdue theses.
- **P2.4: DONE (2026-08-18).** Integration tests for the thesis workflow (happy path + revision loop + rejection loop + committee auto-advance + authorization boundaries + invalid transitions) — 3 new `@SpringBootTest` classes / 16 tests under `src/test/java/com/praksa/integration/`, real PostgreSQL, all passing. See the dated P2.4 entry near the top.
- **P2.5: DONE (2026-08-17, BUG-15 complete).** SMTP creds fully moved out of source (Item #15); DB password + local JWT secret now have NO inline defaults (`${SPRING_DATASOURCE_PASSWORD}`, `${JWT_SECRET}` — startup fails fast if absent), and the external-auth signing key is split into its own required-when-enabled property (`${EXTERNAL_JWT_SECRET:}`). See the BUG-15 dated entry below for full details, tests, and results.
- **P2.6: Notification retry job.** Scheduled task that walks `findByIsSentFalse()` and re-attempts.
- **P2.7: Lock down `/api/notifications/unsent`** to STUDENT_SERVICE or remove it.
- **P2.8: Fix `checkReadAccess` doc comment** to say STUDENT_SERVICE instead of "admin".

### P3 — future / optional
- **P3.1: Real external auth.** Swap symmetric HMAC for RS256 + JWKS. Disable local login. Remove `POST /register`.
- **P3.2: 200-credit gate.** Add `credits: int` on `User` (or a `StudentAcademicRecord` join). Enforce in `decideEligibility`.
- **P3.3: DONE (2026-08-18).** Configuration-driven CORS for split deployment — `app.cors.allowed-origins` (explicit list, no wildcard, Bearer-header compatible, `allowCredentials=false`). See the dated combined P2.2+P3.3+P3.4 entry near the top.
- **P3.4: Mobile-responsive layout.** Sidebar is a fixed 256px block.
- **P3.5: Refresh tokens** or session renewal without re-login.
- **P3.6: DONE (2026-08-18).** Read/unread flag on notifications + secure user-scoped `PATCH /api/notifications/{id}/read`, `PATCH /api/notifications/read-all`, `GET /api/notifications/unread-count`; frontend unread styling + mark-read/mark-all + Sidebar badge. Independent of the email `isSent`/retry path. See the dated "P3.6 — Notification read/unread — COMPLETE" entry near the top.

---

## 11. IMPORTANT ARCHITECTURE RULES

**Do these consistently — the existing code follows them.**

- **Controllers are thin.** Every controller method delegates to a service; no business logic in controllers. See `ThesisController`.
- **Business logic lives in services.** `service/impl/*` classes are `@Service @RequiredArgsConstructor`. They own transactions, guard clauses, and state transitions.
- **Never expose entities across the controller boundary.** Always map to DTOs. Response DTOs live in `dto/*/` with `static from(entity)` factories.
- **Status transitions go through `transitionStatus(thesis, newStatus, changedBy)`.** Every service that changes a status has a private helper that (1) sets status, (2) saves, (3) writes a `ThesisStatusHistory` row. Never call `thesis.setStatus()` directly. `ScheduledTasksService` has its own copy with `changedBy = null` for system actions.
- **Every status change writes a history row.** Even system actions (null `changedBy`).
- **`@Transactional` on service methods, `@Transactional(readOnly=true)` on read-only ones.** DTO mapping happens INSIDE the transaction (see `getMyTheses` for the LazyInitializationException-avoidance comment).
- **Notifications: save row synchronously in caller's tx, then dispatch async email with primitives only.** Never pass a JPA entity into an `@Async` method — the session is gone by then. `NotificationServiceImpl.notify` extracts everything before calling `EmailService.sendAsync`.
- **`EmailService.sendAsync` has its own `@Transactional`** (independent from caller). Marks `isSent=true` on success; logs and returns on failure — never throws upward.
- **Files stored on disk, paths stored in DB.** `FileStorageService.storePdf` returns the absolute path; `Thesis.applicationPdfPath` and `ThesisVersion.pdfUrl` hold paths. Upload flow: write file first, then save DB row; on DB failure, `deleteFile()` the orphan.
- **Version numbering is derived per upload** inside the transaction via `findTopByThesisOrderByVersionNumberDesc` — avoids race between simultaneous uploads.
- **Guard clauses in services:** `requireRole`, `requireStatus`, `requireOwner`. Custom exceptions (`BadRequestException`, `UnauthorizedException`, `ResourceNotFoundException`) handled by `GlobalExceptionHandler`.
- **All status/enum columns use `@Enumerated(EnumType.STRING)`.** DB stores enum names.
- **Response envelope:** all endpoints return `ApiResponse<T>` (never raw data). Frontend `authApi/thesisApi/…` unwrap the `.data.data`.
- **Frontend axios interceptor is the only place that handles 401/403.** Components don't need to.
- **`@PrePersist` on entities** sets `createdAt/updatedAt` (and status defaults on Thesis). No triggers in the DB.
- **DevTools is enabled** — code changes trigger auto-restart. New Maven dependencies need a full manual restart.

---

## 12. TEST ACCOUNTS

Verified in `com/praksa/config/DataInitializer.java`. Seeded only if `users` table is empty on startup. **All passwords: `password123`**.

| Email | Role | Notes |
|---|---|---|
| `student@test.com` | STUDENT | index 2024/001 |
| `mentor@test.com` | MENTOR | |
| `mentor2@test.com` | MENTOR | for committee testing |
| `mentor3@test.com` | MENTOR | for committee testing |
| `service@test.com` | STUDENT_SERVICE | |
| `committee@test.com` | COMMITTEE | for defense grading only |
| `archive@test.com` | ARCHIVE | |

---

## 13. HOW TO RUN THE PROJECT

### Prerequisites
- Java 21 (Adoptium/Temurin recommended)
- Maven wrapper is included (`mvnw.cmd` on Windows)
- Node 18+ (for the frontend)
- PostgreSQL 14+ running locally with:
  - Database: `diploma_system`
  - User: `postgres`
  - Password: `123` (change in `application.properties` if different)

### Backend

```bash
cd C:/Users/bosko/Desktop/praksa
mvnw.cmd spring-boot:run
```

- Boots on `http://localhost:8080`.
- Uploads directory `./uploads` is created on first start.
- Data seeder runs on first startup only (guards on `count() > 0`).
- Auto-restart via DevTools when a class file changes.

### Frontend

```bash
cd C:/Users/bosko/Desktop/praksa-frontend
npm install
npm run dev
```

- Vite serves on `http://localhost:5173`.
- Dev proxy forwards `/api/*` to `http://localhost:8080` (no CORS config on backend).

### Build

- Backend: `mvnw.cmd clean package` → `target/praksa-0.0.1-SNAPSHOT.jar`.
- Frontend: `npm run build` → `dist/`.

### Testing

- Backend: `mvnw.cmd test` — only runs `AuthIntegrationTest` + `PraksaApplicationTests`.
- Frontend: no test runner configured.

### Useful URLs

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Actuator health: `http://localhost:8080/actuator/health`

### Environment / configuration properties (`src/main/resources/application.properties`)

- `spring.datasource.url` / `username` / `password` — PostgreSQL connection. **`password` is a REQUIRED secret with NO inline default (BUG-15)**: env `SPRING_DATASOURCE_PASSWORD`, or `spring.datasource.password` in the git-ignored `application-local.properties`. Startup fails fast if unset. `url`/`username` keep non-secret local-dev defaults.
- `jwt.secret`, `jwt.expiration-ms` — LOCAL JWT signing key + TTL (24h). **`jwt.secret` is a REQUIRED secret with NO inline default (BUG-15)**: env `JWT_SECRET` (or local file). Missing → `JwtUtil` `@PostConstruct` throws at startup.
- `auth.external-enabled` — false by default (env `AUTH_EXTERNAL_ENABLED`); true = verify incoming tokens with the external key + auto-provision unknown users from JWT claims.
- `auth.external-jwt-secret` — **separate** external-provider signing key (env `EXTERNAL_JWT_SECRET`), empty by default. Required ONLY when `auth.external-enabled=true`; if enabled while blank, `JwtUtil` fails fast rather than reusing `jwt.secret` (BUG-15).
- `file.upload-dir` — `uploads`
- `spring.servlet.multipart.max-file-size` — `20MB`
- `spring.mail.*` — SMTP settings (placeholder — email will fail)
- `springdoc.swagger-ui.path` — `/swagger-ui.html`

---

## 14. IMPORTANT FILE TREE

```
praksa/                                                    ← backend repo
├── pom.xml
├── mvnw.cmd
├── application.properties      (src/main/resources/)
├── uploads/                    (generated at runtime)
└── src/main/java/com/praksa/
    ├── PraksaApplication.java
    ├── config/
    │   ├── AsyncConfig.java              ← @EnableAsync + @EnableScheduling + emailTaskExecutor bean
    │   ├── DataInitializer.java          ← seeds 7 test users
    │   ├── OpenApiConfig.java            ← Swagger + Bearer auth scheme
    │   └── PasswordEncoderConfig.java    ← standalone BCrypt bean (breaks a cycle)
    ├── controller/                       ← 8 REST controllers
    ├── dto/                              ← auth/, thesis/, version/, committee/, defense/, notification/, user/, ApiResponse.java
    ├── exception/                        ← BadRequestException, UnauthorizedException, ResourceNotFoundException, GlobalExceptionHandler
    ├── model/
    │   ├── User, Thesis, ThesisVersion, ThesisComment, CommitteeMember,
    │   │   Defense, DefenseResult, Notification, ThesisStatusHistory
    │   └── enums/  Role, ThesisStatus, MentorDecision, MemberRole, NotificationType
    ├── repository/                       ← 8 Spring Data JPA interfaces
    ├── security/
    │   ├── SecurityConfig.java           ← filter chain
    │   ├── JwtAuthFilter.java            ← JWT + optional auto-provision
    │   ├── JwtUtil.java                  ← HMAC symmetric signing
    │   ├── UserDetailsServiceImpl.java
    │   └── SecurityUtils.java            ← getCurrentUser()
    └── service/
        ├── ThesisService (+ impl)                ← core workflow
        ├── ThesisVersionService (+ impl)
        ├── CommitteeService (+ impl)
        ├── DefenseService (+ impl)
        ├── DefenseResultService (+ impl)
        ├── NotificationService (+ impl)
        ├── AuthService (+ impl)
        ├── EmailService                          ← @Async, placeholder SMTP
        ├── ApplicationPdfService                 ← OpenHTMLtoPDF
        ├── FileStorageService                    ← upload/delete PDFs
        └── ScheduledTasksService                 ← 2 @Scheduled jobs

praksa-frontend/                                  ← frontend repo
├── package.json, vite.config.ts, tailwind.config.js
└── src/
    ├── main.tsx, App.tsx, index.css
    ├── api/
    │   ├── client.ts                     ← Axios + interceptors
    │   ├── authApi.ts, userApi.ts, thesisApi.ts, versionApi.ts,
    │   │   committeeApi.ts, defenseApi.ts, notificationApi.ts
    ├── components/
    │   ├── layout/ Sidebar.tsx, Header.tsx
    │   └── ui/     PageHeader, StatusBadge, Modal, EmptyState, Skeleton
    ├── features/
    │   ├── mentor-picker/  MentorPickerModal
    │   ├── revise-proposal/ ReviseProposalModal
    │   ├── versions/       VersionUploader, CommentList, VersionsSection
    │   ├── committee/      ProposeCommitteeModal, CommitteeSection
    │   └── defense/        ProposeDefenseModal, RejectDefenseRequestModal, RecordGradeModal, DefenseSection
    ├── layouts/            AuthLayout, AppLayout
    ├── pages/
    │   ├── auth/ LoginPage
    │   ├── DashboardPage, ThesesListPage, CreateThesisPage,
    │   │   ThesisDetailPage, NotificationsPage
    ├── routes/             AppRouter, ProtectedRoute
    ├── store/              authStore.ts
    ├── types/              api.ts       ← mirrors backend DTOs
    └── utils/              cn.ts, date.ts
```

---

## CURRENT NEXT STEP

**CURRENT NEXT STEP = SUBMITTED. DEVELOPMENT STOPPED PENDING PROFESSOR FEEDBACK.** The project has been finally committed and pushed to GitHub — see the dated "Final Git Commit + Push" entry directly below for the exact verification performed, the commit hash, and the push result. Do NOT start any new feature, refactor, or fix without explicit direction from the user; the next legitimate trigger for further work is feedback from the professor.

---

**Final Git Commit + Push — DONE (2026-08-31). 🏁 Backend committed and pushed to `origin/main`; frontend already in sync (nothing new to push). No destructive git commands used; no secrets committed; no application logic changed.**

**1. Scope.** A pure Git finalization/submission task, explicitly NOT a development task — no refactor, no bug fix, no feature, no logic change of any kind. The goal: verify the current working tree, prepare the final commit, and push everything to GitHub. `CLAUDE.md` was read in full, start to finish (2936 lines, in sequential chunks), before any git command was run, per the task's explicit requirement.

**2. Repositories identified (before any change).**
- Backend: `C:\Users\bosko\Desktop\praksa` — remote `origin` = `https://github.com/BoshkoSmileski/praksa.git`, branch `main`, `HEAD` at `fb2f4d0` ("Finalize diploma thesis management system"), up to date with `origin/main`.
- Frontend: `C:\Users\bosko\Desktop\praksa-frontend` — remote `origin` = `https://github.com/BoshkoSmileski/praksa-frontend.git`, branch `main`, `HEAD` at `98783d6` ("Finalize diploma thesis management system"), up to date with `origin/main`.

**3. Working-tree state discovered.** `git status`/`git diff --stat`/`git diff --cached --stat` on both repos, BEFORE touching anything: backend had exactly ONE modified, unstaged, uncommitted file — `CLAUDE.md` (48 insertions, 2 deletions) — this was the entire "FINAL PRE-PUSH AUDIT — DONE (2026-08-31)" entry plus the `CURRENT NEXT STEP`/`SINGLE BEST NEXT TASK` updates written by the immediately-preceding audit task in this same session, never committed. Frontend: `git status` → "nothing to commit, working tree clean" — no changes at all. So, contrary to the prior audit entry's own closing line ("both working trees remain byte-for-byte identical to what is already pushed"), the backend repo's CODE was identical to `origin/main` but its DOCUMENTATION (the audit's own findings, written to the tracked `CLAUDE.md`) was NOT yet committed or pushed — this task's job was to close exactly that gap.

**4. Accidental-artifact / secrets check.** Inspected `.gitignore` on both repos (backend: covers `target/`, `application-local.properties`, `.env`, `uploads/`, `*.log`, IDE folders; frontend: present and unmodified) — no gaps found, no change made. `git status` showed zero untracked files in either repo (no stray logs/temp files/screenshots/dumps to clean up). Confirmed `application-local.properties` (the file holding the real local JWT secret + DB password) is NOT tracked (`git ls-files | grep application-local` returns only the placeholder `application-local.properties.example`). Grepped the entire `CLAUDE.md` diff for `password|secret|api[_-]?key|token` — every match was either a documentation reference to an environment-variable NAME (`JWT_SECRET`, `SPRING_DATASOURCE_PASSWORD`) or prose describing that secrets are properly externalized; zero actual secret VALUES present. No file was found that needed to be excluded or removed.

**5. Final verification actually executed (not assumed).**
- Backend: `./mvnw.cmd -o test` (JAVA_HOME = the bundled JetBrains Runtime JDK 25, `--release 21`, live local Postgres `diploma_system`) → **Tests run: 393, Failures: 1, Errors: 0, Skipped: 0.** The single failure is `DefenseRequestIntegrationTest.doubleBooking_secondApprovalRejected_realStack:137` (`expected: <1> but was: <2>`) — the exact same long-documented, pre-existing, environmental failure (a leftover non-cancelled `Defense` row in room "Shared Hall" in the live local dev Postgres DB from earlier manual/live testing sessions across many prior tasks). This is EXACTLY consistent with the documented baseline — not a new failure, not caused by anything in the current working tree (which had zero code changes pending). Per the task's explicit instruction, this was NOT "fixed" and the defense-request/room-booking code was NOT touched.
- Frontend: `npm run build` (`tsc -b && vite build`) → **EXIT 0**, `✓ 1856 modules transformed`, zero TypeScript errors, zero Vite errors — matches the documented baseline exactly.
- Re-checked `git status` on the backend AFTER running the test suite to confirm the test run left no stray tracked-file changes (Maven's `target/` output is gitignored) — confirmed: still exactly the one `CLAUDE.md` modification, nothing else.

**6. Staging and review.** `git add CLAUDE.md` (backend only — the sole legitimate change; frontend had nothing to stage). `git status` after staging confirmed exactly one file staged. `git diff --cached --stat` / `git diff --cached` reviewed in full before committing: the diff is purely prose additions to `CLAUDE.md` — the FINAL PRE-PUSH AUDIT entry, the `CURRENT NEXT STEP` rewrite, and the `SINGLE BEST NEXT TASK` update note, all already documented as legitimate work in §1 of this entry. No secrets, no debug code, no accidental files, no destructive change, no application logic — confirmed by direct inspection, not assumed.

**7. Commit.** Created ONE clean commit on the backend repo with message `Update CLAUDE.md with final pre-push audit findings` (Co-authored by Claude). This is a single, additive documentation commit — no source file, test, configuration, or dependency was touched. The frontend repo required no commit (nothing changed).

**8. Push.** Pushed the backend commit to `origin/main` via a normal, non-destructive `git push` (no `--force`, no `-f`, no branch deletion, no remote-URL change). No authentication prompt or failure occurred — the push completed against the existing configured remote. The frontend repo needed no push (already fully in sync with `origin/main`, confirmed both before and after this task).

**9. Post-push verification.** `git status` → working tree clean on both repos. `git log -1 --oneline` on the backend confirms the new commit is now `HEAD` and matches what was pushed; the frontend `HEAD` (`98783d6`) is unchanged and already matches `origin/main`. (The exact new backend commit hash is reported in this task's final chat summary to the user, per the project's established convention of not self-referencing a commit's own hash inside the commit it belongs to.)

**10. Safety confirmation.** No destructive git command was run at any point (no `reset`, `restore`, `checkout -- `, `clean`, `stash`, no `push --force`). No secret was staged or committed (verified in §4/§6). No application/business logic, test, DTO, entity, security rule, or configuration file was touched — the ENTIRE diff across both repos for this task is the one `CLAUDE.md` documentation update on the backend. All of the extensive, already-completed project work documented throughout this file (Macedonian localization, defense-request redesign, the 17 official faculty-procedure rules, security hardening, the full test suite, etc.) was already committed and pushed by the prior finalize-and-push task and remains untouched here.

**11. Final project state.** Backend `praksa`: branch `main`, up to date with `origin/main`, working tree clean, the FINAL PRE-PUSH AUDIT findings are now permanently part of the pushed history. Frontend `praksa-frontend`: branch `main`, up to date with `origin/main`, working tree clean, unchanged by this task. **Development is STOPPED.** The project, including this session's own audit trail, is now fully submitted to GitHub and ready to be shared with the professor. No further roadmap item, bug fix, or feature should be started without explicit new direction.

**12. Honesty notes.** ACTUALLY EXECUTED: the full 393-test backend suite (JBR JDK 25, live local Postgres), `npm run build` (EXIT 0, 1856 modules), `git status`/`git diff`/`git diff --cached` inspection on both repos before and after every state-changing step, the actual `git add`/`git commit`/`git push` sequence on the backend, and a post-push `git status`/`git log -1` verification. STATICALLY VERIFIED: `.gitignore` contents, the absence of `application-local.properties` from tracked files, and the absence of secret VALUES in the staged diff (grep-based). NOT executed: a live browser walkthrough or a fresh security/localization/workflow audit — none was needed, since the immediately-preceding "FINAL PRE-PUSH AUDIT" entry in this same file already performed that full 18-phase verification against this exact code state minutes earlier and found zero issues; re-running it would have been redundant per this task's own "verify, don't redevelop" scope.

---

**FINAL PRE-PUSH AUDIT — DONE (2026-08-31). 🏁 Backend suite 393/393 (minus 1 pre-existing/environmental failure); frontend `npm run build` EXIT 0; live HTTP smoke test confirms auth/authorization/CORS/localization all correct. VERDICT: READY TO PUSH. Zero code changes made — this was a pure audit.**

**1. Scope and starting state.** This was requested as a strict, independent "would I be comfortable sending this exact working tree to my professor" audit — explicitly NOT a feature or refactor task, and explicitly forbidding destructive git commands and forbidding push/commit. `git status` on BOTH repos showed **working tree clean, nothing to commit** before this audit began — the previous session's finalize-and-push task had already committed and pushed everything (backend commit `fb2f4d0` "Finalize diploma thesis management system" on `origin/main`; frontend commit `98783d6`, same message, on `origin/main`). So there was no uncommitted work to inventory or preserve; the audit's job was purely to verify the ALREADY-PUSHED state is actually correct, not to trust the prior session's own self-report of it.

**2. Backend test suite (actually executed, not assumed).** `./mvnw.cmd -o test` (JBR JDK 25, live local Postgres `diploma_system`) → **Tests run: 393, Failures: 1, Errors: 0, Skipped: 0.** The single failure is `DefenseRequestIntegrationTest.doubleBooking_secondApprovalRejected_realStack:137` (`expected: <1> but was: <2>`) — the exact same pre-existing, environmental failure documented in every prior handoff entry in this file (a leftover non-cancelled `Defense` row in room "Shared Hall" in the live local dev DB from earlier manual/live testing sessions, outside any test's own transaction). Confirmed this is NOT a new regression: the working tree was already clean/committed before this audit touched anything, so no code change could have caused it. Per the task's explicit instruction, it was **not** "fixed" by modifying defense-request/room-booking logic — only re-confirmed and documented, exactly as every prior entry that hit it has done. Additionally ran the two dedicated PDF-generation test classes in isolation (`DefenseRecordPdfServiceTest`, `DefenseRecordPdfAccessTest`) → **8/8 passing**, confirming the bundled Noto Sans Cyrillic font still loads and renders a real, non-trivial PDF.

**3. Frontend build (actually executed).** `npm run build` (`tsc -b && vite build`) → **EXIT 0**, `✓ 1856 modules transformed`, zero TypeScript errors, zero Vite errors.

**4. Frontend Macedonian localization — independently re-audited, not trusted from the prior entry.** Ran fresh `grep` sweeps across all of `praksa-frontend/src` (not a re-read of the prior session's own claims) for: JSX text nodes matching `>[A-Z][a-z]+[a-zA-Z ]{2,}<` — **zero matches**; string literals matching `'[A-Z][a-z]+ …'`/`"[A-Z][a-z]+ …"` — only 2 matches, both inside `/* code comments */` or `/** JSDoc */` (`CommitteeSection.tsx` and `RejectValidationModal.tsx`), explicitly out of scope per the task's own instructions (comments are developer-only, never rendered); `placeholder=`/`title=`/`aria-label=` props starting with an English capitalized word — **zero matches**; `toast.error(`/`toast.success(` starting with an English capital letter — **zero matches**; single-word English button labels (`>Cancel<`, `>Save<`, `>Submit<`, etc.) — **zero matches**; raw enum renders (`{thesis.status}`, `{user.role}`, `{notification.type}` printed directly instead of through a label map) — **zero matches**, every status render goes through `<StatusBadge status={...} />`; leftover `en-US`/`toLocaleDateString`/`Intl.DateTimeFormat` — **zero matches**, `utils/date.ts` is fully custom `dd.MM.yyyy` formatting. Confirms the full localization pass from the prior entry is intact and was not silently reverted or partially lost.

**5. Backend user-visible language — independently re-audited.** Re-ran the `throw new (BadRequestException|UnauthorizedException|ResourceNotFoundException)(...)` sweep across `src/main/java` — every literal string is Macedonian; the only English fragments remaining inside these calls are either (a) enum-name string concatenations for developer/debug context (`thesis.getStatus()`, `Role required` — explicitly correct per the task's own "do not translate enum identifiers" instruction), or (b) Swagger `@Operation`/`@ApiResponse` `description=`/`summary=` annotations (explicitly out of scope — developer API documentation, never seen by an end user). Verified two specific claims from the prior localization entry against current source rather than trusting the write-up: (a) `GlobalExceptionHandler.handleValidation` — confirmed it still builds a per-field `errors` map from `MethodArgumentNotValidException` but discards it, returning only the generic `"Валидацијата не успеа."` — so the English `@NotBlank`/`@Size`/`@Min`/`@Max` DTO annotation messages remain genuinely dead code, never reaching a client; (b) `UserDetailsServiceImpl`'s `"User not found: " + email"` (`UsernameNotFoundException`) — confirmed via `JwtAuthFilter.doFilterInternal` that this is caught **inside the filter itself** (try/catch around `loadUserByUsername`) before it can ever reach `GlobalExceptionHandler` or a client response; in the default `auth.external-enabled=false` mode an unknown-user token simply logs at `debug` and the request proceeds unauthenticated (Spring Security's own 401/403 then applies). Both are confirmed non-issues, not new findings — matches the prior session's own documented conclusion. `NotificationType.java` (all 30 subject/body pairs) and `ApplicationPdfService.java`/`DefenseRecordPdfService.java` (PDF template labels) re-swept — zero English user-facing fragments remain (only the technical `"Noto Sans"` font-family name and internal `RuntimeException` messages that never reach a client).

**6. Authentication / authorization — verified via source AND live HTTP, not just re-read.** `SecurityConfig.java` re-read in full: CSRF disabled (correct for a stateless Bearer-token API), sessions stateless, `permitAll` limited to exactly `/api/auth/**`, Swagger, and `/actuator/health`, everything else `.anyRequest().authenticated()`; CORS origins come from an explicit comma-separated allow-list (`app.cors.allowed-origins`, default `http://localhost:5173`) — **never** a wildcard — `allowCredentials(false)` (correct: auth is a Bearer header, not a cookie), `Authorization` explicitly whitelisted, `Content-Disposition` exposed for PDF downloads. Confirmed live over real HTTP against a freshly-started backend (`curl`, not the PowerShell console, per this project's established practice for verifying raw UTF-8/Cyrillic and header behavior):
   - Wrong password → `403 {"message":"Погрешна е-пошта или лозинка."}` (correct Cyrillic, no user enumeration).
   - No token on a protected endpoint → `403` with no body.
   - `POST /api/auth/register` with `role:"STUDENT_SERVICE"` in the body → account created with **`role:"STUDENT"` in the response** — live-confirms BUG-2's fix is still in effect (the requested privileged role is silently coerced, never honored).
   - **Cross-student IDOR**: the newly-registered second student's token, used against the FIRST student's thesis id → `403 {"message":"Немате пристап до оваа дипломска работа."}` — zero data leaked.
   - **Unrelated-mentor IDOR**: `mentor2@test.com` (not the assigned mentor on that thesis) → same `403`, same message.
   - **Role-boundary check**: the owning student attempting the STUDENT_SERVICE-only `PATCH /{id}/eligibility` → `403 {"message":"Оваа акција бара улога: STUDENT_SERVICE"}` (the enum role name is intentionally left untranslated — a technical identifier per policy).
   - **CORS preflight** from `http://localhost:5173` (the configured allowed origin) → `200` with `Access-Control-Allow-Origin: http://localhost:5173` (the literal allowed origin echoed back, never `*`) and no `Access-Control-Allow-Credentials` header (confirming `allowCredentials=false`); the identical preflight from `http://evil.example.com` → `403` with **no** CORS headers granting access at all.
   Also re-read (not just grepped) `DefenseResultServiceImpl.requireVotingCommitteeSeat` (external non-voting members rejected before any mutation, confirmed at lines 290-297) and `ThesisServiceImpl.findByRegistrationNumber` (still gated by `thesisReadAccessPolicy.requireReadAccess`, lines 681-694) — both intact. `DefenseServiceImpl.decideDefenseRequest` and `DeadlineExtensionServiceImpl.decideDeadlineExtensionRequest` both still hard-require `Role.STUDENT_SERVICE` (never the owning student) — a student can never approve their own defense-term or deadline-extension request. No secrets found in either repo (repeated the pattern search from the prior finalize task; `application.properties` remains 100% env-var-backed with no inline defaults for `jwt.secret`/DB password; `application-local.properties` remains correctly git-ignored and untracked).

**7. Thesis workflow + official faculty procedure rules — verified against current source, not re-read from history.** Directly inspected (not assumed): `ThesisStatus.java` (21 statuses, unchanged, `DEFENSE_FAILED` sits correctly between `DEFENSE_SCHEDULED` and `ARCHIVED`); `ThesisServiceImpl.isActiveStatus` (excludes exactly `ARCHIVED`, `ELIGIBILITY_REJECTED`, `DEFENSE_FAILED` — confirmed a `DEFENSE_FAILED` thesis does not block reapplication); mentor-capacity constant (`activeMentorCount >= 15`, `ThesisServiceImpl.java:161`); grade constants (`MIN_DEFENSE_GRADE=5`, `MAX_DEFENSE_GRADE=10`, `FAILING_GRADE=5`, `DefenseResultServiceImpl.java`); the grade-5 branch (`recordDefenseFailure`) confirmed to set NO archive metadata and the grade-6-10 branch (`recordSuccessfulArchive`) confirmed to set all four archive fields — the two are mutually exclusive by construction; the 45-day mentor-review constant (`MENTOR_REVIEW_DEADLINE_DAYS=45`, `ScheduledTasksService.java`); the 14-day post-application wait (`MIN_DAYS_SINCE_APPLICATION=14`) and the 5-15 day scheduling window (`MIN_PROPOSAL_DAYS=5`, `MAX_PROPOSAL_DAYS=15`), both in `DefenseServiceImpl.java`; the 3-or-4-member committee rule with the exact-external-count check (`memberCount==3→externalCount must be 0`, `memberCount==4→externalCount must be exactly 1`, `CommitteeServiceImpl.java:305-315`); room double-booking re-validated **at approval time** against the live DB (`requireRoomAvailable`, called inside `decideDefenseRequest`'s try/catch, not just at proposal time); the 15-day deadline-extension cap (`requestedDays > 15` rejected, `DeadlineExtensionServiceImpl.java:109-113`); the archive-notes editor (`ThesisServiceImpl.updateArchiveNotes`, gated on `Role.ARCHIVE` + `ThesisStatus.ARCHIVED`, confirmed it calls neither `transitionStatus` nor any notification). Every one of the 17 official-procedure rules in the task's checklist was traced to an exact line of currently-committed source, not accepted on the strength of the prior write-up alone. No discrepancy found.

**8. API contract consistency — spot-checked directly, not assumed.** Compared `defenseApi.ts` against `DefenseController.java`/`DefenseResultController.java` line-by-line (all 8 endpoints: create/decide/list defense requests, get-active, get-all, cancel, record-result, get-result, download-record-pdf) — URLs, HTTP methods, and body shapes match exactly. Compared `thesisApi.ts` against `ThesisController.java`'s Javadoc-documented endpoints (17 functions, including the newer `archive-notes`, `defense-eligibility`, `deadline-extension-request/decision/requests` trio) — all match. Verified the two previously-flagged wire-key-sensitive boolean fields once more: `CommitteeMemberResponse.externalNonVoting` (no `is` prefix on the field so Lombok's `isExternalNonVoting()` getter serializes as the unambiguous `externalNonVoting` JSON key — matches the frontend `CommitteeMember.externalNonVoting: boolean` exactly) and confirmed `Notification.sent`/`Notification.read` come back correctly on the wire during the live smoke test (`"read":true,"sent":false"` observed directly in a real `GET /api/notifications/my` response body). Confirmed `Thesis.lastVersionSubmittedAt` is — by original design, not by omission — absent from both `ThesisResponse.java` and the frontend `Thesis` type: it is a backend-only field that drives the 45-day scheduled reminder job and was never meant to cross the API boundary (verified `grep` finds zero references to it anywhere under `praksa-frontend/src`). No mismatch found anywhere checked.

**9. Database / entity sanity — read directly, not summarized from memory.** Re-read `Thesis.java`, `CommitteeMember.java`, `DefenseRequest.java`, and `DeadlineExtensionRequest.java` in full: no accidental duplicate fields; nullable/non-nullable settings are all deliberate (every "not yet reached this stage" timestamp is nullable; ids, FKs to the owning thesis, and status columns are `nullable=false`); all enums persisted via `@Enumerated(EnumType.STRING)` (safe against reordering); the unique constraint on `committee_members(thesis_id, professor_id)` and on `theses.archive_registration_number` both still present; no dangerous `CascadeType.ALL`/`orphanRemoval` anywhere in any `@ManyToOne` (none of these entities owns a cascading child collection); `CommitteeMember.isExternalNonVoting` still carries `@ColumnDefault("false")` so `ddl-auto=update` back-fills every pre-existing row safely as a normal voting seat. No schema issue found; no migration framework introduced (still bare `ddl-auto=update`, as documented).

**10. Notifications — re-verified for duplicates.** Re-read `ScheduledTasksService.sendDefenseReminders` and `autoAdvanceStaleCommitteeReviews`: confirmed **no** explicit `notify(thesis.getMentor(), …)` call exists in either — the mentor is reached exactly once, via their `MENTOR_MEMBER` committee seat inside the `for (CommitteeMember m : …)` loop, matching the documented duplicate-notification fix. All defense-request/deadline-extension/committee/archive notification call sites inspected in Phase 5/7 above route through the same `notificationService.notify(...)`/`notifyRole(...)` machinery with no new ad-hoc email code introduced anywhere.

**11. Issues found.** **None requiring a code change.** One pre-existing, previously-documented, low-severity cosmetic item was re-confirmed present and intentionally left as-is (per this task's explicit "do not touch completed functionality without a real reason" instruction and matching the prior "Final Readiness Audit" entry's own conclusion): `FileStorageService.java:81` still uses `System.err.println(...)` instead of a logger for a failed-orphan-file-cleanup warning — server-console-only, never reaches a user, not a security issue, not a regression. No other TODO/FIXME, `console.log`, secret, or accidental artifact was found in either repository (repeated searches from Phase 15, both clean).

**12. Files changed during this audit.** **None.** This was a read-only audit plus a live HTTP smoke test against a temporarily-started backend process (cleanly stopped afterward, port 8080 confirmed freed) and one throwaway test account (`audit-temp-student@test.com`) created in the **local development Postgres database only** to empirically prove cross-student IDOR protection — this is local runtime data, not a repository file, and has no effect on the git working tree or the pushed commits. `git status` on both repos before and after this audit: **identical — "nothing to commit, working tree clean."** This entry itself is the only change, added to `CLAUDE.md` as instructed.

**13. Pre-existing known issue (unchanged by this audit).** `DefenseRequestIntegrationTest.doubleBooking_secondApprovalRejected_realStack` fails with `expected: <1> but was: <2>` due to a leftover non-cancelled `Defense` row in room "Shared Hall" in the live local dev Postgres database, left over from earlier manual/live HTTP testing sessions across multiple prior tasks (documented in at least six prior entries in this file). This is **not** caused by this audit — the working tree was already clean and fully committed before this audit ran a single command, and this audit's diff is empty. Not "fixed" here, per the explicit instruction not to modify defense/room-booking logic just to chase a green build.

**14. Git status (final).** Backend (`praksa`): branch `main`, `HEAD` at `fb2f4d0`, up to date with `origin/main`, working tree clean. Frontend (`praksa-frontend`): branch `main`, `HEAD` at `98783d6`, up to date with `origin/main`, working tree clean. **Nothing was pushed, committed, reset, restored, or stashed by this audit** — both repositories were already fully synced with GitHub before this task began, and remain byte-for-byte identical after it.

**15. Final recommendation.** **READY TO PUSH.** There is, in fact, nothing new to push — the audited state IS the state already on `origin/main` for both repositories, and this audit found no reason to change it. The single test failure is the long-documented, DB-state-only environmental issue, unrelated to any code. Frontend build is clean. Every phase of the requested audit (localization, backend message language, authentication/authorization — both statically and live over real HTTP — thesis workflow, the 17 official-procedure rules, API contracts, entity/schema sanity, notifications, PDFs, frontend routing, secrets/debug artifacts, and git hygiene) was independently re-verified against current source and, where practical, live HTTP behavior — not accepted on the strength of prior handoff entries alone. The project, as currently pushed, is safe to submit to the professor.

**16. Honesty notes.** ACTUALLY EXECUTED: the full 393-test backend suite, the 8-test focused PDF suite, `npm run build` (EXIT 0, 1856 modules), and a 12-step live HTTP smoke test against a freshly-started real backend process (login failure, unauthenticated access, valid login, own-data fetch, notifications fetch, privilege-escalation-attempt registration, cross-student IDOR, unrelated-mentor IDOR, role-boundary rejection, and CORS preflight from both an allowed and a disallowed origin), followed by clean process shutdown (port 8080 confirmed freed). STATICALLY VERIFIED (direct `Read`/`Grep` against current source, not inferred from documentation): `SecurityConfig.java`, `GlobalExceptionHandler.java`, `JwtAuthFilter.java`, `ThesisServiceImpl.java`, `DefenseServiceImpl.java`, `DefenseResultServiceImpl.java`, `CommitteeServiceImpl.java`, `DeadlineExtensionServiceImpl.java`, `ScheduledTasksService.java`, `Thesis.java`, `CommitteeMember.java`, `DefenseRequest.java`, `DeadlineExtensionRequest.java`, `ThesisStatus.java`, `NotificationType.java`, `ApplicationPdfService.java`, `DefenseRecordPdfService.java`, `AppRouter.tsx`, `Sidebar.tsx`, `defenseApi.ts`, `thesisApi.ts`, `CommitteeMemberResponse.java`, plus full-repo `grep` sweeps for English UI strings, secrets, `console.log`/`System.out`/`System.err`, and TODO/FIXME. NOT executed: a live browser/DOM walkthrough (no browser automation tool available in this environment — substituted with the live `curl`-based HTTP smoke test, the same evidentiary standard used by every prior entry in this file under the same constraint); a native-speaker linguistic review of the Macedonian text (unchanged from the prior localization pass, not re-translated).

--- Per explicit user request, the entire user-facing application (frontend UI + all user-facing backend messages/notifications/generated PDFs) has been translated into Macedonian (Cyrillic). This was a pure localization pass — no workflow, API contract, DTO shape, authorization rule, or business logic was changed. Backend `git diff` touches only literal string values inside existing `throw new …Exception("…")` calls, the `NotificationType` enum's `(subject, defaultBody)` string arguments, three `GlobalExceptionHandler` messages, `NotificationServiceImpl`'s email-body builder strings, and `ApplicationPdfService`'s HTML template (plus a Cyrillic-font fix — see the entry). Frontend `git diff` touches only JSX text nodes, string literal props (`title=`, `placeholder=`, `label`, toast messages), the `StatusBadge` label map, the `NotificationsPage` type-label map, and `utils/date.ts`'s date-formatting output — plus two new small shared utility files (`utils/roleLabels.ts`). Full backend suite: 393/393 passing except the SAME pre-existing/environmental `DefenseRequestIntegrationTest.doubleBooking_secondApprovalRejected_realStack` failure documented in every prior entry (confirmed untouched by this task). Frontend `npm run build`: EXIT 0, 1856 modules. Live backend smoke test (real HTTP, raw `curl`, not the PowerShell console) confirmed Cyrillic renders correctly end-to-end (wrong-password login, duplicate-email register). **STOP — no further faculty-procedure items and no further localization items remain on the user's list.** Everything below this entry (including the previously-completed Defense Deadline Extension Request entry) is prior history and remains accurate for the parts of the app it describes.

---

**Full Macedonian (MK) Localization — DONE (2026-08-28). 🏁 Backend suite 393/393 (minus 1 pre-existing/environmental failure); frontend `npm run build` EXIT 0 (1856 modules); live HTTP smoke test confirms correct Cyrillic end-to-end.**

**1. Overall verdict.** COMPLETE. This was a full repository-wide localization audit and translation, not a partial pass over the obviously-visible pages. Every `.tsx`/`.ts` file under `praksa-frontend/src` was read and audited; every backend file that throws a user-visible exception message, sends a notification, or renders a user-downloaded document was read and audited. The application now presents entirely in Macedonian Cyrillic to every role (Student, Mentor, Student Service, Committee, Archive) across every page, modal, toast, status badge, notification, and generated PDF. No workflow/business-rule/API-contract/authorization change was made anywhere — this task was scoped strictly to translating existing user-facing strings in place.

**2. Terminology decisions (glossary used consistently across the whole app).** Студент (Student), Ментор (Mentor), Студентска служба (Student Service), Комисија / Член на комисија (Committee / Committee member as a role tag), Надворешен член (без право на глас) (External non-voting member — kept exactly as it already existed in the codebase from the prior 4-member-committee feature), Дипломска работа (Thesis), Пријава (на дипломска работа) (Application), Одбрана (Defense), Барање за одбрана / термин за одбрана (Defense request / defense term), Просторија (Room), Закажан(а) (Scheduled), Во тек / Чека (Pending, chosen per context — e.g. "Чека одобрување" for an awaiting-approval badge, "Во изработка" for IN_PROGRESS), Одобрено/Одбиено (Approved/Rejected), Причина (Reason), Известување(а) (Notification(s)), Непрочитано/Прочитано (Unread/Read), Архива / Забелешки на архивата (Archive / Archive notes), Верзија (Version), Коментар (Comment), Оценка (Grade), Одбраната не е положена (Defense failed — reused verbatim from the pre-existing `DEFENSE_FAILED` status label already in Macedonian from an earlier task), Статус (Status), Историја (History), Контролна табла (Dashboard), Најава/Одјава (Login/Logout), Регистрација (Register), Лозинка (Password), Е-пошта (Email), Зачувај/Откажи/Избриши/Уреди/Поднеси/Преземи/Прикачи/Пребарај/Прегледај/Детали/Назад/Следно/Претходно/Затвори/Потврди/Одбиј/Одобри/Побарај измени/Креирај/Додади/Отстрани — the full requested action-verb glossary, applied consistently. Sentences were translated for natural meaning, not word-by-word (e.g. confirmation dialogs read as full idiomatic Macedonian questions, not literal substitutions). Brand name "DiplomaSystem" → "Дипломски систем" everywhere it appeared (sidebar logo, auth layout header/footer, email subject prefix).

**3. Frontend — scope covered.** All 51 `.ts`/`.tsx` files under `praksa-frontend/src` were read. Translated: `components/ui/StatusBadge.tsx` (all 21 `ThesisStatus` labels), `components/ui/Modal.tsx` (`aria-label`), `components/layout/Header.tsx` + `Sidebar.tsx` (nav labels, logo/tagline, logout button, unread-badge tooltip, role tag), `layouts/AuthLayout.tsx` (brand name/tagline/footer), `api/client.ts` (all 4 interceptor toast messages), `utils/date.ts` (rewritten to a fixed `dd.MM.yyyy` / `dd.MM.yyyy, HH:mm` Macedonian-style format, replacing the old `en-US` `toLocaleDateString`), `pages/auth/LoginPage.tsx` + `RegisterPage.tsx` (full forms, labels, placeholders, hints, toasts), `pages/DashboardPage.tsx` (quick-action cards + welcome header), `pages/ThesesListPage.tsx`, `CreateThesisPage.tsx`, `CommitteePage.tsx`, `DefensesPage.tsx`, `ArchivePage.tsx`, `ManageCreditsPage.tsx`, `NotificationsPage.tsx` (including the full `typeLabels` map for all 21 `NotificationType` values), and the large "kitchen sink" `ThesisDetailPage.tsx` (archive-record card, defense-failed card, header metadata, application-PDF block, comments, the entire "Available Actions" decision-row system, the mentor-tri-decision row, the workflow timeline, and the access-denied panel). All 16 feature-folder components were translated: `mentor-picker/MentorPickerModal.tsx`, `revise-proposal/ReviseProposalModal.tsx`, `validation/RejectValidationModal.tsx`, `mentor-decision/MentorDecisionModal.tsx`, `versions/{VersionsSection,VersionUploader,CommentList}.tsx`, `credits/EditCreditsModal.tsx`, `committee/{ProposeCommitteeModal,CommitteeSection}.tsx`, `defense/{ProposeDefenseModal,RejectDefenseRequestModal,RecordGradeModal,DefenseSection}.tsx`, `deadline-extension/{DeadlineExtensionModal,RejectDeadlineExtensionModal,DeadlineExtensionSection}.tsx`. Every `toast.success(...)`/`toast.error(...)` call site in the repo (verified by a final `grep` sweep — see §7) is now Macedonian, including native `window.confirm(...)` dialog text (`DefenseSection.handleApprove`/`handleCancel`, `DeadlineExtensionSection.handleApprove`) which the task's example ("Are you sure you want to reject this request?") specifically called out.

**4. New small shared utility (safe, in-scope refactor).** `praksa-frontend/src/utils/roleLabels.ts` — a `Record<Role, string>` map (`STUDENT→Студент`, `MENTOR→Ментор`, `STUDENT_SERVICE→Студентска служба`, `COMMITTEE→Член на комисија`, `ARCHIVE→Архива`) + a `roleLabel(role)` helper. Used in `Header.tsx` (the role tag under the user's name), `DashboardPage.tsx` ("Најавени сте како …"), and `CommentList.tsx` (a version comment's author-role tag, which previously rendered the raw English enum string `c.authorRole` directly — a genuine pre-existing localization gap, fixed here). This was the one place a shared constant was worth extracting rather than repeating five inline ternaries — matches the task's explicit allowance ("If a translation requires changing a reusable frontend constant/component, do that safely").

**5. Status translations.** All 21 `ThesisStatus` values in `StatusBadge.tsx` now have Macedonian labels (enum identifiers themselves were never renamed — only the display label object). `DEFENSE_FAILED`'s label ("Одбраната не е положена") was already Macedonian from an earlier task and was left byte-for-byte unchanged for consistency. `PENDING_ELIGIBILITY_CHECK`→"Проверка на условите", `TOPIC_SELECTION`→"Избор на тема", `MENTOR_REQUESTED_CHANGES`→"Потребни се измени", `APPLICATION_SUBMITTED`→"Пријавата е поднесена" (a **deliberate, documented choice**: this status's English label was already misleading before this task — per §3 of this document, `APPLICATION_SUBMITTED` actually means "mentor accepted, application NOT yet submitted" — the Macedonian translation preserves the SAME pre-existing naming quirk faithfully rather than silently fixing it, since fixing a naming inaccuracy is a scope decision outside a pure translation task), `PENDING_ARCHIVE_VALIDATION`/`PENDING_SERVICE_VALIDATION`→"Чека валидација од Архива"/"...Студентска служба", `IN_PROGRESS`→"Во изработка", `COMMITTEE_REVIEW`/`COMMITTEE_ACCEPTED`→"Разгледување од комисија"/"Прифатено од комисија", `PENDING_DEFENSE_CHECK`→"Проверка на услови за одбрана", `PENDING_DEFENSE_SCHEDULING`→"Чека закажување одбрана", `ARCHIVED`→"Архивирано". No status was invented and none was renamed at the enum level — verified against the real `ThesisStatus.java` enum (21 values) before writing the label map, per the task's explicit "do not invent statuses that don't exist" instruction.

**6. Notification translations.** All 30 `NotificationType` enum entries in `model/enums/NotificationType.java` had both their `subject` and `defaultBody` string arguments translated to Macedonian (the enum constant NAMES were never touched). This is the single source of truth used both for the async email body (via `NotificationServiceImpl.buildSubject`/`buildBody`, whose own surrounding English scaffold text — "Dear …", "Thesis: …", "Current status: …", "This is an automated message…" — was also translated to "Почитуван/а …", "Дипломска работа: …", "Тековен статус: …", "Ова е автоматска порака…") and for the frontend's independent `typeLabels` map in `NotificationsPage.tsx` (which mirrors the same 21 in-app-relevant types with short list-view labels, kept in sync by hand since the two serve different rendering contexts — the email body needs a full sentence, the notification list needs a short noun phrase). The four custom-message notification call sites that build a runtime string on top of the type's default body (`MENTOR_REQUESTED_CHANGES` + mentor feedback, `APPLICATION_REJECTED_BY_ARCHIVE`/`_BY_SERVICE` + rejection reason, `DEFENSE_SCHEDULED`/`DEFENSE_CANCELLED` + room/time details, `THESIS_GRADED`/`THESIS_ARCHIVED` + grade/registration-number, `DEFENSE_FAILED_CAN_REAPPLY`'s explanatory paragraph) were all translated in `ThesisServiceImpl`, `DefenseServiceImpl`, and `DefenseResultServiceImpl`. The two `DeadlineExtensionServiceImpl` custom messages (`MK_APPROVED`/`MK_REJECTED` constants) were **already** Macedonian from the prior Deadline Extension Request task — left untouched, and their `"\n\nReason: "` / `"\n\nNew defense deadline: "` suffixes were translated to `"\n\nПричина: "` / `"\n\nНов рок за одбрана: "` to match.

**7. Backend user-facing messages translated.** A repository-wide `grep` for every `throw new BadRequestException(...)`, `throw new UnauthorizedException(...)`, and `throw new ResourceNotFoundException(...)` across `src/main/java` found ~65 static and dynamic message call sites across 12 files — `ThesisServiceImpl`, `CommitteeServiceImpl`, `DefenseServiceImpl`, `DefenseResultServiceImpl`, `ThesisVersionServiceImpl`, `NotificationServiceImpl`, `UserServiceImpl`, `AuthServiceImpl`, `DeadlineExtensionServiceImpl`, `FileStorageService`, `SecurityUtils`, `ThesisReadAccessPolicy` — every one was translated to natural Macedonian, including the dynamic ones that interpolate a value (e.g. `"Дипломската работа не е пронајдена: " + id`, `"Оваа акција бара улога: " + required`). Enum identifiers embedded inside a dynamic message for developer/debug context (e.g. a raw `ThesisStatus`/`Role` name inside "Тековен статус: " + thesis.getStatus()`) were deliberately left as the raw English enum token, per the task's explicit "do not translate enum identifiers" instruction — only the surrounding Macedonian sentence changed. `GlobalExceptionHandler`'s three catch-all messages were translated: bean-validation failure → "Валидацијата не успеа.", Spring Security authentication failure → "Погрешна е-пошта или лозинка.", the generic 500 handler → "Настана неочекувана грешка." The already-Macedonian `ROOM_CONFLICT_REASON` constant in `DefenseServiceImpl` ("Просторијата е зафатена во тој термин.") was found already correct from a prior task and left untouched.

**8. `ApplicationPdfService` — translated AND a real pre-existing bug fixed.** The thesis-application-form PDF (downloaded by students/mentors/archive/service as the official application document) was previously rendered entirely in English using the base-14 Helvetica PDF font. Investigated the sibling `DefenseRecordPdfService` (the Macedonian "записник за одбрана" PDF, from an earlier task) and found it already solves the "Helvetica has no Cyrillic glyphs" problem by bundling `Noto Sans` (`src/main/resources/fonts/NotoSans-{Regular,Bold}.ttf`, SIL Open Font License) via `PdfRendererBuilder.useFont(...)`. Replicated the exact same font-registration pattern in `ApplicationPdfService` and translated every static label ("ПРИЈАВА НА ДИПЛОМСКА РАБОТА", "Студент"/"Ментор"/"Предложена тема"/"Поднесување" section headers, "Име и презиме"/"Индекс"/"Е-пошта"/"Датум на поднесување"/"Број на пријава" field labels, "Студент"/"Ментор"/"Студентска служба" signature-line labels, the auto-generated-document footer). Also switched the date format from `MMMM d, yyyy` (English month names, e.g. "August 28, 2026") to the same `dd.MM.yyyy` format used everywhere else in the translated frontend, for consistency. **Why this matters beyond translation:** without the font fix, the newly-Macedonian static labels (and any Cyrillic already present in a thesis title/student name, which was ALREADY silently broken before this task since users type in Macedonian) would have rendered as blank boxes — this is a real, previously-latent rendering bug that the localization task surfaced and fixed using the project's own established pattern, not a new framework or design.

**9. Localization architecture.** No i18n library (react-intl, i18next, etc.) was introduced — the project has never used one, and introducing one for a single-locale (Macedonian-only) application would be unnecessary architecture per the task's explicit "do not redesign the application" instruction. All strings are inline Macedonian literals at their point of use, exactly matching the codebase's pre-existing pattern of inline English literals. The two exceptions — `StatusBadge`'s `statusConfig` label map and `NotificationsPage`'s `typeLabels` map — already existed as centralized lookup objects before this task (they are the correct place to keep enum→label mappings) and were simply filled in with Macedonian values instead of English ones. `roleLabels.ts` (§4) is the one new centralized mapping, added because the same five-way role-label ternary was about to be duplicated a third time.

**10. English-string audit result (final sweep, executed after all edits).** Ran targeted `grep` passes across the entire `praksa-frontend/src` tree for: JSX text nodes matching `>[A-Z][a-z]+[a-zA-Z ]{2,}<`, string literals matching `'[A-Z][a-z]+ [A-Za-z ]*'`/`"[A-Z][a-z]+ [A-Za-z ]*"`, every `toast.success(`/`toast.error(` call site, and the common word list from the task brief (Login, Dashboard, Student, Mentor, Committee, Defense, Thesis, Application, Archive, Notification, Status, Pending, Approved, Rejected, Save, Cancel, Delete, Edit, Submit, Upload, Download, Search, Details, View, Close, Confirm, Approve, Reject, Reason, Date, Time, Room, Email, Password, Version, Comment, Grade, History, Request, Review, Read, Unread, Mark as read, etc.). Every genuine match was either (a) already translated by this task, (b) a TypeScript type/interface name, import statement, or React/lucide-react component name (never user-facing), or (c) a code comment (explicitly out of scope — comments are not displayed to users). No mixed-language user-facing string remains anywhere in the audited surface.

**11. Backend tests.** Running the full existing suite immediately surfaced (correctly) that 5 pre-existing unit/integration tests asserted the OLD literal English exception-message text as their expected value — this is the expected, necessary consequence of translating those exact strings, not a regression. Updated the assertions in `AuthIntegrationTest` (1: duplicate-email `jsonPath("$.message")`), `AuthServiceRegistrationTest` (3: duplicate-email/duplicate-index/missing-index `assertEquals`), and `MentorCapacityLimitTest` (1: 15-theses-cap `assertEquals`) to the new Macedonian text — each is a literal 1:1 swap of the expected string, no test logic, setup, or assertion structure changed. A repository-wide `grep` for every OTHER message string this task translated (~50 additional distinct messages, covering `You do not/are not/own`, `Only the assigned mentor`, `This action requires`, `A rejection/reason/comment is required`, `Room is required`, `Invalid thesis status`, `Thesis/Mentor/Committee member/Professor not found`, `Cannot submit/record`, `A result has already`, `Not authenticated`, `You cannot modify`, `The thesis mentor`, `You must propose`, `A 3-/4-member committee`, `The external member/non-voting`, `Defense grade must be`, `File cannot be/Only PDF/File must have`, `Credits can only`, `Only Student Service`, `User not found`, `Requires role`, `Not permitted for role`, `Listing users`) found **zero** other test asserting exact literal message text for anything else this task changed — confirmed by pattern-matching every `assertEquals("...", ex.getMessage())` and `jsonPath("$.message").value("...")` call site in `src/test`. `./mvnw.cmd -o test` (JBR JDK 25, live local Postgres `diploma_system`) → **393 tests, 1 failure, 0 errors** — the single failure is `DefenseRequestIntegrationTest.doubleBooking_secondApprovalRejected_realStack:137` (`expected: <1> but was: <2>`), the SAME pre-existing, unrelated, environmental failure documented in every prior handoff entry in this file (a leftover non-cancelled `Defense` row in room "Shared Hall" in the live local dev DB from earlier manual/live testing sessions, outside any test's own transaction) — this task's diff touches zero files under `DefenseServiceImpl`'s room-booking logic, `DefenseRepository`, or `DefenseController`, so it is not caused by this task; per the explicit task instruction, it was documented here and NOT "fixed" by rewriting unrelated defense logic.

**12. Frontend build.** `npm run build` (`tsc -b && vite build`) → **EXIT 0**, `✓ 1856 modules transformed` (was 1855 before this task; +1 for the new `utils/roleLabels.ts` file), zero TypeScript errors. The type-check passing confirms every JSX/string edit preserved correct TypeScript syntax across all ~45 edited files.

**13. Browser/live smoke test.** A live browser walkthrough was NOT performed (no browser automation tool available in this environment). Instead, the backend was started for real (`./mvnw.cmd -o spring-boot:run`, JBR JDK 25, live local Postgres, real `application-local.properties` secrets) and exercised over real HTTP with raw `curl` (not the PowerShell console, which has previously been documented in this file as mangling UTF-8/Cyrillic display — curl's raw byte output is the reliable verification method used throughout this project's history): (a) `POST /api/auth/login` with a wrong password → `403 {"message":"Погрешна е-пошта или лозинка."}`, correct Cyrillic, no mangling; (b) `GET /api/theses/my` with no token → `403`, empty body (Spring Security's own filter-chain rejection, unaffected by this task); (c) `POST /api/auth/register` with a duplicate email → `400 {"message":"Оваа е-пошта веќе се користи."}`, correct Cyrillic; (d) a full valid login (`student@test.com` / `password123`) → `200` with a real JWT, followed by an authenticated `GET /api/theses/my` → `200` with the student's real thesis data, proving the translated exception paths did not break the happy path. The backend process was then stopped cleanly (verified port 8080 freed). This is the same evidentiary standard (`curl`-based HTTP verification in lieu of a live browser) used by several prior entries in this file (e.g. the defense-request-redesign entry) when no browser tool was available.

**14. Explicitly checked and confirmed to need NO change (dead/non-user-facing text).** `dto/ApiResponse.java`'s generic success-envelope defaults (`"OK"` from `ApiResponse.ok(data)`, `"Login successful"` from `AuthController`'s `ok(message, data)` call) were investigated and confirmed **never read anywhere in the frontend** — a `grep` across every file in `praksa-frontend/src/api/*.ts` for `.message`/`response.message`/`data.message` found zero matches; every frontend page shows its OWN custom `toast.success(...)` string (already translated, §3) rather than the backend's generic envelope message, and the axios interceptor only reads `.message` on the ERROR path. Left as-is — translating a string with zero display surface would be dead work and risks an inconsistent partial precedent for other unread envelope defaults. Bean-validation `@NotBlank`/`@Size`/`@Min`/`@Max` field-level `message = "..."` annotations across every DTO in `dto/*` (~40 occurrences) were investigated and confirmed **already dead** by a pre-existing, documented defect: `GlobalExceptionHandler.handleValidation` builds a per-field error map from these messages but then discards it, returning the flat generic `"Validation failed"` (now translated to "Валидацијата не успеа.", §7) instead — this was flagged as a known bug in the "Final Readiness Audit" entry (§4) long before this task and was explicitly marked "not fixed — optional." Translating ~40 messages that can never reach a user was skipped as out-of-scope busywork; if that pre-existing bug is ever fixed (surfacing field-level messages to the client), those annotation strings should be translated at that time — noted here for a future session rather than silently left as a trap.

**15. Known limitations / intentionally untranslated technical text.** Per the task's own explicit exceptions: (a) Swagger/OpenAPI `@Operation`/`@ApiResponse` annotations in every controller — left in English as API technical documentation (translating these was explicitly called out as something that "would damage the technical contract" and is aimed at developers, not end users, consistent with Swagger UI being reachable only via `/swagger-ui.html`, not part of the student/staff-facing application). (b) All enum constant NAMES (`ThesisStatus.*`, `NotificationType.*`, `Role.*`, `MemberRole.*`, `DefenseRequestStatus.*`, `DeadlineExtensionStatus.*`) — never renamed, per the explicit "do not rename enum values" instruction; only their associated DISPLAY LABELS were translated. (c) REST API paths, JSON field/DTO property names, Java class/package names, database table/column names — all technical identifiers, untouched. (d) Java source-code comments throughout the backend and frontend — left in English, as they are developer documentation never rendered to an end user (the task's own instruction scopes translation to "text intended for the end user"). (e) Console/log output (`log.info`/`log.error` calls, `System.err.println` in `FileStorageService`) — operational/developer-facing, not user-facing, left in English. (f) The seeded test-account emails/index numbers in `DataInitializer` and the `LoginPage`'s "Test accounts" hint block — the ACCOUNT VALUES themselves (`student@test.com`, `2024/001`, etc.) are technical/seed data, not translatable prose; the surrounding label text in `LoginPage.tsx` ("Тестовски сметки (лозинка: ...)") was translated (§3). (g) `dto/*` bean-validation annotation messages — see §14 (dead code, deliberately skipped). (h) The generic `ApiResponse` envelope defaults ("OK"/"Login successful") — see §14 (never displayed).

**16. Exact files changed — backend.** `model/enums/NotificationType.java` (all subject/body strings), `exception/GlobalExceptionHandler.java` (3 messages), `service/impl/{ThesisServiceImpl,CommitteeServiceImpl,DefenseServiceImpl,DefenseResultServiceImpl,ThesisVersionServiceImpl,NotificationServiceImpl,UserServiceImpl,AuthServiceImpl,DeadlineExtensionServiceImpl}.java` (exception messages + notification custom-message builders + email body scaffold text), `service/FileStorageService.java` (3 validation messages), `security/{SecurityUtils,ThesisReadAccessPolicy}.java` (exception messages), `service/ApplicationPdfService.java` (full rewrite: Cyrillic font registration + all template labels + date format). Test files: `src/test/java/com/praksa/integration/AuthIntegrationTest.java`, `src/test/java/com/praksa/service/{AuthServiceRegistrationTest,MentorCapacityLimitTest}.java` (message-string assertion updates only, §11). No controller signature, DTO field, repository method, security rule, or entity/schema change anywhere in the backend.

**17. Exact files changed — frontend.** `types/api.ts` — NOT modified (no user-facing strings live there; only TS type shapes). `utils/date.ts` (rewritten), `utils/roleLabels.ts` (new). `components/ui/{Modal,StatusBadge}.tsx`, `components/layout/{Header,Sidebar}.tsx`, `layouts/AuthLayout.tsx`, `api/client.ts`. `pages/auth/{LoginPage,RegisterPage}.tsx`, `pages/{DashboardPage,ThesesListPage,CreateThesisPage,CommitteePage,DefensesPage,ArchivePage,ManageCreditsPage,NotificationsPage,ThesisDetailPage}.tsx`. `features/mentor-picker/MentorPickerModal.tsx`, `features/revise-proposal/ReviseProposalModal.tsx`, `features/validation/RejectValidationModal.tsx`, `features/mentor-decision/MentorDecisionModal.tsx`, `features/versions/{VersionsSection,VersionUploader,CommentList}.tsx`, `features/credits/EditCreditsModal.tsx`, `features/committee/{ProposeCommitteeModal,CommitteeSection}.tsx`, `features/defense/{ProposeDefenseModal,RejectDefenseRequestModal,RecordGradeModal,DefenseSection}.tsx`, `features/deadline-extension/{DeadlineExtensionModal,RejectDeadlineExtensionModal,DeadlineExtensionSection}.tsx`. `layouts/AppLayout.tsx`, `routes/{AppRouter,ProtectedRoute}.tsx`, `App.tsx`, `main.tsx`, `store/authStore.ts`, `utils/cn.ts` — inspected, confirmed to contain no user-facing strings, left unmodified.

**18. Working-tree verification.** All the extensive pre-existing uncommitted work from prior sessions (the entire Defense Deadline Extension Request, 4-member-committee, defense-request-redesign, mentor-capacity, grade-5/`DEFENSE_FAILED`, 45-day-reminder, 14-day-wait work, and every other prior feature) was left completely untouched beyond the specific string-literal edits described above — this task never touched business logic, control flow, method signatures, authorization checks, database schema, or API contracts in any file. No `git reset/restore/checkout/clean/stash` was run at any point.

**19. Exact next task.** None from this task's own scope — STOP per the explicit instruction. Per the user's tracked list of official-faculty-procedure items, every item (student-proposed defense requests, room double-booking prevention, the 5-15 day scheduling window, the 14-day application-age minimum, grade 5 → `DEFENSE_FAILED`, mentor capacity 15, the 45-day mentor review reminder, 3-or-4-member committees + external non-voting member, the deadline-extension request) was already complete before this task started, and this task added the final cross-cutting item (full Macedonian localization) requested directly by the user. **No further items remain tracked in this file.** Do not start any new feature, translation touch-up, or the two documented-but-skipped dead-code items (§14) without being asked.

**20. Honesty notes.** ACTUALLY EXECUTED: the full 393-test backend suite (twice — once before and once after the 5 test-assertion fixes, both via `./mvnw.cmd -o`, JBR JDK 25, live local Postgres `diploma_system`), `npm run build` (EXIT 0, 1856 modules), and a live HTTP smoke test against a freshly-started real backend process using raw `curl` (login failure, unauthenticated access, duplicate-email registration, and a full valid login + authenticated data fetch), followed by clean process shutdown. STATICALLY VERIFIED: every file listed in §16/§17 via direct `Read`/`Edit` tool calls (not inferred); the final English-string audit greps in §10; the dead-code confirmation in §14 (grep across `praksa-frontend/src/api` for `.message` usage). NOT executed: a live browser/DOM walkthrough (no browser automation tool available in this environment — substituted with the `curl`-based HTTP verification in §13, the same standard used by prior entries in this file under the same constraint); a native-speaker linguistic review of the ~500+ translated strings (translations were produced directly by the model performing this task, using consistent terminology per §2 and cross-checked against the task's own worked examples, but were not independently reviewed by a third party).

---

**Defense Deadline Extension Request — DONE (2026-08-28). 🏁 Focused tests 55/55 (27 + 18 + 10 new); full suite 393/393 minus 1 pre-existing/environmental failure; frontend `npm run build` EXIT 0 (1855 modules).** Official faculty procedure (the last remaining open item from the user's list): a student may request an extension of the defense deadline, for a maximum of 15 additional days, with a written explanation. New `Thesis.defenseDeadline` field (nullable `OffsetDateTime`) is stamped exactly once, in `ThesisServiceImpl.verifyDefenseEligibility` (the `PENDING_DEFENSE_CHECK → PENDING_DEFENSE_SCHEDULING` transition, Item #8), as `now + 1 month` — mirroring the existing `submissionDeadline` convention; no distinct "defense deadline" concept existed before this task (documented design decision, not assumed). New dedicated `DeadlineExtensionRequest` entity/table (PENDING/APPROVED/REJECTED lifecycle, mirrors `DefenseRequest`'s shape) + `DeadlineExtensionService`/`DeadlineExtensionServiceImpl`, wired into the existing `ThesisController`: `POST /api/theses/{id}/deadline-extension-request` (STUDENT, owner-only, reason + requestedDays 1-15) creates a PENDING row and never mutates the deadline; `PATCH /api/theses/{id}/deadline-extension-decision` (STUDENT_SERVICE-only) approves (extends `defenseDeadline` by EXACTLY `requestedDays`, server-computed, never a client-supplied date) or rejects (mandatory reason, deadline untouched); `GET /api/theses/{id}/deadline-extension-requests` returns the full history via the existing `ThesisReadAccessPolicy`. Neither decision changes the thesis's workflow `status` or writes a `ThesisStatusHistory` row — a purely administrative sub-process, per the task's explicit instruction. Conservative repeat-request rule (documented design choice): at most one PENDING request and at most one APPROVED extension per thesis, so the deadline can never silently drift more than 15 days through repeated approvals; a student MAY resubmit after a rejection. 3 new `NotificationType`s (`DEADLINE_EXTENSION_REQUESTED/APPROVED/REJECTED`), reusing the existing notify/notifyRole/`isSent`/`isRead`/retry machinery untouched. Frontend: new `features/deadline-extension/` (`DeadlineExtensionModal`, `RejectDeadlineExtensionModal`, `DeadlineExtensionSection`), rendered inside the existing `ThesisDetailPage` next to `DefenseSection` (self-gates on `thesis.defenseDeadline != null` — no new page). Backend tests: 27 (`DeadlineExtensionServiceTest`, Mockito) + 18 (`DeadlineExtensionCreateRequestValidationTest`, bean validation) + 10 (`DeadlineExtensionIntegrationTest`, real HTTP+Postgres) = 55 new, all passing. Full backend suite: 393 tests (was 338 + 55 new), 1 failure — the SAME pre-existing, unrelated `DefenseRequestIntegrationTest.doubleBooking_secondApprovalRejected_realStack` environmental failure (confirmed untouched — this task's diff never touches `DefenseServiceImpl`/`DefenseController`/room-booking logic). Frontend `npm run build` passes (1855 modules, EXIT 0). **STOP — no further faculty-procedure items remain on the user's list.** Everything below this entry is prior history and remains accurate for the parts of the app it describes.

---

**Defense Deadline Extension Request — DONE (2026-08-28). 🏁 Focused tests 55/55 (27 + 18 + 10 new); full suite 393/393 minus 1 pre-existing/environmental failure; frontend `npm run build` EXIT 0 (1855 modules).**

**1. Overall verdict.** FIXED / COMPLETE. Official faculty procedure requested directly by the user: "A student may request an extension of the defense deadline, for a maximum of 15 additional days, with an explanation/reason." Implemented end-to-end (entity → repository → service → controller → DTO → frontend API → UI → tests) following the exact architectural conventions of the immediately-preceding `DefenseRequest` feature (student proposes / STUDENT_SERVICE decides / PENDING-APPROVED-REJECTED lifecycle / thesis-scoped reads via `ThesisReadAccessPolicy`). All previously completed faculty-procedure items (student-proposed defense requests, room double-booking prevention, the 5–15 day scheduling window, the 14-day minimum after `applicationSubmittedAt`, grade 5 → `DEFENSE_FAILED`, grade 6-10 → `ARCHIVED`, mentor capacity 15, the 45-day mentor review reminder, 3-or-4-member committees + the external non-voting seat, notification read/unread, archive notes, CORS) were re-verified against the current source before starting and left completely untouched — confirmed by `git status`/`git diff --stat` at the end (§16).

**2. Existing deadline model discovered (investigated first, per the task's explicit instruction — nothing assumed).** Read `Thesis`, `ThesisStatus`, `ThesisService`/`ThesisServiceImpl`, `ThesisController`, `DefenseService`/`DefenseServiceImpl`/`DefenseController`, `Defense`, `DefenseRequest` (+its repository/DTOs/controller endpoints), the notification infrastructure (`NotificationType`, `NotificationService`), `ScheduledTasksService`, the frontend `DefenseSection`/`ThesisDetailPage`/thesis types/API modules, and the existing defense-scheduling/deadline test suite (`DefenseRequestApplicationAgeTest`, `AbstractWorkflowIntegrationTest`). Findings:
   - `Thesis.submissionDeadline` — the 1-month deadline to submit the FORMAL APPLICATION (set in `createThesis`, enforced in `submitApplication`). This is a completely different milestone from "defend the thesis" and was correctly NOT reused — extending it would have no relationship to the defense process at all.
   - `Thesis.applicationSubmittedAt` — timestamp of the formal application submission; feeds the unrelated 14-day minimum-wait-before-defense-request rule.
   - `DefenseRequest.scheduledAt` — the actual proposed defense date/time, constrained to a 5-15 day window measured from the REQUEST's OWN `createdAt` (not a thesis-level deadline at all — a per-request scheduling window, already complete and explicitly NOT to be touched by this task).
   - **No existing field represented "the deadline by which the student must complete the defense process."** This is a genuinely new concept.
   - **Design decision (documented, not silently invented):** added `Thesis.defenseDeadline` (nullable `OffsetDateTime`), stamped EXACTLY ONCE, in `ThesisServiceImpl.verifyDefenseEligibility` — the Item #8 `PENDING_DEFENSE_CHECK → PENDING_DEFENSE_SCHEDULING` transition, i.e. the moment Student Service confirms the student may proceed toward defense — as `now.plusMonths(1)`, mirroring `submissionDeadline`'s exact "+1 month" convention already used elsewhere in this codebase (`createThesis`). This is the natural analogue: `submissionDeadline` bounds "submit the application within 1 month of eligibility"; `defenseDeadline` bounds "complete the defense within 1 month of being deemed eligible to defend." No other code path ever sets or clears it.
   - **Deliberately NOT enforced elsewhere** (documented scope decision): `defenseDeadline` carries no automatic blocking behavior — it does NOT gate `DefenseServiceImpl.createDefenseRequest` or any other existing operation. The task's own instructions repeatedly stress "do not redesign or regress" the already-completed defense-request workflow (5-15 day window, room booking, 14-day wait) and "do not alter unrelated deadlines" — tying a NEW enforcement rule to those flows was out of scope and risked regressing tested, working behavior. The field exists to track the deadline and support the extension workflow this task actually asked for; future enforcement (if ever required) is a separate task.

**3. Request lifecycle.** New dedicated entity `com.praksa.model.DeadlineExtensionRequest` (table `deadline_extension_requests`, `ddl-auto=update` — confirmed created cleanly via live Postgres DDL log during the integration test run, no Flyway/Liquibase introduced), modeled directly on the proven `DefenseRequest` shape: `id`, `thesis` (FK), `requestedBy` (FK — the student, stored explicitly for audit even though always `== thesis.getStudent()`, same convention as `DefenseRequest.requestedBy`/`CommitteeMember.proposedBy`), `reason` (student's written explanation, required, trimmed), `requestedDays` (int, 1-15), `status` (`PENDING`/`APPROVED`/`REJECTED`, `@Enumerated(STRING)`), `decisionReason` (nullable — the STUDENT_SERVICE rejection reason, mandatory on rejection), `previousDeadline`/`newDeadline` (audit snapshots, populated only at decision time — `newDeadline` stays null for a PENDING or REJECTED row, since rejection never changes the deadline), `createdAt`, `decidedAt`, `decidedBy`. Index on `(thesis_id, status)`, matching `DefenseRequest`'s indexing convention.
```
STUDENT submits (POST .../deadline-extension-request)
        ↓
PENDING row created — deadline UNCHANGED, no thesis status change
        ↓
STUDENT_SERVICE decides (PATCH .../deadline-extension-decision)
   ├── approved=true  → APPROVED; defenseDeadline += EXACTLY requestedDays (server-computed)
   └── approved=false → REJECTED; decisionReason stored (mandatory); deadline UNCHANGED
```
PENDING and REJECTED rows are kept permanently for audit history (never overwritten/reused) — a rejected request lets the student submit a brand-new row for a fresh attempt, exactly like `DefenseRequest`.

**4. API endpoints (exact, matching the task's specified paths).**
```
POST  /api/theses/{id}/deadline-extension-request    STUDENT (thesis owner)   { reason, requestedDays }
PATCH /api/theses/{id}/deadline-extension-decision    STUDENT_SERVICE          { approved, reason? }
GET   /api/theses/{id}/deadline-extension-requests    thesis-scoped read      → full history, newest first
```
All three added directly to the existing `ThesisController` (flat `/{id}/...` URL scheme, matching `/{id}/defense-eligibility` and `/{id}/archive-notes` — not nested under `DefenseController`'s `/defenses` mapping, since this is a thesis-level concept, not a defense-scheduling one), backed by a new dedicated `DeadlineExtensionService`/`DeadlineExtensionServiceImpl` injected into the controller alongside the existing `ThesisService` (same multi-service-per-controller pattern `DefenseController` already uses with `DefenseService`+`DefenseResultService`). DTOs: `DeadlineExtensionCreateRequest` (`@NotBlank @Size(max=2000) reason`, `@NotNull @Min(1) @Max(15) requestedDays`), `DeadlineExtensionDecisionRequest` (`@NotNull approved`, optional `reason` — cross-field "required only on rejection" rule enforced in the service, same pattern as `DefenseRequestDecisionRequest`), `DeadlineExtensionResponse` (mirrors `DefenseRequestResponse`'s flattened-name shape). Entity name `DeadlineExtensionRequest` collides with the task's illustrative DTO name suggestion, so the request/response DTOs were named `DeadlineExtensionCreateRequest`/`DeadlineExtensionDecisionRequest`/`DeadlineExtensionResponse` instead — same naming pattern as `DefenseRequestCreateRequest`/`DefenseRequestDecisionRequest`/`DefenseRequestResponse` for the `DefenseRequest` entity.

**5. Authorization (server-side, IDOR-safe).** `submitDeadlineExtensionRequest`: `requireRole(STUDENT)` → load thesis (404 if missing) → ownership check comparing `thesis.getStudent().getId()` against `securityUtils.getCurrentUser().getId()` (never a client-supplied id — there is no student-id field anywhere in the request DTO) → `UnauthorizedException` (403) on mismatch, exactly the same IDOR-safe pattern as every other student-owned action in `ThesisServiceImpl`/`DefenseServiceImpl`. `decideDeadlineExtensionRequest`: `requireRole(STUDENT_SERVICE)` — a STUDENT, MENTOR, COMMITTEE, or ARCHIVE caller is rejected before the thesis is even loaded (`thesisRepository.findById` is never called — verified by `verify(thesisRepository, never()).findById(any())` in the unit tests). `getDeadlineExtensionRequests`: delegates to the shared, already-proven `ThesisReadAccessPolicy.requireReadAccess` (owner/assigned mentor/seated committee member/STUDENT_SERVICE/ARCHIVE) — no new read-access logic invented.

**6. The 15-day validation (defense-in-depth, matching this project's "the service layer is authoritative" convention).** Enforced at TWO independent points: (a) DTO bean validation (`@Min(1) @Max(15)` on `requestedDays`) rejects an out-of-range value at the controller boundary before the service ever runs; (b) the service layer RE-VALIDATES the exact same bounds (`requestedDays == null || < 1` → 400; `> 15` → 400) — proven by `DeadlineExtensionServiceTest.requestedDaysAbove15_rejectedByService`/`requestedDaysBelow1_rejectedByService`, which construct the DTO directly (bypassing `@Valid`) to prove the service itself, not just the annotation, enforces the cap. The actual deadline arithmetic (`previousDeadline.plusDays(requestedDays)`) happens ENTIRELY server-side inside `decideDeadlineExtensionRequest` — the client never supplies a target date anywhere in either DTO, so there is no code path by which a client could request an arbitrary new deadline. Verified exact-amount extension at both boundaries (1 day and 15 days) in `DeadlineExtensionServiceTest.approval_extendsDeadlineByExactAmount`/`approval_oneDayBoundary`, and end-to-end over real HTTP+Postgres in `DeadlineExtensionIntegrationTest.requestThenApprove_realStack`.

**7. Persistence/database changes.** One new nullable column, `theses.defense_deadline` (same `ddl-auto=update` mechanism as every other optional timestamp on this entity — `submissionDeadline`, `applicationSubmittedAt`, `lastVersionSubmittedAt` — no migration tool introduced). One new table, `deadline_extension_requests`, with the columns listed in §3, proper FKs to `theses`/`users` (×3: `requestedBy`, `decidedBy`, plus the `thesis` FK), timestamps, `status` as `@Enumerated(EnumType.STRING)` (plain varchar, matching every other status column in the project), and an index on `(thesis_id, status)`. Both confirmed created cleanly against the live local Postgres `diploma_system` database during the integration test run (Hibernate DDL log shows `defense_deadline` in the generated `theses` `UPDATE` statement and successful inserts/selects against `deadline_extension_requests`) — no data loss, no failed migration, no existing row affected (existing theses simply get `defense_deadline = NULL`, which the feature already treats correctly as "no deadline set yet, cannot request an extension").

**8. Notifications.** Three new `NotificationType` values, following the exact `(subject, defaultBody)` constructor convention as every other entry (no reordering): `DEADLINE_EXTENSION_REQUESTED` (student → STUDENT_SERVICE role fan-out via the existing `notifyRole`, on submission), `DEADLINE_EXTENSION_APPROVED` and `DEADLINE_EXTENSION_REJECTED` (STUDENT_SERVICE → the requesting student, on decision, via the existing 4-arg `notify(recipient, thesis, type, customMessage)` overload — a primitive `String`, so nothing crosses the `@Async` email boundary, matching the established pattern used for `MENTOR_REQUESTED_CHANGES`/`APPLICATION_REJECTED_BY_ARCHIVE`/etc.). Per the task's explicit content instructions, the custom messages lead with the exact Macedonian phrasing requested — "Барањето за продолжување на рокот е одобрено." / "...е одбиено." — followed by the practical detail (the new deadline on approval; the decision reason on rejection). Exactly one notification per event (verified: `rejection_sendsExactlyOneNotificationWithReason` also asserts `notifyRole` is never called on a decision — no accidental STUDENT_SERVICE fan-out on approve/reject). All three participate in the pre-existing `isSent`/`sent`, `isRead`/`read`, async-email, and retry (P2.6) machinery completely unchanged — nothing about `NotificationServiceImpl`, `EmailService`, or `ScheduledTasksService.retryUnsentNotifications` was touched; they are simply new rows through the same pipe.

**9. Thesis status — deliberately unchanged.** Per the task's explicit instruction (§9: "should NOT unnecessarily change the thesis lifecycle status"), neither `submitDeadlineExtensionRequest` nor `decideDeadlineExtensionRequest` ever calls `transitionStatus()` or writes a `ThesisStatusHistory` row — the thesis's workflow `status` (e.g. `PENDING_DEFENSE_SCHEDULING`/`DEFENSE_SCHEDULED`) is completely untouched by either action, proven by `DeadlineExtensionServiceTest.approval_neverChangesThesisStatus` and asserted explicitly at every step of `DeadlineExtensionIntegrationTest.requestThenApprove_realStack`. This is a purely administrative sub-process layered on top of the existing lifecycle, exactly like the pre-existing P2.2 archive-notes editor.

**10. Repeat-request policy (design decision, documented per the task's explicit request — §6).** Because the faculty procedure handed to this task specifies only "a maximum of 15 additional days" per request with no stated limit on the NUMBER of requests, and the project has no precedent for a multi-stage/stacking extension concept, the conservative interpretation was chosen: (a) at most one PENDING request may exist per thesis at a time (mirrors `DefenseRequest`'s identical "one pending proposal" rule — enforced via `findByThesisAndStatus(thesis, PENDING)`); (b) a student MAY resubmit a brand-new request after a rejection (the rejected row is never reused, matching `DefenseRequest`); (c) at most ONE APPROVED extension is permitted per thesis EVER — once an `APPROVED` row exists for a thesis, `submitDeadlineExtensionRequest` rejects any further submission with a clear 400 message. This guarantees the total extension granted can never silently exceed the single 15-day-per-request cap through repeated approvals — the deadline can drift by AT MOST 15 days from its original value, period. Proven by `DeadlineExtensionServiceTest.alreadyApprovedExtension_blocksFurtherSubmission` and `DeadlineExtensionIntegrationTest.secondExtensionAfterApproval_blocked_realStack`. If the faculty procedure later clarifies that multiple stacked extensions are intended, this is the one rule to revisit — documented here rather than silently assumed either way.

**11. Frontend changes.** `types/api.ts` — `+defenseDeadline: string | null` on `Thesis`; `+DeadlineExtensionStatus` type; `+DeadlineExtensionRequest` interface (mirrors the backend response shape exactly — no boolean fields in this feature, so none of the project's established `isX()`→Jackson-strips-"is" wire-key gotchas apply here, unlike the `CommitteeMember.externalNonVoting`/`Notification.sent`/`Notification.read` precedents). `api/thesisApi.ts` — `+requestDeadlineExtension`, `+decideDeadlineExtension`, `+getDeadlineExtensionRequests` (URLs verified to match the controller exactly). New `features/deadline-extension/` folder: `DeadlineExtensionModal.tsx` (student request form — reason textarea + requestedDays number input 1-15, mirrors `ProposeDefenseModal`'s structure), `RejectDeadlineExtensionModal.tsx` (Student Service reason-required rejection, mirrors `RejectDefenseRequestModal`/`RejectValidationModal` — this codebase's established convention is a small dedicated modal per feature area rather than one heavily-parametrized generic component, followed here for consistency), `DeadlineExtensionSection.tsx` (self-contained section: shows the current deadline, the PENDING request with Одобри/Одбиј for Student Service, the latest APPROVED (old→new deadline) or REJECTED (reason) outcome, and the request button for an eligible student — self-gates on `thesis.defenseDeadline != null`, rendering nothing before eligibility verification, so no separate visibility flag was needed in `ThesisDetailPage`). Wired into the EXISTING `ThesisDetailPage` immediately after `DefenseSection` (no new page, no new route, per the task's explicit "use the existing thesis/defense UI" instruction) and `NotificationsPage.tsx` gained the 3 new type labels. The frontend performs light UX-only prechecks (hiding the request button once a PENDING or APPROVED row exists); the backend remains the sole authority regardless of what the UI shows.

**12. API/frontend contract consistency (verified, not guessed — per the task's explicit §11 instruction).** Compared, field by field: controller endpoint URL/method ↔ `thesisApi.ts` call sites (exact match, verified by the integration tests hitting the same literal paths the frontend calls); request body JSON keys (`reason`, `requestedDays`, `approved`) ↔ DTO field names (identical, no Jackson prefix-stripping involved since neither DTO has a boolean getter with an `is`-prefixed name — `approved` is a plain `Boolean` field/setter, matching the proven `DefenseRequestDecisionRequest.approved` wire behavior already exercised by dozens of passing tests); response JSON shape (`DeadlineExtensionResponse`, all fields plain non-boolean getters — `int requestedDays` → `getRequestedDays()`, never `isRequestedDays()`) ↔ the frontend `DeadlineExtensionRequest` TypeScript interface (byte-for-byte field-name match). No serialization surprises were possible in this feature specifically because it introduces zero new boolean fields — confirmed by inspection, not assumed.

**13. Tests added.**
- `DeadlineExtensionServiceTest` (27, pure Mockito, no Spring context) — covers the full matrix from the task's §12 checklist: owner can submit / non-owner rejected (403) / non-STUDENT rejected; no-defense-deadline-set rejected; service-layer defense-in-depth for `requestedDays` above 15 / below 1 / blank reason; duplicate-PENDING rejected; already-APPROVED blocks further submission; STUDENT_SERVICE can decide / STUDENT cannot / MENTOR+COMMITTEE+ARCHIVE cannot (parameterized `@EnumSource`) / no-pending-request decision rejected; approval extends by EXACTLY the requested amount at both the general case and the 1-day lower boundary; approval never changes thesis status; approval cannot be repeated (second call finds nothing PENDING, deadline unchanged); approval sends exactly one notification to the student; rejection without a reason rejected; rejection preserves the reason and leaves the deadline unchanged (`newDeadline` asserted null); rejection cannot be repeated (decision reason from the first call proven unchanged); rejection sends exactly one notification with no `notifyRole` fan-out; a new request is allowed after rejection; the reason is trimmed before storage; read history is authorized via the shared `ThesisReadAccessPolicy` (verified the exact mock interaction).
- `DeadlineExtensionCreateRequestValidationTest` (18, bean validation with a real `Validator`, mirrors `RecordResultRequestValidationTest`'s style) — valid request passes; blank/whitespace/null reason fails (`@NotBlank`); reason of exactly 2000 chars passes, 2001 fails (`@Size` boundary); requestedDays 1/5/10/15 pass, 0/-1/-5/16/30/100/null fail (`@Min`/`@Max`/`@NotNull` boundaries).
- `DeadlineExtensionIntegrationTest` (10, real `@SpringBootTest`/MockMvc + real local Postgres, extends the shared `AbstractWorkflowIntegrationTest` exactly like `DefenseRequestIntegrationTest`) — happy path over real HTTP+DB (request → PENDING → approve → deadline extended by exactly the requested amount, both notifications counted); reject-then-resubmit (deadline unchanged, reason preserved, a genuinely new row for the second attempt, which can then be approved); duplicate-PENDING rejected with no duplicate row; a real cross-student IDOR attempt gets a real 403 with zero rows created; an unauthenticated request is rejected with zero rows created; a STUDENT and a MENTOR both get a real 403 attempting to decide; double-approval over real HTTP is impossible (second decision 400, deadline unchanged); a second extension is blocked once one is already APPROVED; requesting 16 days is rejected server-side even bypassing any client check; and an explicit regression test driving the ENTIRE existing defense-request → room-booking → grading → archiving pipeline to completion AFTER using the deadline-extension feature, proving zero interference with the already-completed, untouched `DefenseRequest` workflow.
- `AbstractWorkflowIntegrationTest` gained 3 new protected helper methods (`requestDeadlineExtension`, `approveDeadlineExtension`, `rejectDeadlineExtension`) and one new `@Autowired DeadlineExtensionRequestRepository` field, following the exact same additive pattern as the existing `requestDefense`/`approveDefenseRequest`/`rejectDefenseRequest` helpers — no existing helper method was modified.
- Per the task's §12 items 24-30 (regression checklist), NO new tests were written to re-prove the already-completed defense-request workflow, 5-15 day window, 14-day application-age rule, grade-5/grade-6-10 outcomes, 3-or-4-member committee behavior, or the mentor 15-limit — those are already exhaustively covered by their own existing test suites (`DefenseRequestWorkflowTest`, `DefenseRequestApplicationAgeTest`, `DefenseGradeOutcomeTest`, `CommitteeCompositionValidationTest`, `MentorCapacityLimitTest`, etc.), and running the FULL suite (§ below) is the actual regression proof — all of them still pass unmodified.

**14. Focused test results (real, JetBrains Runtime JDK 25 via `./mvnw.cmd -o`, project `--release 21`, live local Postgres `diploma_system`).**
`./mvnw.cmd -o -Dtest=DeadlineExtensionServiceTest,DeadlineExtensionCreateRequestValidationTest test` → **Tests run: 45, Failures: 0, Errors: 0, BUILD SUCCESS.**
`./mvnw.cmd -o -Dtest=DeadlineExtensionIntegrationTest test` → **Tests run: 10, Failures: 0, Errors: 0, BUILD SUCCESS** (Hibernate DDL log confirms `defense_deadline` column and `deadline_extension_requests` table created cleanly under `ddl-auto=update`).

**15. Full backend test results.** `./mvnw.cmd -o test` → **Tests run: 393, Failures: 1, Errors: 0, Skipped: 0.** Baseline (documented in the "3-or-4-member committee" entry directly below) was 338; +55 = exactly the new tests in §13, all green. The single failure is the SAME pre-existing, unrelated `DefenseRequestIntegrationTest.doubleBooking_secondApprovalRejected_realStack` environmental failure (`expected: <1> but was: <2>`, a leftover non-cancelled `Defense` row in room "Shared Hall" from earlier manual/live testing sessions in the local dev DB, documented across many prior entries in this file) — re-run in isolation (`-Dtest=DefenseRequestIntegrationTest`, 6 tests, 1 failure, same assertion) to confirm it is deterministic and unrelated. This task's diff touches ZERO files under `DefenseServiceImpl`/`DefenseController`/`DefenseRepository`/`DefenseRequestIntegrationTest` — confirmed by `git status`/`git diff --stat` (§16). Per the task's explicit instruction, this was NOT "fixed" here — only re-confirmed and documented, exactly as every prior entry that hit it has done.

**16. Working-tree verification.** `git status`/`git diff --stat` before and after this task confirm the ONLY tracked backend files this task modified are: `src/main/java/com/praksa/model/Thesis.java` (+`defenseDeadline` field only), `src/main/java/com/praksa/model/enums/NotificationType.java` (+3 enum values only), `src/main/java/com/praksa/dto/thesis/ThesisResponse.java` (+`defenseDeadline` field only), `src/main/java/com/praksa/service/impl/ThesisServiceImpl.java` (+one `setDefenseDeadline` line inside `verifyDefenseEligibility` only — no other method touched), `src/main/java/com/praksa/controller/ThesisController.java` (+3 new endpoints + 1 new constructor dependency only), and `src/test/java/com/praksa/integration/AbstractWorkflowIntegrationTest.java` (+3 helper methods + 1 `@Autowired` field only). New (untracked) backend files, all this task's own: `model/DeadlineExtensionRequest.java`, `model/enums/DeadlineExtensionStatus.java`, `repository/DeadlineExtensionRequestRepository.java`, `dto/thesis/DeadlineExtensionCreateRequest.java`, `dto/thesis/DeadlineExtensionDecisionRequest.java`, `dto/thesis/DeadlineExtensionResponse.java`, `service/DeadlineExtensionService.java`, `service/impl/DeadlineExtensionServiceImpl.java`, plus the 3 new test files in §13. Frontend: the ONLY tracked files modified were `types/api.ts` (+`defenseDeadline` on `Thesis` +2 new types, no existing field touched), `api/thesisApi.ts` (+3 new functions only), `pages/ThesisDetailPage.tsx` (+1 import +2 render lines only — the surrounding `DEFENSE_FAILED`/archive-notes diff visible in `git diff` predates this task, from the still-uncommitted prior "Grade 5 = DEFENSE_FAILED" session, and was NOT touched by this task), `pages/NotificationsPage.tsx` (+3 label entries only); new (untracked) frontend files, all this task's own: the entire `features/deadline-extension/` folder (3 files). All the extensive pre-existing uncommitted work from prior sessions (the entire `DefenseRequest` redesign, mentor-capacity, grade-5/`DEFENSE_FAILED`, 45-day reminder, 14-day wait, 3-or-4-member committee/external-non-voting-member work, and all their associated files in both repos) was left completely untouched — confirmed line-by-line via `git diff --stat` scoped to exactly the files this task changed. No `git reset/restore/checkout/clean/stash` was run at any point.

**17. Security verification (per the task's explicit §14 checklist).** (a) Student ownership enforced server-side — `thesis.getStudent().getId().equals(securityUtils.getCurrentUser().getId())`, never a client-supplied id (no such field exists in either request DTO). (b) STUDENT_SERVICE decision authorization enforced server-side via `requireRole`, checked BEFORE the thesis is even loaded. (c) No client-supplied user id is trusted anywhere in this feature. (d) No IDOR — proven by a real 403 over real HTTP (`nonOwnerStudent_rejected_realStack`) and the shared, already-hardened `ThesisReadAccessPolicy` for reads. (e) The deadline cannot be manipulated directly by the client — no date field exists in either DTO; the new deadline is always `previousDeadline.plusDays(requestedDays)`, computed entirely server-side from the server's own stored `previousDeadline` and the bounds-checked `requestedDays`. (f) The 15-day cap cannot be bypassed — DTO validation AND independent service-layer re-validation, both tested. (g) A request cannot be approved twice — the second decision call finds no PENDING row (400), proven at both the unit and real-HTTP level. (h) The deadline cannot be extended twice by replaying the same decision — same mechanism as (g); `thesisRepository.save` is `never()`-verified on the rejected replay attempt. (i) No unrelated thesis status change — `transitionStatus()` is never called by either service method; proven explicitly. (j) No cross-user notification leakage — the approval/rejection notification always targets `pending.getRequestedBy()` (the actual requesting student, resolved server-side from the persisted row, never from client input), and the submission fan-out targets only the `STUDENT_SERVICE` role via the existing, unmodified `notifyRole`.

**18. Known limitations / design decisions (documented, not fixed — explicitly in-scope-to-document per the task).**
- `defenseDeadline` carries no automatic enforcement (§2) — a future task could decide whether `DefenseServiceImpl.createDefenseRequest` (or some other operation) should be blocked once the deadline has passed; deliberately out of scope here to avoid touching the already-completed, tested defense-request workflow.
- The "at most one APPROVED extension per thesis" rule (§10) is a conservative interpretation of an underspecified faculty procedure — if the real-world procedure later permits multiple stacked extensions (still capped at 15 days each), this is the single rule to revisit.
- No list-level UI indicator (e.g. a "pending extension" badge on `DefensesPage.tsx`) was added — the feature lives entirely on `ThesisDetailPage`, per the task's "use the existing thesis/defense UI, do not create unnecessary new pages" instruction; a future polish task could surface it on the list view too.
- Legacy theses that reach `PENDING_DEFENSE_SCHEDULING`/`DEFENSE_SCHEDULED` before this feature existed will have `defenseDeadline = NULL` (since it is only stamped going forward at `verifyDefenseEligibility` time) — such a thesis correctly cannot request an extension (`BadRequestException`, not silently bypassed) until/unless a future task decides to back-fill it. No such theses exist in the current dev database at school scale.

**19. Exact next task.** None from this task's own scope — STOP per the explicit instruction. Per the user's own list of official-faculty-procedure items tracked across this file's history (student-proposed defense requests, room double-booking prevention, the 5-15 day window, the 14-day application-age minimum, grade 5 → `DEFENSE_FAILED`, mentor capacity 15, the 45-day mentor review reminder, 3-or-4-member committees + external non-voting member, and now the deadline extension request), **no further items remain on that list.** Do not start any new feature automatically — wait for explicit direction.

**20. Honesty notes.** ACTUALLY EXECUTED: the 45-test focused unit/DTO-validation run, the 10-test focused integration run, the isolated re-run of `DefenseRequestIntegrationTest` confirming its one pre-existing failure, the full 393-test suite (all via `./mvnw.cmd -o`, JBR JDK 25, live local Postgres `diploma_system`), and `npm run build` (EXIT 0, 1855 modules). STATICALLY VERIFIED: `git status`/`git diff --stat` confirming the exact file set touched by this task (§16); that `submitDeadlineExtensionRequest`/`decideDeadlineExtensionRequest` never call `transitionStatus()` (read directly from source, also proven by the dedicated unit test); the API/frontend contract field-by-field (§12). NOT executed: a live browser/manual HTTP walkthrough against a freshly-restarted server process — verification instead relies on the `DeadlineExtensionIntegrationTest` suite, which drives the real Spring MVC dispatcher, the real Spring Security filter chain (real JWT mint/verify), the real service/repository layers, and the real local Postgres database over real HTTP semantics (`MockMvc`) for every required scenario — the same evidentiary standard used by every recent entry in this file in place of a live walkthrough.

---

**3-or-4-member committee + external non-voting member — DONE (2026-08-28). 🏁 Focused tests 39/39 (22 new + 17 re-verified across 5 updated files); full suite 338/338 minus 1 pre-existing/environmental failure; frontend `npm run build` EXIT 0 (1852 modules).**

**1. Overall verdict.** FIXED / COMPLETE. Official faculty procedure requested directly by the user: a thesis defense committee may now be 3 OR 4 members (was: exactly 3, hard-enforced). The optional 4th seat is a single external, non-voting professional from practice. All previously completed faculty-procedure items (student-proposed defense requests, Student Service approval/rejection, room conflict checking, 5–15 day scheduling window, 14-day minimum after application submission, grade 5 → `DEFENSE_FAILED`, mentor capacity 15, 45-day mentor review reminder, notification read/unread, archive notes, CORS) were verified against the current source before starting and left completely untouched.

**2. Existing implementation audited first (per the task's explicit instruction — nothing assumed).** Read `CommitteeMember`, `CommitteeMemberRepository`, `ProposeCommitteeRequest`, `CommitteeMemberResponse`, `CommitteeService`/`CommitteeServiceImpl`, `CommitteeController`, `DefenseResultServiceImpl` (the grading path), `DefenseRecordPdfService`, the frontend `CommitteeSection`/`ProposeCommitteeModal`/`CommitteePage`/`DefenseSection`/`RecordGradeModal`, and the existing committee/grading tests (`CommitteeServiceNotificationTest`, `DefenseResultGradingAuthorizationTest`, `DefenseGradeOutcomeTest`, `ArchiveMetadataTest`, `DefenseResultGradeValidationTest`, `DefenseResultServiceNotificationTest`, `CommitteeWorkflowIntegrationTest`, `AbstractWorkflowIntegrationTest`). Ran a repo-wide search for `== 3`, `!= 3`, `size() == 3`, `exactly 3`, `mentor + 2`, `countByThesis`, and the frontend `/3`/`memberCount` patterns (§13 below) to separate real committee-size assumptions from unrelated numbers/test fixtures before changing anything — exactly two hits needed a code change (`ProposeCommitteeRequest`'s `@Size`, `CommitteeServiceImpl.approveCommittee`'s safety check) and one needed a comment-only fix (`ScheduledTasksService`'s stale "3-member committee" javadoc); everything else was either already-correct generic iteration over `findByThesis(...)` (unaffected by member count) or an unrelated numeric constant (`DefenseResultServiceImpl.MAX_DEFENSE_GRADE = 10`).

**3. Composition rule — exact.**
```
2 members  → reject  (below minimum)
3 members  → accept  (all voting; 0 external)
4 members  → accept  ONLY IF exactly 1 is external non-voting (3 voting + 1 external)
5 members  → reject  (above maximum)
```
Enforced at TWO points, mirroring the pre-existing "propose creates, approve is the final safety net" pattern already used for the old "exactly 3" rule:
- **`CommitteeServiceImpl.proposeCommittee`** (creation time) — new private `validateProposalComposition(professorIds, externalId)`: 2 proposed professors + `externalProfessorId == null` → 3-member committee, all voting; 3 proposed professors + a non-null `externalProfessorId` that IS one of the three → 4-member committee, exactly 1 external; any other shape (2 proposed + an external id set, 3 proposed + no external id, external id not among the three) → `BadRequestException` (400), zero `CommitteeMember` rows created.
- **`CommitteeServiceImpl.approveCommittee`** (the authoritative final gate) — kept the exact pre-existing `countByThesis` safety check but widened `!= 3` to `!= 3 && != 4`, then added new `validateCommitteeComposition(members, memberCount)`, re-derived from the ACTUAL persisted `CommitteeMember` rows (not the propose-time request) — `externalCount` must be 0 for a 3-member committee, exactly 1 for a 4-member committee, and the mentor's own `MENTOR_MEMBER` seat must never be flagged external. This re-validation is deliberate defense-in-depth: it does not trust that `proposeCommittee` was the only path that ever created these rows (proven by a dedicated integration test that mutates a persisted seat directly and shows `approveCommittee` still catches it — §11).

**4. `CommitteeMember.isExternalNonVoting` — the new field.** `model/CommitteeMember.java`:
```java
@Column(name = "is_external_non_voting", nullable = false)
@ColumnDefault("false")
private boolean isExternalNonVoting;
```
Named/annotated exactly like the sibling booleans already on this codebase's entities (`Defense.isCancelled`, `ThesisVersion.isFinal`, `Notification.isSent`/`isRead`) — `@ColumnDefault("false")` is the SAME mechanism `Notification.isRead` (P3.6) used to make Hibernate emit `DEFAULT false` in the `ddl-auto=update`-generated `ALTER TABLE ... ADD COLUMN`, so every pre-existing committee-member row back-fills to `false` (normal voting member) safely — confirmed live: the full backend suite (which exercises real Postgres DDL) ran clean with no startup/migration failure, and no existing `CommitteeMember` row's `isExternalNonVoting` was ever set to anything but its default. No Flyway/Liquibase introduced (none exists in this project). This is a property of the SEAT (`CommitteeMember`), never a new system-wide `Role` — no `EXTERNAL` role was created, and `User`/`Role`/`SecurityConfig`/`JwtUtil` were not touched.

**5. Grading restriction — the critical part.** `DefenseResultServiceImpl.recordResult` previously authorized grading via `requireCommitteeSeat` (a boolean `existsByThesisAndProfessor` check — any seat sufficed). Replaced with `requireVotingCommitteeSeat`, which fetches the ACTUAL seat row via a new repository method `CommitteeMemberRepository.findByThesisAndProfessor(Thesis, User)` (row-returning sibling of the existing `existsByThesisAndProfessor`, needed because a yes/no existence answer is no longer enough — the seat's `isExternalNonVoting` must be inspected):
```java
private void requireVotingCommitteeSeat(Thesis thesis, User recorder) {
    CommitteeMember seat = committeeRepository.findByThesisAndProfessor(thesis, recorder)
            .orElseThrow(() -> new UnauthorizedException(
                    "Only a member of this thesis's defense committee can record the grade"));
    if (seat.isExternalNonVoting()) {
        throw new UnauthorizedException(
                "The external non-voting committee member cannot record a defense grade");
    }
}
```
Runs BEFORE the status check and before any `DefenseResult` save, status transition, history row, archive metadata, or notification — exactly the same ordering as the pre-existing (BUG-13-era) authorization guard it replaces, so a rejected external-member attempt mutates NOTHING (no `DefenseResult`, no status change, no archive metadata, no notification) — proven by both a Mockito unit test (`DefenseResultGradingAuthorizationTest` test 8) and a real-HTTP integration test (`CommitteeExternalMemberIntegrationTest`, §11). `existsByThesisAndProfessor` itself is UNCHANGED and still used everywhere else (read-access checks in `ThesisReadAccessPolicy`, `requireRecordAccess` for the record-PDF endpoint, the propose-time duplicate check) — the external member's READ/participation access (viewing the thesis, downloading the record PDF, submitting committee review notes, appearing in committee lists) is completely untouched, per the task's explicit instruction that only the VOTING/grading restriction was required (§18 of the task).

**6. `DefenseResultServiceImpl.MAX_DEFENSE_GRADE`/grade-outcome logic (grade 5 = `DEFENSE_FAILED`, 6-10 = archive) — untouched.** The task asked whether the existing grading system assumes a specific number of graders/voters — inspected and confirmed it does not: `recordResult` records exactly ONE grade per defense (from whichever single voting committee member calls the endpoint) with no vote-counting, no majority calculation, and no requirement that every member grade. This behavior is unchanged and inherently compatible with "3 voting members" or "3 voting + 1 non-voting" — the external member simply never reaches the point of submitting a grade. No `DefenseResult` row is ever created for the external member (not even a null/placeholder one) — they simply have no grading result, per the task's explicit instruction not to fabricate placeholder data.

**7. DTO/API changes.** `dto/committee/ProposeCommitteeRequest.java` — `@Size(min=2,max=2)` → `@Size(min=2,max=3)`; new nullable `UUID externalProfessorId` field (only meaningful when `professorIds` has 3 entries). `dto/committee/CommitteeMemberResponse.java` — new field, deliberately named `externalNonVoting` (no "is" prefix) so Lombok's generated getter is unambiguously `isExternalNonVoting()` and the JSON wire key is unambiguous — **empirically verified**, not guessed (per the task's explicit instruction, echoing this project's documented `isSent`→`"sent"` precedent): a new `CommitteeMemberResponseSerializationTest` constructs the DTO, serializes it with a real `ObjectMapper`, and asserts the JSON key is exactly `"externalNonVoting"` (and that `"isExternalNonVoting"` does NOT appear) — 2/2 pass. `praksa-frontend/src/types/api.ts`'s `CommitteeMember.externalNonVoting: boolean` matches this confirmed key exactly.

**8. Authorization / security verification.** (a) External status is set ONLY server-side, inside `proposeCommittee`, by comparing each proposed professor's id against the request's `externalProfessorId` — a caller cannot mark themselves or anyone else external by any other path; there is no PATCH/update endpoint for `isExternalNonVoting` at all. (b) The mentor can never become the external seat: the mentor is auto-added separately and is never present in `professorIds` (the pre-existing "cannot add the thesis mentor again" check fires first if someone tries), and `approveCommittee`'s `validateCommitteeComposition` additionally re-checks the mentor's persisted seat is voting, as defense-in-depth against any future/alternate write path. (c) A caller cannot bypass the DTO's `@Size(min=2,max=3)` bean validation and reach the service layer with an invalid shape — `validateProposalComposition` re-derives and re-checks the same rule server-side (never trusts bean validation alone, consistent with the rest of this codebase's "the service layer is authoritative" architecture rule). (d) Grading remains thesis-scoped: `findByThesisAndProfessor` is checked against the REQUESTED thesis, so a professor's external (or voting) seat on one thesis never leaks into another thesis's grading decision — no cross-thesis IDOR was introduced (the mechanism is the row-returning sibling of the exact same `existsByThesisAndProfessor` call the pre-existing IDOR fix already relied on). (e) No new sensitive data is exposed — the new field is a plain boolean describing a seat's voting eligibility, already visible to everyone who could already see the committee list (owner/mentor/seated member/STUDENT_SERVICE/ARCHIVE, via the unchanged `ThesisReadAccessPolicy`).

**9. Frontend changes.**
- `types/api.ts` — `CommitteeMember.externalNonVoting: boolean` (comment documents the verified wire key).
- `api/committeeApi.ts` — `propose(thesisId, professorIds, externalProfessorId?)`.
- `features/committee/ProposeCommitteeModal.tsx` — the mandatory 2-voting-professor picker is unchanged; added an optional "Add an external non-voting member" checkbox that, when checked, shows a single-select dropdown (excluding the 2 already-selected voting professors) to designate the external member. Submit is disabled unless exactly 2 voting professors are selected and (if the checkbox is on) an external member is chosen. Macedonian hint text: "Надворешен член – без право на оценување."
- `features/committee/CommitteeSection.tsx` — header count changed from the hardcoded `{members.length} / 3 members` to `{members.length} member(s) (3–4 allowed)`; `canApprove` now accepts `members.length === 3 || members.length === 4`; each member row shows an amber "Надворешен член – без право на оценување" badge when `m.externalNonVoting`.
- `pages/CommitteePage.tsx` — the overview card's `Committee: {memberCount}/3` denominator (a genuine stale exact-3 assumption, found during the repo-wide sweep — §13) was corrected to `Committee: {memberCount}` (no longer implies a fixed 3).
- `features/defense/DefenseSection.tsx` — now fetches the committee list (`committeeApi.list`) alongside the existing defense/request fetches; derives `myCommitteeSeat`/`isExternalNonVotingMember` for the logged-in user and folds that into `canGrade` (`(isMentor || role==='COMMITTEE') && !isExternalNonVotingMember`); when the logged-in user IS the external seat and would otherwise see the grade button, shows "Надворешен член – нема право на оценување" instead. This is UX only — the backend (§5) is the real, sole authorization boundary, and rejects a direct API call from the external member regardless of what the frontend renders.
- `RecordGradeModal.tsx`, `DefensesPage.tsx` — inspected, no change needed (grading is initiated only from `DefenseSection`; `DefensesPage` is read-only display).

**10. Database impact.** One new nullable-turned-`NOT NULL`-with-default column, `committee_members.is_external_non_voting BOOLEAN NOT NULL DEFAULT false` — same `ddl-auto=update` + `@ColumnDefault` mechanism as every other recent boolean addition on this project (`is_read` for P3.6). Existing committee-member rows back-fill to `false` (normal voting members) — none were converted to external. No other schema change.

**11. Tests added.**
- `dto/committee/CommitteeMemberResponseSerializationTest` (2, new) — the wire-key verification (§7).
- `service/CommitteeCompositionValidationTest` (14, new, pure Mockito) — approve-time: A) 2 members rejected; B) 3 voting members accepted; C) 4 members with exactly 1 external accepted; D) 4 members with 0 external rejected; E) 4 members with 2 external rejected; F) 5 members rejected; G) the mentor's own seat flagged external (simulated corrupted/legacy data) rejected. Propose-time: 2 professors + no external → 3-member, all voting; 3 professors + a valid external id → 4-member, exactly 1 external, correct professor flagged; 3 professors + no external id → rejected; 2 professors + an external id set → rejected; external id not among the proposed professors → rejected; the mentor's own id used as the "external" designee → rejected (structurally impossible to reach the composition check because the pre-existing mentor-duplicate check fires first — proven, not assumed); H) the pre-existing duplicate-professor-in-request check still fires correctly with the new 2-or-3 shape.
- `integration/CommitteeExternalMemberIntegrationTest` (5, new, real `@SpringBootTest`/MockMvc + real local Postgres, mirroring `CommitteeWorkflowIntegrationTest`'s style) — a 4-member committee (3 voting + 1 external) can be proposed AND approved, with the external member visible in the real `GET .../committee` response (`externalNonVoting: true`, correct professor id) — satisfies task letters C, P, R; proposing 3 professors with 0 designated external is rejected (400) over real HTTP with zero committee rows created; approving a persisted 4-member committee with 2 external seats (simulated by directly mutating a persisted row, since `proposeCommittee` itself can never produce that shape) is rejected (400), proving `approveCommittee` re-derives the rule from the DB rather than trusting the propose-time request — satisfies letter E at the integration level too; a full workflow drives a 4-member-committee thesis all the way to `DEFENSE_SCHEDULED` — satisfies letters O (existing 3-member path, re-verified via the pre-existing untouched `ThesisWorkflowIntegrationTest`), P, and Q; the last test proves, over real HTTP against the real security chain, that the external member (confirmed genuinely seated via a direct repository read) gets **403** attempting `POST .../defenses/{id}/result` with zero mutations (no `DefenseResult`, status stays `DEFENSE_SCHEDULED`, no registration number), and that a genuine voting member (`professorA`) then grades successfully in the same test, archiving the thesis — satisfies task letters J, K, L, I, and section 23's explicit "external member attempting to grade" + "normal voting member grading" integration requirements.
- `service/DefenseResultGradingAuthorizationTest` (updated: 8 existing tests' stubs switched from `existsByThesisAndProfessor` to `findByThesisAndProfessor` returning an actual `CommitteeMember` row, since the production code now needs the row, not just a boolean; +1 new test — "8. External non-voting committee member SEATED on THIS thesis → 403, no mutations" — proving a GENUINELY seated external member (not merely an unrelated/unseated user) is still rejected, the core distinction this whole task is about).
- `service/DefenseGradeOutcomeTest`, `service/ArchiveMetadataTest`, `service/DefenseResultGradeValidationTest`, `service/DefenseResultServiceNotificationTest` (updated, no new tests) — same mechanical stub swap (`existsByThesisAndProfessor` → `findByThesisAndProfessor` returning a voting `CommitteeMember` row) so the pre-existing grade-5/archive-metadata/grade-range/notification tests keep exercising a REAL voting seat rather than accidentally relying on the old boolean-only check; all pre-existing assertions (grade 5 → `DEFENSE_FAILED`, grades 6-10 → `ARCHIVED` with metadata, notification recipients/types) are byte-for-byte unchanged and still pass — satisfies task letters M and N.

**12. Focused test results (real, JBR JDK 25 via `./mvnw.cmd -o`, project `--release 21`, live local Postgres `diploma_system`).**
`./mvnw.cmd -o -Dtest=CommitteeMemberResponseSerializationTest test` → **2/2, BUILD SUCCESS.**
`./mvnw.cmd -o -Dtest=DefenseGradeOutcomeTest,ArchiveMetadataTest,DefenseResultGradeValidationTest,DefenseResultServiceNotificationTest,DefenseResultGradingAuthorizationTest test` → **31/31, BUILD SUCCESS** (includes the new external-member-denied test).
`./mvnw.cmd -o -Dtest=CommitteeCompositionValidationTest,CommitteeServiceNotificationTest test` → **17/17, BUILD SUCCESS** (one iteration surfaced a genuine `UnnecessaryStubbingException` in a duplicate-professor test — a test-only bug of my own making, not a production issue — fixed by removing a stub that could never be reached because the code correctly throws one iteration earlier; re-run green).
`./mvnw.cmd -o -Dtest=CommitteeExternalMemberIntegrationTest,CommitteeWorkflowIntegrationTest test` → **8/8, BUILD SUCCESS** (real Postgres — this run also confirms the new `is_external_non_voting` column was created cleanly under `ddl-auto=update`).

**13. Repo-wide exact-3 sweep (task §21/§25 requirement — executed, not skipped).** Backend `src/main`: grep for `== 3`, `!= 3`, `size() == 3`, `mentor + 2`, `countByThesis` found exactly the two genuine hits already fixed (§3) plus one stale comment in `ScheduledTasksService.java` ("...3-member committee including the auto-added mentor...", inside the auto-advance notification loop) — corrected to "3- or 4-member committee" with a note that the external member is a genuine seat and is notified like everyone else (the loop's actual code — iterate `findByThesis(thesis)` — was already count-agnostic and needed no logic change, only the comment was stale). Frontend: grep for `/3`, `members.length`, `memberCount`, `3 members` across all `.tsx` files found exactly one genuine stale assumption, `CommitteePage.tsx`'s `Committee: {memberCount}/3` (fixed, §9) — every other match was either my own new code, an unrelated Tailwind width class (`w-2/3`, `w-1/3`), or a `Skeleton` placeholder width. No other exact-3 assumption was found or left unfixed anywhere in either repo.

**14. Full backend suite.** `./mvnw.cmd -o test` → **Tests run: 338, Failures: 1, Errors: 0, Skipped: 0.** Baseline (documented in the 14-day-wait entry directly below) was 316; +22 = exactly the new tests listed in §11, all green. Re-ran the FULL suite a second time after the final comment-only `ScheduledTasksService.java` edit to confirm stability — same result both times (338/338 minus the one pre-existing failure), confirming determinism.

**15. The one remaining failure — confirmed pre-existing and unrelated.** `DefenseRequestIntegrationTest.doubleBooking_secondApprovalRejected_realStack:137` — `expected: <1> but was: <2>`, re-run in isolation and reproduced deterministically. This is the SAME environmental issue documented across many prior handoff entries in this file (a leftover non-cancelled `Defense` row in room "Shared Hall" in the live local Postgres DB from earlier manual/live testing sessions, outside any test's own transaction). This task's diff touches ZERO files under the defense-scheduling/room-booking surface (`DefenseServiceImpl`'s room/window logic, `DefenseRepository.findByRoomAndIsCancelledFalse`, `DefenseController`'s scheduling endpoints, `DefenseRequestIntegrationTest` itself) — confirmed by `git status` (§16). Per the task's explicit instruction, this was NOT "fixed" here — only documented, exactly as every prior entry that hit it has done.

**16. Working-tree verification.** `git status` before starting matched the CLAUDE.md-documented pre-existing uncommitted state exactly (the defense-request redesign, grade-5/`DEFENSE_FAILED`, mentor-capacity, 45-day-reminder, and 14-day-wait work from prior sessions — all `M`/`D`/`??` as already recorded in this file). This task's OWN changes are: `model/CommitteeMember.java`, `repository/CommitteeMemberRepository.java`, `dto/committee/CommitteeMemberResponse.java`, `dto/committee/ProposeCommitteeRequest.java`, `service/impl/CommitteeServiceImpl.java`, `service/impl/DefenseResultServiceImpl.java` (the `requireCommitteeSeat`→`requireVotingCommitteeSeat` section only — the rest of that file's diff predates this task, per §14 of the 14-day-wait entry and the grade-5/`DEFENSE_FAILED` entry), `service/DefenseRecordPdfService.java` (role-label distinction for the record PDF, §17), `service/ScheduledTasksService.java` (one comment, §13); test files `CommitteeCompositionValidationTest.java` (new), `CommitteeExternalMemberIntegrationTest.java` (new), `dto/committee/CommitteeMemberResponseSerializationTest.java` (new), plus targeted stub-mechanism updates to `DefenseResultGradingAuthorizationTest.java`, `DefenseGradeOutcomeTest.java`, `ArchiveMetadataTest.java`, `DefenseResultGradeValidationTest.java`, `DefenseResultServiceNotificationTest.java`, and the two new helper methods added to `AbstractWorkflowIntegrationTest.java`; frontend `types/api.ts`, `api/committeeApi.ts`, `features/committee/ProposeCommitteeModal.tsx`, `features/committee/CommitteeSection.tsx`, `pages/CommitteePage.tsx`, `features/defense/DefenseSection.tsx`. All the extensive pre-existing uncommitted work from prior sessions (defense-request redesign, mentor-capacity, grade-5/`DEFENSE_FAILED`, 45-day reminder, 14-day wait, and all their associated files in both repos) was left completely untouched beyond the specific lines listed above. No `git reset/restore/checkout/clean/stash` was run.

**17. `DefenseRecordPdfService` — one additional, in-scope decision (task §20).** The task explicitly allowed leaving the PDF alone unless it would falsely represent the external member as a voting member. The pre-existing role label ("Ментор" / "Член на комисија") does not literally claim voting rights, but printing the SAME generic "Член на комисија" label for a non-voting external professional would read as implying equal standing on an official signed record. Updated `roleLabel(CommitteeMember)` (was `roleLabel(MemberRole)`, now inspects the whole seat) to print "Надворешен член (без право на глас)" for `isExternalNonVoting=true` members, in both the committee table and the signature block — a minimal, targeted correction, not a PDF redesign. The "Статус" (defense-event-held/cancelled) field and the grade field are unrelated and untouched.

**18. Manual/live verification — honest status.** NOT performed as a browser walkthrough. A `java` process was already listening on port 8080 (started 8/27 19:00, well before this task's first edit and outside this task's control) — per this project's own safety guidance, an already-running process of uncertain ownership was not restarted or killed to avoid interfering with what could be the user's own session. Verification instead relies on the `CommitteeExternalMemberIntegrationTest` suite (§11), which is NOT a mock — it drives the real Spring MVC dispatcher, the real Spring Security filter chain (real JWT mint/verify), the real service/repository layers, and the real local Postgres `diploma_system` database, over real HTTP semantics (`MockMvc`), for every one of the task's required end-to-end scenarios (4-member committee proposed+approved, external member visible, external member rejected from grading with a real 403, a normal voting member grades successfully, the defense workflow continues normally through `DEFENSE_SCHEDULED`). This is the same evidentiary standard several prior entries in this file used in place of a live walkthrough (e.g. the P2.4 and TS5101 entries).

**19. Known limitations (documented, not fixed — explicitly out of scope for this task).**
- The external member's committee SEAT is not visually distinguished on `CommitteePage.tsx`'s overview cards (only on the full `CommitteeSection` detail view) — a minor UX polish item, not required by the task.
- `submitReviewNotes` (committee review-notes submission) was deliberately left completely unrestricted for the external member, per task §18's explicit instruction that only voting/grading is restricted — an external member can and should be able to participate in review.
- The pre-existing optional committee-member-conflict check and multi-instance double-approval race (both already documented as limitations in the defense-request-redesign entry) are unrelated to this task and remain as they were.

**20. Exact next task.** None from this task's own scope — STOP per the explicit instruction. Per the user's list of other pending official-faculty-procedure items, the one remaining candidate (do NOT start automatically) is: the deadline-extension rule. (The 4-member committee / external non-voting member item completed in this entry, the 45-day mentor-review reminder, and the 5-15 day defense scheduling window are already implemented — see the entries below.)

**21. Honesty notes.** ACTUALLY EXECUTED: every focused test group in §12 (individually and combined), the full 338-test suite (run twice for stability), the isolated re-run of the one pre-existing failing test, and `npm run build` (EXIT 0, 1852 modules, both before and after the `CommitteePage.tsx` fix). STATICALLY VERIFIED: `git status`/`git diff --stat` confirming the exact file set touched by this task (§16); the repo-wide exact-3 sweep (§13); that `requireVotingCommitteeSeat` runs before any mutation (read directly from the source, also proven by the zero-mutation assertions in both the unit and integration tests). NOT executed: a live browser/manual HTTP walkthrough against a freshly-restarted server process (§18 explains why, honestly, rather than fabricating one).

---

**14-day minimum wait before committee/defense request — DONE (2026-08-27). 🏁 Focused tests 61/61 (8 new + 50 existing re-verified + 3 more in existing files); full suite 316/316 minus 1 pre-existing/environmental failure; frontend `npm run build` EXIT 0.**

**1. Overall verdict.** FIXED / COMPLETE. A single, well-scoped official-faculty-procedure addition: the request that leads to committee formation / defense scheduling may only be submitted once at least 14 full days have elapsed since the formal thesis application was submitted. The recently completed defense-request redesign (`POST /api/theses/{id}/defenses/request`, `PATCH /api/theses/{id}/defenses/request/decision`) was NOT reverted or redesigned — a single guard was added at its one creation point. No other completed task (mentor capacity, DEFENSE_FAILED/grade-5, notification read/unread, 45-day mentor reminder, CORS, auth/JWT, room-conflict logic, the 5-15 day scheduledAt window, cancellation, grading, PDF generation, archive behavior, committee member count) was touched.

**2. Which operation is "the request" — investigated, not assumed.** Inspected both candidate operations before choosing:
- `CommitteeServiceImpl.proposeCommittee` — initiated by the assigned **MENTOR**, not the student, and never called a "request" anywhere in the code or this document. It happens right after `MENTOR_APPROVED`, long before any defense-eligibility check.
- `DefenseServiceImpl.createDefenseRequest` — the ONLY genuinely student-initiated "request" in the entire late-stage workflow (persisted as a `DefenseRequest` entity, named "request" throughout the code, docs, and DTOs). It is reached only after committee formation, committee review, and Student Service's explicit defense-eligibility verification (Item #8) have already happened.

Since forming the committee is a strict, mandatory prerequisite to ever reaching `createDefenseRequest`, gating the single downstream student "request" is sufficient to enforce the intent of the rule without duplicating a guard in an operation (`proposeCommittee`) that isn't a student request at all and that the task's own guidance said not to restrict unless it "clearly" represents that request. The gate lives in exactly one place: `DefenseServiceImpl.createDefenseRequest`.

**3. `Thesis.applicationSubmittedAt` — did not already exist; added.** Verified by reading `model/Thesis.java` in full before starting — no such field existed. Added:
```java
@Column(name = "application_submitted_at")
private OffsetDateTime applicationSubmittedAt;
```
placed next to `submissionDeadline`, nullable, no `ddl-auto` migration needed (Hibernate `update` adds it automatically — the same pattern as every other optional timestamp on this entity, e.g. `committeeReviewStartedAt`, `lastVersionSubmittedAt`). Also added to `ThesisResponse` (`dto/thesis/ThesisResponse.java`) as a plain passthrough field, purely so the frontend can compute a UX-only waiting notice — no other DTO changed.

**4. Exactly where and how it is set — `ThesisServiceImpl.submitApplication()`.** Inspected the full existing method first (guards role/ownership/status → deadline check → generates the application PDF → transitions to `PENDING_ARCHIVE_VALIDATION`). Added exactly one line, immediately after the PDF is generated and before the status transition:
```java
thesis.setApplicationSubmittedAt(OffsetDateTime.now());
```
`submitApplication()` is the SAME method used for both a fresh submission (from `APPLICATION_SUBMITTED`) and a resubmission after either archive/service rejection — the field is re-stamped on every successful call, since each is a genuine fresh formal submission of the application (mirrors how `lastVersionSubmittedAt` resets on every new version upload). It is **never** set at thesis creation (`createThesis`), and no other method in the codebase references it — confirmed by a full-repo search — so it is provably untouched by version uploads, comments, committee-member changes, or any other edit. A failed/rolled-back `submitApplication` call (e.g. the deadline-expired 400 path) never reaches this line, since it sits after that guard.

**5. The 14-day gate itself — `DefenseServiceImpl`.** New constant `MIN_DAYS_SINCE_APPLICATION = 14` and a new private helper, called in `createDefenseRequest` immediately after the existing `requireEligibleForProposal(thesis)` status check (i.e. AFTER role + ownership + status checks, so the authorization order documented in the defense-request redesign is completely preserved):
```java
requireEligibleForProposal(thesis);
requireApplicationAgeMet(thesis);
```
`requireApplicationAgeMet`: `eligibleAt = applicationSubmittedAt.plusDays(14)`; if `now.isBefore(eligibleAt)` → `BadRequestException` with the submission date and a ceiling-rounded "please wait N more day(s)" count (e.g. 12 hours remaining reads as "1 more day", never "0 more days"); otherwise the request proceeds exactly as before. A `null` `applicationSubmittedAt` (should be unreachable once a thesis reaches this stage, since every thesis passes through `submitApplication` first, but possible for corrupt/legacy data) is treated as **NOT eligible** and rejected with a clear business error — never silently bypassed. No other method (`decideDefenseRequest`, `cancelDefense`, `proposeCommittee`, `approveCommittee`, `submitReviewNotes`, `acceptCommitteeReview`, `verifyDefenseEligibility`) was touched — the approval step re-validates only the pre-existing room/window rules against the request's own `createdAt`, which never changes after creation, so no second age check is needed there.

**6. Exact timing semantics.** `OffsetDateTime#plusDays` is a fixed 24-hour-per-day duration add (no calendar/DST ambiguity, since `OffsetDateTime` carries a fixed offset) — matches the task's own worked example (submitted 2026-08-01 15:00 → eligible 2026-08-15 15:00, inclusive). Verified exactly at the boundary: 13 days elapsed → rejected; 13 days 23 hours → rejected; exactly 14 days → accepted; more than 14 days → accepted. A future/corrupt `applicationSubmittedAt` is handled without any special case — `eligibleAt` simply lands even further in the future, so `now.isBefore(eligibleAt)` is still correctly `true` (rejected).

**7. Authorization order preserved — no IDOR, no data leak.** `createDefenseRequest`'s existing order is `requireRole(STUDENT)` → `findThesis` (404) → ownership check (403) → `requireEligibleForProposal` (400) → **`requireApplicationAgeMet`** (400) → duplicate-pending check → field validation → window validation. The 14-day check runs strictly AFTER role and ownership, so a non-owner or wrong-role caller is rejected by the existing checks and never even reaches the age check — proven by a dedicated test (F) using a thesis that is deliberately still WITHIN the 14-day window, confirming the ownership failure fires first rather than an age-check message leaking timing information to an unauthorized caller.

**8. Interaction with committee workflow — none.** Per the task's explicit guidance, `proposeCommittee`, `approveCommittee`, `submitReviewNotes`, and `acceptCommitteeReview` were left completely untouched — none of them represents the student's request, and none was restricted.

**9. Frontend.** `types/api.ts` — added `applicationSubmittedAt: string | null` to `Thesis`. `features/defense/DefenseSection.tsx` — added a UX-only precheck: computes `applicationEligibleAt = applicationSubmittedAt + 14 days` client-side and, when a student is otherwise eligible to propose (`eligibleForProposal`) but the 14 days haven't elapsed, shows an amber waiting notice (Macedonian, matching the surrounding UI's language) with the exact eligible date and a "уште N ден(а)" remaining count, and hides the "Предложи нов термин" button until the wait is satisfied. `canPropose` now also requires `applicationWaitSatisfied`. The backend remains fully authoritative — the frontend calculation is a UX nicety only; a direct API call is still rejected by `requireApplicationAgeMet` regardless of what the frontend computes. No redesign; reused the existing amber-notice pattern already used for the Item #8 eligibility-check widget.

**10. Files changed.**
- `src/main/java/com/praksa/model/Thesis.java` — `+applicationSubmittedAt` field.
- `src/main/java/com/praksa/service/impl/ThesisServiceImpl.java` — stamp it in `submitApplication()`.
- `src/main/java/com/praksa/service/impl/DefenseServiceImpl.java` — `+MIN_DAYS_SINCE_APPLICATION` constant, `+requireApplicationAgeMet`, `+ceilDays` helper, `+Duration` import, one call site in `createDefenseRequest`.
- `src/main/java/com/praksa/dto/thesis/ThesisResponse.java` — `+applicationSubmittedAt` passthrough field.
- `src/test/java/com/praksa/service/DefenseRequestApplicationAgeTest.java` — **new**, 8 tests (A-F, H, I from the task's letter checklist).
- `src/test/java/com/praksa/service/DefenseRequestWorkflowTest.java` — the shared `thesis()` fixture helper now defaults `applicationSubmittedAt` to 30 days ago, so the existing 28 tests (none of which are about this rule) are unaffected by the new gate.
- `src/test/java/com/praksa/service/ThesisVersionUploadDeadlineTest.java` — `+1 test` (letter G: a version upload does not change `applicationSubmittedAt`).
- `src/test/java/com/praksa/service/ThesisCreditsDeadlineTest.java` — `+2 tests` (submitApplication stamps ~now; a resubmission re-stamps to a fresh value).
- `src/test/java/com/praksa/integration/AbstractWorkflowIntegrationTest.java` — the shared `submitApplication(...)` HTTP helper now backdates `applicationSubmittedAt` 30 days immediately after the real call succeeds (`+backdateApplicationSubmission` helper), exactly like the pre-existing committee auto-advance tests backdate `committeeReviewStartedAt` to simulate elapsed real time. Every integration test that reaches a defense request goes through this single helper, so this one change is sufficient for the whole suite.
- `praksa-frontend/src/types/api.ts` — `+applicationSubmittedAt` on `Thesis`.
- `praksa-frontend/src/features/defense/DefenseSection.tsx` — UX-only waiting notice + `canPropose` gate.
- `CLAUDE.md` — this entry + CURRENT NEXT STEP update.
- No other file touched — confirmed by `git status`/diff (see §14).

**11. Why the integration-test helper needed a fix mid-task.** The first full run surfaced a genuine regression: `ThesisWorkflowIntegrationTest.happyPath_fullLifecycle` calls `submitApplication(...)` directly (not via the `advanceToInProgress` compound helper) and then, milliseconds later in the same test method, calls `requestDefense(...)` — with the new gate in place this correctly returned 400 instead of 200, because in real wall-clock time the 14 days genuinely had not passed. This was NOT a false rejection — it was the new rule doing exactly what it should against a test that (before this task) had no reason to simulate elapsed time. Fixed by moving the backdating into the single low-level `submitApplication(...)` HTTP helper in `AbstractWorkflowIntegrationTest` (rather than only inside `advanceToInProgress`), so every test that calls it — directly or via any compound helper — gets 30 simulated days "elapsed" immediately after the real submission. Re-ran the full defense-request-adjacent integration suite afterward; all green except the one pre-existing failure below.

**12. Tests added.**
- `DefenseRequestApplicationAgeTest` (8, pure Mockito, no Spring context): (A) exactly 13 days → rejected; (B) 13 days + 23 hours → rejected; (C) exactly 14 days (with a small jitter-absorbing margin, mirroring the existing boundary-test style in `DefenseRequestWorkflowTest`) → accepted; (D) 30 days → accepted; (E) null `applicationSubmittedAt` → rejected, `defenseRequestRepository.save` never called (no silent bypass); (F) a non-owner student is rejected by the ownership guard even when the thesis IS within the 14-day window, proving check order; (H) once satisfied, `createDefenseRequest` still only creates a PENDING request with no `Defense` row and no status change, exactly as the pre-existing behavior; (I) a request submitted after the wait can still be rejected by Student Service with a reason, exactly as before.
- `ThesisVersionUploadDeadlineTest` (+1, letter G): a successful version upload leaves `applicationSubmittedAt` completely unchanged.
- `ThesisCreditsDeadlineTest` (+2): `submitApplication` stamps `applicationSubmittedAt` to within a `[before, after]` window around the call; a resubmission after `APPLICATION_REJECTED_BY_ARCHIVE` re-stamps a stale 40-day-old value to a fresh one.
- `DefenseRequestWorkflowTest` (0 new, 28 pre-existing re-verified green after the fixture default was added).

**13. Focused test results (real, JetBrains Runtime JDK 25 via `./mvnw.cmd -o`, project `--release 21`, live local Postgres `diploma_system`).**
`./mvnw.cmd -o -Dtest=DefenseRequestApplicationAgeTest,ThesisVersionUploadDeadlineTest,ThesisCreditsDeadlineTest,DefenseRequestWorkflowTest test` → **Tests run: 50, Failures: 0, Errors: 0, BUILD SUCCESS.**
`./mvnw.cmd -o -Dtest=DefenseRequestIntegrationTest,ThesisWorkflowIntegrationTest,AuthorizationWorkflowIntegrationTest,CommitteeWorkflowIntegrationTest,ArchiveNotesIntegrationTest test` → **Tests run: 31, Failures: 1** (see §15 — the one pre-existing environmental failure; all 30 others, including the re-fixed `ThesisWorkflowIntegrationTest`, pass).

**14. Full backend suite.** `./mvnw.cmd -o test` → **Tests run: 316, Failures: 1, Errors: 0, Skipped: 0.** Baseline (documented in the 45-day-reminder entry directly below) was 305; +11 = exactly the 8 new `DefenseRequestApplicationAgeTest` + 1 new `ThesisVersionUploadDeadlineTest` assertion + 2 new `ThesisCreditsDeadlineTest` assertions, all green.

**15. The one remaining failure — confirmed pre-existing and unrelated.** `DefenseRequestIntegrationTest.doubleBooking_secondApprovalRejected_realStack:137` — `expected: <1> but was: <2>` on `defenseRepository.findByRoomAndIsCancelledFalse("Shared Hall").size()`. This is the SAME environmental issue documented across multiple prior handoff entries in this file (a leftover non-cancelled `Defense` row in room "Shared Hall" in the live local Postgres DB from earlier manual/live HTTP testing of the defense-request redesign, outside any test's own transaction). This task's diff touches zero files under the room-availability/booking logic (`DefenseRepository.findByRoomAndIsCancelledFalse`, `requireRoomAvailable`, `DefenseController`'s scheduling endpoints) — confirmed by `git status`/diff (§10). Per the task's explicit instruction, this was NOT "fixed" by touching the defense-request implementation — only documented here, honestly, exactly as every prior entry that hit it has done.

**16. Frontend build.** `npm run build` (`tsc -b && vite build`) → **EXIT 0**, `✓ 1852 modules transformed` (same count as the pre-existing baseline — only existing files edited, no files added/removed). Zero TypeScript errors.

**17. Database impact.** One new nullable column, `theses.application_submitted_at` (`ddl-auto=update`, same mechanism as every other optional timestamp on this entity — no Flyway/Liquibase introduced). Existing rows get `NULL`; any such thesis is handled safely by the null-check in `requireApplicationAgeMet` (rejected with a clear error, never silently bypassed, never crashes) rather than being back-filled.

**18. Edge cases tested/verified.** Exactly 13 days (rejected), 13 days + 23 hours (rejected), exactly 14 days (accepted, with a jitter-absorbing margin), more than 14 days (accepted), `null` timestamp (rejected, not bypassed), a future/corrupt timestamp (handled without a special case — `eligibleAt` lands further in the future, still rejected), repeated request attempts (unaffected — the pre-existing "one PENDING request at a time" rule is unchanged and runs after the age check), non-owner/unauthorized caller (rejected by the pre-existing ownership guard before the age check ever runs), a version upload during `IN_PROGRESS` (does not reset the clock), and a resubmission after rejection (correctly DOES reset the clock, since it is a fresh formal submission).

**19. Known limitations (documented, not fixed — explicitly out of scope for this task).**
- `Thesis.applicationSubmittedAt` is `NULL` for every pre-existing thesis that already passed through `submitApplication()` before this feature was deployed; such a thesis is correctly rejected (not silently allowed) if it ever reaches `createDefenseRequest` — no back-fill was performed or needed for a school-scale project with no such theses currently at that stage.
- The optional committee-member-conflict check and the multi-instance double-approval race, both already documented as limitations in the defense-request-redesign entry below, are unrelated to this task and remain as they were.
- The frontend waiting-notice calculation duplicates the 14-day constant as a local `APPLICATION_WAIT_DAYS` in `DefenseSection.tsx` rather than fetching it from a config endpoint — acceptable for a fixed, rarely-changing faculty rule; the backend is the sole authority regardless.

**20. Exact next task.** None from this task's own scope — STOP per the explicit instruction. Per the user's list of other pending official-faculty-procedure items, the next candidates (do NOT start automatically) are: the 4-member committee / external non-voting member rule, and the deadline-extension rule. (The 45-day mentor-review reminder and the 5-15 day defense scheduling window are already implemented — see the entries below.)

**21. Honesty notes.** ACTUALLY EXECUTED: the 50-test focused unit run, the 31-test focused integration run, and the 316-test full suite (all via `./mvnw.cmd -o`, JBR JDK 25, live local Postgres `diploma_system`), plus `npm run build` (EXIT 0, 1852 modules). STATICALLY VERIFIED: `git status`/diff confirming the exact file set touched by this task (§10), and that `requireApplicationAgeMet` is called strictly after the existing role/ownership/status guards in `createDefenseRequest`. NOT executed: a live end-to-end HTTP walkthrough of the 14-day rejection message in a running application (covered instead by the 8 focused Mockito tests plus the real-HTTP integration suite, which exercises the exact same code path with backdated fixture data); a live browser check of the new frontend waiting notice (the frontend change is covered by the green `tsc`/`vite build`, consistent with how the analogous 45-day-reminder frontend label change was verified in the entry below).

---

**45-day mentor-review deadline reminder — DONE (2026-08-27). 🏁 Focused tests 10/10; full suite 305/305 minus 1 pre-existing/environmental failure; frontend `npm run build` EXIT 0.**

**1. Overall verdict.** FIXED / COMPLETE. A single, well-scoped official-faculty-procedure addition: the mentor must review a submitted thesis version and return it with comments within 45 days, or the mentor (and, implicitly, staff monitoring the notification) is reminded. No defense-request workflow, defense scheduling, grading, committee workflow, authentication, CORS, or notification read/unread behavior was touched.

**2. New field `Thesis.lastVersionSubmittedAt`.** Added to `model/Thesis.java` as `@Column(name = "last_version_submitted_at") private OffsetDateTime lastVersionSubmittedAt;` — plain nullable column, no `ddl-auto` migration needed (Hibernate `update` adds it automatically, same pattern as every other optional timestamp field on this entity, e.g. `committeeReviewStartedAt`). Represents the submission time of the MOST RECENT thesis version.

**3. `ThesisVersionServiceImpl.uploadVersion()` — exact change.** Inspected the full existing method first (validates role/ownership/status → computes next version number inside the transaction → stores the file on disk → `try { versionRepository.save(version) } catch { cleanup + rethrow }` → returns the DTO). Added exactly two lines immediately AFTER the `try/catch` block succeeds (i.e., only on a genuinely persisted version) and BEFORE the `return`:
```java
thesis.setLastVersionSubmittedAt(OffsetDateTime.now());
thesisRepository.save(thesis);
```
Because this sits after the catch block, a DB save failure (caught, file cleaned up, exception rethrown) never reaches these lines — the clock is untouched on a failed/rolled-back upload (proven by `ThesisVersionUploadDeadlineTest.uploadVersion_dbFailure_doesNotStampDeadline`). Nothing else in the method changed: version-number logic, file storage, PDF validation, `markAsFinal`/comments/read paths, status transitions, and the existing `FINAL_VERSION_SUBMITTED` notification are all untouched. Reading a version (`getVersions`/`downloadVersion`) never touches this field.

**4. New `NotificationType.MENTOR_REVIEW_DEADLINE_EXCEEDED`.** Added to `model/enums/NotificationType.java` (no reordering) with a subject/body describing that the latest submitted version has waited more than 45 days for mentor review and needs attention. Follows the exact same `(subject, defaultBody)` convention as every other type and travels through the existing `NotificationService.notify(recipient, thesis, type)` → `Notification` row (in-app + email) pipe, so it is automatically compatible with `isSent`/email delivery, `isRead`/unread, the P2.6 retry job, and the existing notification UI — none of that machinery was touched.

**5. Scheduled job — `ScheduledTasksService.remindMentorsOfOverdueReviews` (JOB 5).** Added after the existing JOB 4 (notification retry), following the exact same style as JOB 1/JOB 2 (`@Scheduled` + `@Transactional`, a repository query mocked/tested at the orchestration level, a `log.info`/`log.debug` pair, defensive re-checks inside the loop mirroring the query's own filters). Runs `@Scheduled(fixedDelay = 24h, initialDelay = 180s)` — once daily, sequenced after the other jobs' initial delays (60s/90s/120s/150s), consistent with "do not run every minute." Computes `cutoff = OffsetDateTime.now().minusDays(45)` and calls a new `ThesisRepository.findOverdueMentorReviews(cutoff)` query:
```java
SELECT t FROM Thesis t WHERE t.status = 'IN_PROGRESS'
  AND t.lastVersionSubmittedAt IS NOT NULL
  AND t.lastVersionSubmittedAt <= :cutoff
  AND t.mentor IS NOT NULL
```
For each returned thesis the loop re-checks (defensively, mirroring JOB 1's pattern): status is still `IN_PROGRESS`, `lastVersionSubmittedAt` is non-null, and a mentor is assigned — skipping otherwise (no crash, no notification). **Status is never changed** — the method contains no `transitionStatus`/`recordTransition` call of any kind; `IN_PROGRESS` stays `IN_PROGRESS`.

**6. Deduplication — exact mechanism (no new schema, no broad framework).** Before sending, the job calls a new derived query, `NotificationRepository.existsByThesisAndUserAndTypeAndCreatedAtAfter(thesis, mentor, "MENTOR_REVIEW_DEADLINE_EXCEEDED", thesis.getLastVersionSubmittedAt())`. If a notification of this type for this exact (thesis, mentor) pair already exists with `createdAt` AFTER the thesis's CURRENT `lastVersionSubmittedAt`, the reminder is skipped — that means a reminder for THIS submission cycle was already sent. Only existing `Notification` columns (`thesis`, `user`, `type`, `createdAt`) are used; no new table, no new boolean flag, no notification metadata field. This automatically solves the version-reset requirement: when the student uploads a new version, `lastVersionSubmittedAt` advances to a NEW, later timestamp; any OLD reminder was created BEFORE that new timestamp, so it no longer satisfies `createdAt > lastVersionSubmittedAt` and the dedup check correctly returns false — a fresh reminder becomes possible again once the new version itself sits unreviewed for another 45 days. Day-44→45→46 behavior: day 44 the query doesn't return the thesis (cutoff not reached) → nothing; day 45+ the query returns it, dedup check is false (no notification yet this cycle) → exactly one reminder sent; day 46 (and every later run) the query still returns it, but the dedup check is now true (the day-45 reminder exists and was created after `lastVersionSubmittedAt`) → skipped, no duplicate.

**7. Optional STUDENT_SERVICE notification — deliberately NOT implemented.** Per the task's explicit "do not introduce unnecessary complexity just to implement the optional notification" and "prioritize mentor notification, correct timing, no duplicate spam, correct reset" — only the mentor is notified. Documented here as a conscious scope decision, not an oversight; a future task could add a `notifyRole(STUDENT_SERVICE, thesis, ...)` fan-out alongside the mentor notify with no architecture change needed.

**8. Files changed.**
- `src/main/java/com/praksa/model/Thesis.java` — `+lastVersionSubmittedAt` field.
- `src/main/java/com/praksa/service/impl/ThesisVersionServiceImpl.java` — stamp the timestamp on successful upload; `+OffsetDateTime` import.
- `src/main/java/com/praksa/model/enums/NotificationType.java` — `+MENTOR_REVIEW_DEADLINE_EXCEEDED`.
- `src/main/java/com/praksa/repository/NotificationRepository.java` — `+existsByThesisAndUserAndTypeAndCreatedAtAfter` (+`Thesis` import).
- `src/main/java/com/praksa/repository/ThesisRepository.java` — `+findOverdueMentorReviews`.
- `src/main/java/com/praksa/service/ScheduledTasksService.java` — `+NotificationRepository` dependency, `+MENTOR_REVIEW_DEADLINE_DAYS` constant, `+remindMentorsOfOverdueReviews` (JOB 5).
- `src/test/java/com/praksa/service/MentorReviewDeadlineReminderTest.java` — **new**, 8 tests.
- `src/test/java/com/praksa/service/ThesisVersionUploadDeadlineTest.java` — **new**, 2 tests.
- `praksa-frontend/src/pages/NotificationsPage.tsx` — `+MENTOR_REVIEW_DEADLINE_EXCEEDED: 'Mentor Review Deadline Exceeded'` in the existing `typeLabels` map (the `?? notification.type` fallback already made this safe without any change, but the task asked to add a clean label where the mapping exists — done, matching the majority-English style already used for most other entries).
- `CLAUDE.md` — this entry + CURRENT NEXT STEP update.
- No other file touched. `types/api.ts` needed no change (`Notification.type` is already a generic `string`, not a union).

**9. Database impact.** One new nullable column, `theses.last_version_submitted_at` (`ddl-auto=update`, same mechanism as every other optional timestamp on this entity — no Flyway/Liquibase introduced). No column on `notifications` — dedup reuses existing columns via a derived query. Existing rows get `NULL`, which the job's `IS NOT NULL` filter and the defensive re-check both handle safely (never notified until a version is actually uploaded under this feature).

**10. API changes.** None. No new endpoint was added — this is a backend-only scheduled job, exactly as scoped ("no student can trigger the reminder" — there is no HTTP path to it at all).

**11. Security verification.** (a) Notification recipient is always `thesis.getMentor()`, resolved server-side from the thesis entity the scheduler itself queried — no user input is involved anywhere in this feature. (b) No new endpoint exists, so no student (or anyone) can trigger, delay, or suppress the reminder. (c) No cross-user leakage: the dedup query is scoped to the exact `(thesis, mentor)` pair via `existsByThesisAndUserAndTypeAndCreatedAtAfter(thesis, mentor, ...)` — it cannot match another mentor's or another thesis's notification. (d) Existing authorization is completely unchanged — `SecurityConfig`, `JwtAuthFilter`, and every controller's role guards were not touched; `uploadVersion`'s existing `requireRole(STUDENT)` + `requireOwner` + status guards run exactly as before, with the two new lines added only after all of them already passed.

**12. Tests added.**
- `MentorReviewDeadlineReminderTest` (8): (A) no `lastVersionSubmittedAt` → ignored, no notification, no dedup-repo interaction; (B) empty query result (simulating <45 days) → no-op, plus an `ArgumentCaptor` proof the cutoff passed to the query is ~`now-45days`; (C) overdue `IN_PROGRESS` thesis → mentor notified exactly once with `MENTOR_REVIEW_DEADLINE_EXCEEDED`; (D) a thesis returned by the (mocked) query but no longer `IN_PROGRESS` (e.g. `ARCHIVED`) → skipped by the defensive re-check, no notification; (F) the job run twice for the same cycle — dedup mock returns `false` then `true` — notifies exactly once across both runs; (G) a thesis representing the state AFTER a version reset (a newer `lastVersionSubmittedAt`, itself now 46 days stale) → the dedup check is proven (via `ArgumentCaptor`) to be evaluated against the CURRENT/new cycle start, and a fresh reminder is sent; (H) thesis with no mentor → handled safely, `assertDoesNotThrow`, no notification, no dedup-repo interaction; plus a baseline "empty overdue list → no-op" test.
- `ThesisVersionUploadDeadlineTest` (2, scenario E): a successful upload stamps `lastVersionSubmittedAt` to ~now (overwriting a stale prior value) and saves the thesis; a DB save failure during version persistence leaves the ORIGINAL `lastVersionSubmittedAt` untouched and never calls `thesisRepository.save`.
- Note on scenarios A/B/D: because the project has no embedded test database (no H2 in `pom.xml` — confirmed by inspection, consistent with every other scheduled-job test in this codebase, e.g. `AutoAdvanceCommitteeReviewTest`/`DefenseReminderNotificationTest`), the JPQL `WHERE` clause of `findOverdueMentorReviews` itself is not exercised by an executable test; these tests instead verify the SERVICE'S own defensive re-checks and cutoff computation, which is the same testing boundary every other scheduled job in this project uses.

**13. Focused test results (real, JetBrains Runtime JDK 25 via `./mvnw.cmd`, project `--release 21`, live local Postgres `diploma_system`).** `./mvnw.cmd -o -Dtest=MentorReviewDeadlineReminderTest,ThesisVersionUploadDeadlineTest test` → **Tests run: 10, Failures: 0, Errors: 0, BUILD SUCCESS.**

**14. Full backend suite.** `./mvnw.cmd -o test` → **Tests run: 305, Failures: 1, Errors: 0, Skipped: 0.** Baseline (documented in the "Grade 5 = DEFENSE_FAILED" entry directly below) was 295; +10 = exactly the 8 new `MentorReviewDeadlineReminderTest` + 2 new `ThesisVersionUploadDeadlineTest`, all green. The single failure is the SAME pre-existing, unrelated `DefenseRequestIntegrationTest.doubleBooking_secondApprovalRejected_realStack` environmental failure (`expected: <1> but was: <2>`, a leftover non-cancelled `Defense` row in room "Shared Hall" from earlier manual/live testing, documented in multiple prior entries below) — this task's `git diff` touches zero files under the defense-request/defense-scheduling/grading/committee/authentication/CORS/notification-read-unread surfaces (confirmed by `git status`, see §16).

**15. Frontend build.** `npm run build` (`tsc -b && vite build`) → **EXIT 0**, `✓ 1852 modules transformed` (same count as the pre-existing baseline — only an existing file, `NotificationsPage.tsx`, was edited; no files added/removed). Zero TypeScript errors.

**16. Working-tree verification.** `git status` before and after confirms this task touched exactly: `CLAUDE.md`, and backend — `model/Thesis.java`, `model/enums/NotificationType.java`, `repository/NotificationRepository.java`, `repository/ThesisRepository.java`, `service/ScheduledTasksService.java`, `service/impl/ThesisVersionServiceImpl.java`, plus two new test files. Frontend — `pages/NotificationsPage.tsx` only. All the extensive pre-existing uncommitted work from prior sessions (the defense-request redesign, mentor-capacity change, grade-5/DEFENSE_FAILED change, and their associated new/modified files in both repos) was left completely untouched — confirmed by diffing the file list against the pre-task `git status` snapshot. No destructive git command was run.

**17. Known limitations (documented, not fixed — explicitly out of scope for this task).**
- The JPQL `WHERE` clause of `findOverdueMentorReviews` is not exercised by an executable test in this environment (no embedded test DB) — consistent with every other scheduled-job query in this project (`findStaleCommitteeReviews`, `findUpcomingForReminder`, `findExpiredPendingApplications` are all in the same position).
- The optional STUDENT_SERVICE notification (task item 7) was intentionally not implemented — mentor-only, per the task's explicit prioritization.
- `Thesis.lastVersionSubmittedAt` is `NULL` for every pre-existing thesis until its student uploads a new version under this feature; such theses are correctly never picked up by the job until then (no back-fill was performed or needed).
- Like the pre-existing `sendDefenseReminders`/`autoAdvanceStaleCommitteeReviews` jobs, this job assumes single-instance deployment; multi-instance duplicate-notification protection would need a shared lock (e.g. ShedLock), consistent with the project's already-documented P2.6 limitation — not introduced here.

**18. Exact next task.** None from this task's own scope — STOP per the explicit instruction. Per the user's list of other pending official-faculty-procedure items, the next candidates (do NOT start automatically) are: the 14-day minimum before a committee/defense request, and the 4-member committee / external non-voting member rule, and the deadline-extension rule. (The "5-15 day defense scheduling validation" item was already implemented in the defense-request-redesign entry below — re-verified still present and unrelated to this task.)

**19. Honesty notes.** ACTUALLY EXECUTED: the 10-test focused run and the 305-test full suite (both via `./mvnw.cmd -o`, JBR JDK 25, live local Postgres `diploma_system`), and `npm run build` (EXIT 0, 1852 modules). STATICALLY VERIFIED: `git status`/diff confirming the exact file set touched by this task, and that the two-line timestamp-stamp in `uploadVersion` sits strictly after the version-save `try/catch` (so a failed upload cannot stamp the clock). NOT executed: a live end-to-end walkthrough of the daily job actually firing in a running application (the job's own orchestration logic is covered by the 8 Mockito tests instead, matching how every other scheduled job in this project is tested); a live browser check of the new notification label (the frontend change is a one-line label-map addition, covered by the green `tsc`/`vite build`).

---

**Grade 5 = DEFENSE_FAILED, not ARCHIVED — DONE (2026-08-27). 🏁 Focused tests 47/47 (9+8+38 across new/updated files); full suite 295/295 minus 1 pre-existing/environmental failure; frontend `npm run build` EXIT 0.**

**1. Overall verdict.** FIXED / COMPLETE. A single, well-scoped official-faculty-procedure correction, exactly as scoped: grade 5 now means "not defended" and produces a new terminal status (`DEFENSE_FAILED`) instead of `ARCHIVED`; grades 6-10 are completely unchanged. The defense-request workflow, mentor-capacity rule, and every other prior completed task were left untouched.

**2. New `ThesisStatus.DEFENSE_FAILED`.** Added between `DEFENSE_SCHEDULED` and `ARCHIVED` in `model/enums/ThesisStatus.java` (no reordering of existing values — `@Enumerated(EnumType.STRING)` on `Thesis.status` means ordinal position is irrelevant to persistence anyway, verified against the entity before adding). Produced ONLY by `DefenseResultServiceImpl.recordResult` on a grade-5 result, via the existing `transitionStatus()` helper (same audit/history mechanism as every other transition — no bypass). No other service, controller, or scheduled job can produce it.

**3. Grade 5 behavior (`DefenseResultServiceImpl.recordResult`).** The method now saves the `DefenseResult` (grade preserved, exactly as before) and then branches:
```java
if (request.getGrade() == FAILING_GRADE) {          // FAILING_GRADE = 5
    recordDefenseFailure(thesis, recorder);          // DEFENSE_FAILED, no archive metadata, 1 notification
} else {
    recordSuccessfulArchive(thesis, recorder, result); // unchanged 6-10 path: archive metadata + ARCHIVED + 2 notifications
}
```
`recordDefenseFailure`: calls `transitionStatus(thesis, DEFENSE_FAILED, recorder)` (writes the `ThesisStatusHistory` row `DEFENSE_SCHEDULED → DEFENSE_FAILED`) and sends exactly ONE notification — the new `NotificationType.DEFENSE_FAILED_CAN_REAPPLY` — to the student. It does NOT touch `archiveRegistrationNumber`/`archiveDate`/`archivedBy`/`archiveNotes`, does NOT call the registration-number generator, and does NOT send `THESIS_GRADED`/`THESIS_ARCHIVED`. The `Defense` row, the `DefenseResult` row (grade=5, notes, recordedBy), and all prior committee/version/status history are completely preserved — nothing is deleted or rewritten.

**4. Grade 6-10 behavior.** Byte-for-byte unchanged: `recordSuccessfulArchive` is the exact same code that previously lived inline in `recordResult` (archive metadata assignment → `transitionStatus(..., ARCHIVED, ...)` → `THESIS_GRADED` + `THESIS_ARCHIVED` notifications), just extracted into its own method. Verified by the full pre-existing `ArchiveMetadataTest` (6 tests, all still pass unmodified except a one-line Javadoc pointer added) and `DefenseResultServiceNotificationTest`/`DefenseResultGradingAuthorizationTest` (unmodified, all pass).

**5. Archive metadata invariants (grade 5).** Proven by `DefenseGradeOutcomeTest.grade5_noArchiveMetadata`: `archiveRegistrationNumber`, `archiveDate`, `archivedBy`, and `archiveNotes` all stay `null`, and `thesisRepository.countByArchiveRegistrationNumberStartingWith(...)` (the registration-number generator's only DB call) is verified `never()` invoked for a failed defense — so a failed defense can never accidentally consume a sequence number. Grade 6 and grade 10 are each separately proven to populate the full metadata set exactly as before (`grade6_archivesWithMetadata`, `grade10_archivesWithMetadata`).

**6. Reapplication / active-thesis rule.** The existing one-active-thesis rule lives entirely in one place — `ThesisServiceImpl.createThesis` calls a private `isActiveStatus(ThesisStatus)` helper (already used to exclude `ARCHIVED` and `ELIGIBILITY_REJECTED` from a prior task). `DEFENSE_FAILED` was added to that same exclusion list:
```java
private boolean isActiveStatus(ThesisStatus status) {
    return status != ThesisStatus.ARCHIVED
            && status != ThesisStatus.ELIGIBILITY_REJECTED
            && status != ThesisStatus.DEFENSE_FAILED;
}
```
Every OTHER entry point that could theoretically gate on "does the student have an active thesis" was inspected — `submitMentorRequest`, `submitApplication`, `decideEligibility`, `reviseProposal`, etc. — and none of them independently re-check "does this student have another thesis"; the ONLY gate is `createThesis`'s single `isActiveStatus` filter over `findByStudent(student)`. So updating this one helper is the complete, suffient fix — no scattered special-casing was needed or added, matching the task's explicit preference for "an explicit, readable active-status definition" over duplicated logic. The `ThesisRepository.countActiveMentorTheses` query (`status != 'ARCHIVED'`, mentor-capacity limit — a DIFFERENT business rule, "how many students is this mentor currently supervising") was deliberately left untouched: the task scoped this change to the student's one-active-thesis rule, not mentor capacity, and the task's own item 13 explicitly warns "do NOT blindly replace every ARCHIVED reference." Documented here as a conscious, in-scope decision, not an oversight — a DEFENSE_FAILED thesis still counts toward its mentor's 15-slot cap until/unless a future task decides otherwise.

**7. Reuses the existing `createThesis` flow — no new endpoint.** Per the task's explicit preference ("prefer that rather than adding unnecessary endpoints"), reapplication after a failed defense is simply: the student calls the SAME `POST /api/theses` they'd use for a first-time application (already reachable from `/theses/new` in the frontend, with no client-side gating on prior-thesis status). The old `DEFENSE_FAILED` row is never mutated, deleted, or silently re-statused — it stays exactly as historical/audit data, proven in `DefenseFailedReapplicationTest.create_withDefenseFailedThesis_oldRowUnchanged` (old row's id/status unchanged in memory, never re-saved/deleted, exactly one NEW history row written for the NEW thesis). No automatic status transition happens just from viewing the failed thesis's detail page — the frontend banner only offers a link to the existing `/theses/new` page; ownership/authorization on that page (`requireRole(STUDENT)` + the 200-credit gate) are completely unchanged.

**8. Active-thesis rule NOT weakened elsewhere.** `DefenseFailedReapplicationTest.create_withActiveThesis_rejected` (an `IN_PROGRESS` thesis still blocks) and `create_withActiveAndDefenseFailed_rejected` (an active thesis alongside a `DEFENSE_FAILED` one still blocks) both pass, proving every genuinely active status still enforces the rule exactly as before. The real-stack integration test additionally proves this end-to-end: after a student reapplies post-failure, a THIRD `createThesis` attempt against their own brand-new (`PENDING_ELIGIBILITY_CHECK`) thesis is correctly rejected with `400`.

**9. Notification.** New `NotificationType.DEFENSE_FAILED_CAN_REAPPLY` ("Defense Not Passed" / a default body explaining the thesis was not successfully defended and the student may rework the topic or submit a new application), added at the end of the enum alongside `THESIS_GRADED`/`THESIS_ARCHIVED`, following the exact same `(subject, defaultBody)` constructor convention as every other type — no enum reordering. Sent via the same `NotificationService.notify(recipient, thesis, type, customMessage)` 4-arg overload used everywhere else (a primitive `String` custom message; no entity crosses the `@Async` email boundary). It participates in the pre-existing `isSent`/`sent`, `isRead`/`read`, async-email, and retry (P2.6) machinery completely unchanged — nothing about `NotificationServiceImpl`, `EmailService`, or `ScheduledTasksService.retryUnsentNotifications` was touched; the new type is just another row through the same pipe. `DefenseGradeOutcomeTest.grade5_sendsExactlyOneNotification` proves EXACTLY one `notify(...)` call happens for a failed defense (no `THESIS_GRADED`, no `THESIS_ARCHIVED`, no duplicate).

**10. Frontend changes.**
- `types/api.ts` — `'DEFENSE_FAILED'` added to the `ThesisStatus` union.
- `components/ui/StatusBadge.tsx` — new entry: red styling, Macedonian label `"Одбраната не е положена"`.
- `pages/NotificationsPage.tsx` — `typeLabels['DEFENSE_FAILED_CAN_REAPPLY'] = 'Одбраната не е положена'`.
- `pages/ThesisDetailPage.tsx` — `DEFENSE_FAILED` added to the `versionsVisible`/`committeeVisible`/`defenseVisible` section-visibility conditions (alongside `ARCHIVED`) so the failed thesis's history stays viewable; a new red "Defense Failed" banner card (mirrors the existing "Official Archive Record" card's layout/pattern) explains the outcome and — for the owning student only — offers a "Start a New Thesis Application" button linking to `/theses/new`. `hasAnyAction` was deliberately NOT changed — there is no in-page workflow action for `DEFENSE_FAILED` (reapplication is a NEW thesis, not an action on this one), so "No actions available at this stage" correctly still shows alongside the banner.
- `features/defense/DefenseSection.tsx` — the single green "Grade: X" result box was split into two branches: `result.grade === 5` renders a RED box ("Grade: 5 — Одбраната не е положена", explains it was NOT archived, offers the same reapply guidance) instead of the green "successfully defended" box used for grades 6-10 (unchanged). `canRecordGrade`/`eligibleForProposal`/`canPropose` were NOT touched — they already gate on `thesis.status === 'DEFENSE_SCHEDULED'`, which is automatically false once EITHER outcome status is reached, so no double-grading and no stray "propose a new defense on this failed thesis" path was possible or introduced.
- `features/defense/RecordGradeModal.tsx` — the grade label turns red and the modal description/submit-toast text branches on `grade === 5` ("Grade 5 means the defense was NOT passed — the thesis will NOT be archived" / "Grade 5 recorded — the defense was not passed; the student may reapply") vs. the unchanged 6-10 copy; a small hint line under the slider spells out "5 = Одбраната не е положена" vs "6–10 = successfully defended."
- `pages/DefensesPage.tsx` — the "Grade: X" pill on each defense card is now red with "— Не положена" when `result.grade === 5`, green otherwise (unchanged for 6-10).
- No redesign of any page; every change is additive/branching on the existing render paths.

**11. API/DTO compatibility.** No DTO changed. `ThesisResponse.status` already serializes the raw `ThesisStatus` enum name via Jackson, so `"DEFENSE_FAILED"` travels backend → JSON → frontend with zero mapping code — exactly like every other status. `RecordResultRequest`/`DefenseResultResponse` are untouched (grade is still a plain `Integer` 5-10; the OUTCOME is derived from the grade value, not carried as a separate field).

**12. Tests added/updated.**
- **NEW** `src/test/java/com/praksa/service/DefenseGradeOutcomeTest.java` (9 tests) — grade 5: transitions to `DEFENSE_FAILED`; no archive metadata (+ registration-number generator never consulted); `DefenseResult` persisted with grade 5; exactly one `ThesisStatusHistory` row `DEFENSE_SCHEDULED→DEFENSE_FAILED`; exactly one notification (`DEFENSE_FAILED_CAN_REAPPLY`, no `THESIS_GRADED`/`THESIS_ARCHIVED`). Grade 6 and grade 10: `ARCHIVED` with full metadata, both archive notifications, no `DEFENSE_FAILED_CAN_REAPPLY`. Authorization: an unseated committee-role user and the STUDENT owner both still get `403` on a grade-5 attempt (proves the committee-seat guard runs before, and is unaffected by, the new outcome branch).
- **NEW** `src/test/java/com/praksa/service/DefenseFailedReapplicationTest.java` (8 tests) — deliberately mirrors `EligibilityRejectedNewThesisTest`'s structure (same `isActiveStatus` rule, a different excluded status): a student whose only thesis is `DEFENSE_FAILED` can create a new one; the old row is left byte-for-byte untouched (id/status unchanged, never re-saved/deleted, exactly 1 new history row); the new thesis still gets `createdAt`/`submissionDeadline` correctly; an `IN_PROGRESS` thesis (and an active thesis alongside a `DEFENSE_FAILED` one) still blocks; the 200-credit gate still applies; a pre-existing `ARCHIVED` thesis still behaves as before (regression guard); a non-STUDENT caller is still rejected (authorization unchanged).
- **UPDATED** `src/test/java/com/praksa/service/DefenseResultGradeValidationTest.java` — `grade5_accepted` was asserting the OLD behavior (`ThesisStatus.ARCHIVED`, a non-null registration number, `THESIS_GRADED`/`THESIS_ARCHIVED` sent) — this was factually wrong after this change and has been rewritten to assert `DEFENSE_FAILED`, a null registration number, and `DEFENSE_FAILED_CAN_REAPPLY` sent instead. `grade10_accepted` (and the shared `assertValidGradeArchives` helper, now explicitly scoped to "successful (6-10)" in its Javadoc) are unchanged. The class-level purpose (5-10 RANGE validation, not outcome branching) is preserved — full outcome-branching detail lives in the new `DefenseGradeOutcomeTest`.
- **UPDATED** `src/test/java/com/praksa/integration/ThesisWorkflowIntegrationTest.java` — added **TEST 4**, a real `@SpringBootTest`/`@AutoConfigureMockMvc` test (real security chain, real local Postgres, nothing mocked except SMTP) that drives a thesis all the way to `DEFENSE_SCHEDULED` via `advanceToDefenseScheduled`, records grade 5 over real HTTP, and asserts: `DEFENSE_FAILED` status; null registration number/date; the `DefenseResult` (grade 5) and `Defense` row both persisted; exactly one `DEFENSE_FAILED_CAN_REAPPLY` notification and zero `THESIS_GRADED`/`THESIS_ARCHIVED`; the SAME student can immediately reapply (`createThesis` succeeds, old row untouched); and a THIRD `createThesis` attempt against the student's own new active thesis is correctly rejected (`400`) — proving the rule is narrowed, not disabled.
- **UPDATED** `src/test/java/com/praksa/service/ArchiveMetadataTest.java` — class Javadoc only, pointing readers to `DefenseGradeOutcomeTest` for the grade-5 (null-metadata) counterpart; all 6 existing tests (grades 6-10) untouched and still pass.

**13. Focused test results (real, JetBrains Runtime JDK 25 via `./mvnw.cmd`, project `--release 21`, live local Postgres `diploma_system`).**
`./mvnw.cmd -o -Dtest=DefenseGradeOutcomeTest,DefenseFailedReapplicationTest,DefenseResultGradeValidationTest,ArchiveMetadataTest,DefenseResultServiceNotificationTest,DefenseResultGradingAuthorizationTest test` → **Tests run: 38, Failures: 0, Errors: 0, BUILD SUCCESS.**
`./mvnw.cmd -o -Dtest=ThesisWorkflowIntegrationTest test` → **Tests run: 4, Failures: 0, Errors: 0, BUILD SUCCESS** (includes the new real-stack grade-5 test alongside the 3 pre-existing happy-path/revision/rejection tests).

**14. Full backend suite.** `./mvnw.cmd -o test` → **Tests run: 295, Failures: 1, Errors: 0, Skipped: 0.** Baseline (documented in the mentor-capacity entry directly below) was 277; +18 = exactly the 9 new `DefenseGradeOutcomeTest` + 8 new `DefenseFailedReapplicationTest` + 1 new integration test in `ThesisWorkflowIntegrationTest`, all green. The single failure is the SAME `DefenseRequestIntegrationTest.doubleBooking_secondApprovalRejected_realStack` **pre-existing, unrelated** environmental issue already documented in the mentor-capacity entry below (a leftover non-cancelled `Defense` row in room "Shared Hall" from earlier manual/live testing) — re-confirmed deterministic by re-running that single test in isolation (`-Dtest=DefenseRequestIntegrationTest` → same `expected: <1> but was: <2>` failure, `6` tests, `1` failure). This task's `git diff` touches zero files under `service/impl/DefenseServiceImpl.java`'s request/room logic, `DefenseRequestRepository`, or `DefenseController`'s scheduling endpoints — confirmed by `git diff --stat` scoped to exactly the 8 files this task changed (see §15).

**15. Frontend build.** `npm run build` (`tsc -b && vite build`) → **EXIT 0**, `✓ 1852 modules transformed` (same count as before — only existing files edited, no files added/removed by this task). Zero TypeScript errors.

**16. Search results for stale grade/archive assumptions (task item 13).** Repo-wide grep for `grade >= 5`, `grade > 4`, `every...result...archiv`, `status != ARCHIVED` / `status !== 'ARCHIVED'` across both repos, run AFTER the fix: the only remaining `status != 'ARCHIVED'`-shaped reference in production code is `ThesisRepository.countActiveMentorTheses` (the mentor-capacity query — a deliberately out-of-scope, different rule; see §6). No frontend file used a negated-`ARCHIVED` assumption (the frontend already used explicit positive `status === 'X' || status === 'Y'` lists everywhere, so nothing needed a "meaning active" fix). `DefenseRecordPdfService`'s "Статус" field (`defense.isCancelled() ? "Откажано" : "Одбрането"`) refers to whether the DEFENSE EVENT was held/cancelled, not the grade outcome — left unchanged as a cosmetic, explicitly-out-of-scope item (noted in §19); the grade itself (5) is printed clearly in the PDF's "Оценка" field regardless.

**17. Security / authorization verification.** No authorization logic was touched. `DefenseGradeOutcomeTest.grade5_unseatedCaller_rejected` and `.grade5_studentOwner_rejected` both prove the pre-existing `requireCommitteeSeat` guard (write-side grading IDOR fix, BUG-13) runs identically regardless of the grade being recorded — a caller who is not a seated committee member of THIS thesis still gets `403` with zero mutations, for grade 5 exactly as for grade 6-10. `createThesis`'s `requireRole(STUDENT)` check and the 200-credit gate are completely unchanged and still run before the (now three-way) `isActiveStatus` filter. No new endpoint, no new role, no new permission surface was introduced.

**18. Database impact.** None beyond the new enum value. The project uses Hibernate `ddl-auto=update` with `@Enumerated(EnumType.STRING)` on `Thesis.status` — the column is a plain `varchar`, so a new enum constant needs no migration and no schema change at all (verified against the entity before making the change, per the task's explicit instruction not to introduce Flyway/Liquibase for this). Existing `ARCHIVED` rows are completely unaffected — the branching only applies going forward, inside `recordResult`, and no existing row's status/metadata was touched by this change. A `DEFENSE_FAILED` row can never receive an archive registration number (proven in §5), so the per-year `DT-YYYY-NNNN` sequence integrity for real archived rows is unaffected.

**19. Known limitations (documented, not fixed — explicitly out of scope for this task).**
- `ThesisRepository.countActiveMentorTheses` (mentor's 15-slot capacity) still counts a `DEFENSE_FAILED` thesis as active — see §6 for the reasoning. A future task could decide whether a failed defense should free the mentor's slot.
- `DefenseRecordPdfService`'s "Статус" (event status) field still prints "Одбрането" (defended/held) for a failed defense — it refers to whether the defense event took place, not the grade outcome, and the grade (5) is separately, clearly printed in the "Оценка" row. Not changed per the task's explicit "do not redesign" instruction and because it wasn't called out in the task's frontend/PDF scope.
- `DefenseSection.tsx`'s "Cancel Defense" button visibility (`canCancel`) has no status check beyond "an active, non-cancelled Defense row exists" — this PRE-EXISTING gap (already present for `ARCHIVED` theses before this task) now also technically applies to `DEFENSE_FAILED` theses. Not introduced by this task and not fixed here, per the instruction to avoid unnecessary changes to the already-completed defense-request workflow; a future cleanup could gate `canCancel`/`cancelDefense` on the thesis not yet having a final outcome.

**20. Exact next task.** None from this task's own scope — STOP per the explicit instruction. Per the user's list of other pending faculty-procedure items, the next candidates (do NOT start automatically) are: the 45-day mentor review reminder, the 14-day minimum before a committee/defense request, the 4-member committee / external non-voting member rule, and the deadline-extension rule. Note: the "5-15 day defense scheduling validation" item in the user's own do-not-start list appears to already be implemented (see the defense-request-redesign entry below, §5 — "The 5-15 day rule") — a future session should re-verify against current source before assuming it's still pending, rather than trusting this note alone.

---

**Mentor active-thesis capacity raised 10 → 15 — DONE (2026-08-27). 🏁 Focused tests 3/3; full suite 276/277 passing (the 1 failure is pre-existing/environmental, unrelated to this change — see below).**

**1. Overall verdict.** FIXED / COMPLETE. A single, well-scoped business-rule correction. Old limit: a mentor could supervise **at most 10** active theses. New limit: **at most 15**, per the official faculty procedure. No other behavior, workflow, or unrelated code was touched.

**2. Exact business rule (before → after).** `ThesisServiceImpl.submitMentorRequest` (the only enforcement point — verified by repo-wide search of `countActiveMentorTheses`/`activeMentorCount`):
```java
// before
if (activeMentorCount >= 10) {
    throw new BadRequestException("This mentor already has 10 active theses and cannot accept more");
}
// after
if (activeMentorCount >= 15) {
    throw new BadRequestException("This mentor already has 15 active theses and cannot accept more");
}
```
"Active" is unchanged and was NOT redefined: `ThesisRepository.countActiveMentorTheses` = `COUNT(t) WHERE t.mentor = :mentor AND t.status != 'ARCHIVED'` — the same definition already used everywhere else in the app. The error mechanism is unchanged: `BadRequestException` → `GlobalExceptionHandler.handleBadRequest` → HTTP 400 with the existing `ApiResponse.error(...)` envelope (no change to `GlobalExceptionHandler`'s behavior, only a stale illustrative comment).

**3. Exact files changed.**
- `src/main/java/com/praksa/service/impl/ThesisServiceImpl.java` — the `if (activeMentorCount >= 10)` guard + message → 15 (2 lines); a nearby comment ("...counts toward 10-active") → 15-active.
- `src/main/java/com/praksa/controller/ThesisController.java` — Swagger `@Operation` description on `submitMentorRequest` ("Mentor must have fewer than 10 active theses...") → 15.
- `src/main/java/com/praksa/exception/GlobalExceptionHandler.java` — one illustrative comment ("e.g. mentor already has 10 theses") → 15. No handler logic changed.
- `src/test/java/com/praksa/service/MentorCapacityLimitTest.java` — **new**, 3 Mockito unit tests (see §4).
- `CLAUDE.md` — this entry + CURRENT NEXT STEP update.
- No other file in either repo referenced the mentor-10 limit (confirmed by a repo-wide grep sweep — see §8). No frontend file changed (no frontend text mentioned the number).

**4. Tests added.** `MentorCapacityLimitTest` (pure Mockito, mirrors the existing `ThesisCreditsDeadlineTest` style — `@InjectMocks ThesisServiceImpl` with mocked `ThesisRepository`/`UserRepository`/`SecurityUtils`/`NotificationService`/`ThesisStatusHistoryRepository`):
- `mentor_with14ActiveTheses_canAcceptAnother` — `countActiveMentorTheses` stubbed to 14 → `submitMentorRequest` succeeds, thesis transitions to `PENDING_MENTOR_APPROVAL`, mentor assigned, `thesisRepository.save` + the `MENTOR_REQUEST_RECEIVED` notification both fire.
- `mentor_with15ActiveTheses_cannotAcceptAnother` — stubbed to 15 → `BadRequestException` with the exact new message; thesis mentor stays null, status stays `TOPIC_SELECTION`, `save` and the notification are never called.
- `mentor_withMoreThan15ActiveTheses_rejected` — stubbed to 16 → also rejected (boundary robustness beyond the two required cases).
No pre-existing test covered this rule at all before this task (confirmed by search — `submitMentorRequest`/`countActiveMentorTheses`/mentor-capacity had zero dedicated test coverage), so this is net-new coverage, not a modification of an existing test.

**5. Focused test results (real, JetBrains Runtime JDK 25 via `./mvnw.cmd`, project `--release 21`, live local Postgres).** `./mvnw.cmd -o -Dtest=MentorCapacityLimitTest test` → **Tests run: 3, Failures: 0, Errors: 0, BUILD SUCCESS.**

**6. Full backend suite.** `./mvnw.cmd -o test` → **Tests run: 277, Failures: 1, Errors: 0, Skipped: 0.** Baseline (documented in the entry directly below) was 274/274; +3 are exactly the new mentor-capacity tests, all green. The single failure — `DefenseRequestIntegrationTest.doubleBooking_secondApprovalRejected_realStack` (asserts exactly 1 non-cancelled `Defense` in room "Shared Hall"; got 2) — is **pre-existing and environmental, not caused by this task**:
   - This task touched zero defense/room/`DefenseRequest`-related files (confirmed by `git diff` — only `ThesisServiceImpl`, `ThesisController`, `GlobalExceptionHandler`, plus the new mentor test).
   - A direct read-only `psql` query against the live local `diploma_system` DB found a genuine, non-cancelled `Defense` row already sitting in room "Shared Hall" (`created_at` well before this task's test runs started) — leftover data from earlier manual/live testing of the just-completed defense-request redesign (that work's own CLAUDE.md entry documents driving a live HTTP smoke test through exactly this room name). The test's assertion queries the room globally (`findByRoomAndIsCancelledFalse("Shared Hall")`) rather than scoping to the theses it creates, so it trips over any leftover row from outside its own transaction.
   - Re-ran the test in isolation (`-Dtest=DefenseRequestIntegrationTest`) — same deterministic failure, consistent with stale DB state rather than a flaky race.
   - Per this task's explicit instructions ("Do NOT modify the defense-request workflow that was just completed," "Do NOT refactor unrelated code"), this was left untouched and only documented here, not fixed.

**7. Frontend.** No frontend file changed. Searched `praksa-frontend/src` for any mentor-capacity-related text ("10 active", "fewer than 10", "already has 10", "mentor limit/capacity/workload") — zero matches. The mentor-request rejection message is surfaced purely via the existing axios interceptor's generic 400-message toast (`MentorPickerModal.tsx` calls `thesisApi.submitMentorRequest` and relies on the interceptor for error display), so no hardcoded "10" existed on the frontend to update. `npm run build` was therefore NOT run (no frontend change to verify).

**8. Search results — stale "10 active theses" references.** Repo-wide case-insensitive grep for `10 active thes`, `already has 10`, `fewer than 10`, `max...10...thes`, `>= 10` across `*.java`/`*.ts`/`*.tsx`/`*.md`/`*.properties` in both `praksa/src` and `praksa-frontend/src`, run AFTER the fix — **zero matches**. The one other pre-existing "10" in the backend (`DefenseResultServiceImpl.MAX_DEFENSE_GRADE = 10`, the unrelated 5–10 defense-grade range) was inspected and correctly left untouched — it has nothing to do with the mentor limit.

**9. Security / authorization impact.** None. The change is a pure numeric threshold + message-text change inside an already-existing, already-authorized code path (`submitMentorRequest` still requires `Role.STUDENT` + thesis ownership + target `Role.MENTOR`, all unchanged). No new endpoint, no new role, no authorization logic touched, no exception-handling behavior changed (still `BadRequestException` → HTTP 400 via the existing `GlobalExceptionHandler`).

**10. Confirmation — no unrelated functionality changed.** `git diff` on the three touched production files shows exactly the 10→15 lines described in §2/§3 and nothing else. All the extensive pre-existing uncommitted work from the just-completed defense-request redesign (`DefenseController`, `DefenseService`/`DefenseServiceImpl`, `Defense.java`, `DefenseRepository`, `NotificationType`, the new `DefenseRequest*` files, the defense-related test files, etc. — all present in `git status` before this task started) was left byte-for-byte untouched. No destructive git command was run.

**11. CLAUDE.md.** This entry added; CURRENT NEXT STEP rewritten to point here. No historical entries rewritten.

**12. Remaining known issues (pre-existing, not introduced or fixed by this task).** The `DefenseRequestIntegrationTest.doubleBooking_secondApprovalRejected_realStack` environmental failure (§6) — recommend a future task either scope that assertion to the theses it creates instead of querying the room globally, or clear stray non-cancelled `Defense` rows from the local dev DB before running the suite. All limitations documented in the defense-request-redesign entry below (optional committee-conflict check, multi-instance locking) remain open and unrelated to this task.

**13. Exact next task.** None from this task — STOP per the explicit instruction. If resumed, the next items from the broader backlog are whatever the user specifies next; do not auto-start the `DefenseRequestIntegrationTest` environmental cleanup (§12) or any roadmap item without being asked.

---

**Defense-request redesign: student proposes room/date/time — DONE (2026-08-27). 🏁 Backend suite 274/274, 0 failures, 0 errors; frontend `npm run build` EXIT 0 (1852 modules); live HTTP double-booking smoke test PASS.**

**1. Overall verdict.** FIXED / COMPLETE. This was a deliberately-scoped feature redesign (not a bug fix) requested directly by the user, following the project's existing architecture and conventions throughout. The core inversion of responsibility — the STUDENT now proposes the concrete room/date/time; STUDENT_SERVICE only approves or rejects — is fully implemented end-to-end (entity → repository → service → controller → DTO → frontend API → UI → tests), verified by 274/274 backend tests (real Postgres, real Spring Security chain) and a live smoke test against the running application that reproduced the exact double-booking race described in the spec.

**2. Old behavior (before this task, now removed).**
- `DefenseServiceImpl.requestDefense(UUID)` — STUDENT, no body. Pure signal: no `Defense` row, no status change, just notified `STUDENT_SERVICE` with `DEFENSE_REQUESTED`.
- `DefenseServiceImpl.scheduleDefense(UUID, ScheduleDefenseRequest{room, scheduledAt})` — STUDENT_SERVICE, direct entry. Created the `Defense` row immediately and transitioned the thesis to `DEFENSE_SCHEDULED`. This is the endpoint that directly conflicted with the new spec and was removed.
- Endpoint `POST /api/theses/{thesisId}/defenses` (the STUDENT_SERVICE direct-schedule route) — REMOVED. `dto/defense/ScheduleDefenseRequest.java` — DELETED (fully superseded).

**3. New behavior.**
```
STUDENT   POST /api/theses/{thesisId}/defenses/request  { room, scheduledAt }
          Allowed when thesis is PENDING_DEFENSE_SCHEDULING (first proposal, after Item #8
          eligibility verification) OR DEFENSE_SCHEDULED with no active Defense (rebooking
          after a cancellation — mirrors the old scheduleDefense's dual-state allowance).
          Rejects a second proposal while one is already PENDING for this thesis.
          Validates room non-blank, scheduledAt present, and the 5-15 day window — all
          server-side, never trusting the DTO's bean validation alone.
          Creates a DefenseRequest(status=PENDING). Does NOT create a Defense. Does NOT
          change thesis status. Notifies STUDENT_SERVICE (DEFENSE_REQUESTED, reworded).

STUDENT_SERVICE   PATCH /api/theses/{thesisId}/defenses/request/decision  { approved, reason? }
          Operates on the thesis's current PENDING DefenseRequest (400 — "no pending
          defense request" — if none exists; no Defense, no status change, no notification).
          REJECT (approved=false): reason REQUIRED (400 otherwise). Marks REJECTED, stores
          reason + decidedAt + decidedBy, notifies the student (DEFENSE_REQUEST_REJECTED,
          new type, carries the reason). No Defense, no status change.
          APPROVE (approved=true): re-validates the 5-15 day window against the request's
          ORIGINAL createdAt (never "now") AND re-checks room availability against the
          CURRENT database state. If either re-check fails, the request is marked REJECTED
          with the conflict as the reason (per spec — a stale/conflicting approval attempt
          rejects rather than 500s) and the student is notified; NO Defense is created.
          If both pass: creates exactly one Defense (room/scheduledAt copied from the
          request), transitions the thesis to DEFENSE_SCHEDULED via the existing
          transitionStatus() helper (only on first scheduling — a rebooking after
          cancellation is already DEFENSE_SCHEDULED), and sends the EXISTING
          DEFENSE_SCHEDULED notification to the student + every committee member
          (mentor included exactly once via their MENTOR_MEMBER seat — unchanged pattern).

STUDENT/MENTOR/COMMITTEE/etc.   GET /api/theses/{thesisId}/defenses/request
          Full proposal history (current + past rejected/approved), newest first.
          Thesis-scoped via the existing ThesisReadAccessPolicy — identical policy to
          every other thesis-level read (owner/assigned mentor/seated committee member/
          STUDENT_SERVICE/ARCHIVE; everyone else 403).

Unchanged: PATCH .../defenses/cancel (still does NOT roll the thesis status back — this
is exactly what makes the "DEFENSE_SCHEDULED + no active Defense" rebooking branch work),
GET .../defenses/active, GET .../defenses, GET .../defenses/{id}/record-pdf, and the
whole grading/archiving flow.
```

**4. Data model.** New entity `com.praksa.model.DefenseRequest` (table `defense_requests`, `ddl-auto=update` — no migration tool introduced): `id`, `thesis` (FK, `@ManyToOne`), `requestedBy` (FK to `User` — the student; stored explicitly for audit even though always equal to `thesis.student`, matching the `CommitteeMember.proposedBy` / `ThesisStatusHistory.changedBy` convention), `room`, `scheduledAt`, `status` (new enum `DefenseRequestStatus{PENDING,APPROVED,REJECTED}`, `@Enumerated(STRING)`), `reason` (nullable text, populated on rejection), `createdAt`, `decidedAt` (nullable), `decidedBy` (nullable FK). Indexes: `(thesis_id, status)` (duplicate-PENDING lookup) and `room` (availability query). `Thesis` itself was deliberately NOT touched — no new columns, no duplicated state, per the spec's explicit preference for a dedicated entity. `Defense` gained one index (`room`) to support the new availability query; no other change. `DefenseRepository` gained `findByRoomAndIsCancelledFalse(String)`.

**5. The 5-15 day rule — exact semantics.** `scheduledAt` must satisfy `createdAt.plusDays(5) <= scheduledAt <= createdAt.plusDays(15)`, both bounds INCLUSIVE, using `OffsetDateTime#plusDays` (a fixed 24h-per-day duration add — no calendar/DST ambiguity, since `OffsetDateTime` carries a fixed offset). `createdAt` is captured ONCE at request-creation time and persisted on the `DefenseRequest` row; the approval-time re-check reads this SAME persisted value — it never recomputes against "now" at approval, exactly as the spec requires (a request that sat pending long enough to go stale is caught, not silently waved through).

**6. Room-availability / double-booking.** The project has no defense-duration field anywhere (`Defense` has only a point-in-time `scheduledAt`), so per the spec's own fallback instruction the interval-overlap rule degenerates to the point-in-time equivalent: exact `scheduledAt` equality in the same room. Checked via `DefenseRepository.findByRoomAndIsCancelledFalse(room)` — cancelled defenses are excluded by the query itself, so they never block. A PENDING `DefenseRequest` never reserves the room (two students CAN both hold a PENDING proposal for the same room/time simultaneously) — availability is only real-DB-checked at approval time, using a query issued fresh inside that transaction. **Verified live** (see §11): two real students proposed "Shared Hall" at the same instant, both stayed PENDING; approving the first created a real `Defense` and scheduled the thesis; approving the second re-queried the DB, found the now-real conflict, and REJECTED the second request with `"Просторијата е зафатена во тој термин."` as the stored reason — no second `Defense` was ever created.

**7. Concurrency.** Within a single instance, normal transactional read-committed semantics are sufficient for the spec's actual required scenario (sequential: approve A, THEN approve B — B's transaction only starts after A's has committed, so B's fresh query genuinely sees A's `Defense`). No pessimistic locking / `SELECT ... FOR UPDATE` was added — the codebase has no precedent for it anywhere, and the spec explicitly permits skipping true DB-level locking in favor of documenting the limitation. **Known limitation (documented, not fixed):** two STUDENT_SERVICE users approving two conflicting requests at the EXACT same instant (true concurrent transactions, not sequential) could theoretically both pass the availability check before either commits, producing two overlapping `Defense` rows. This mirrors the project's existing documented multi-instance notification-retry limitation (P2.6, "needs ShedLock") in spirit — acceptable at school scale where approvals are a human clicking a button, not fixed here per the spec's explicit "do not introduce an unnecessary distributed locking framework" instruction.

**8. Optional committee-member conflict check (spec §6) — NOT implemented.** Explicitly optional in the spec ("do NOT spend excessive time... if you do not implement this, document it as a known limitation"). Skipped to keep this already-large redesign scoped. **Known limitation:** a professor seated on two different theses' committees CAN currently be double-booked across two different (non-conflicting-room) approved defenses at overlapping times — the system does not check committee-member availability, only room availability. A future task could add this by checking, at approval time, whether any of the thesis's committee members (`CommitteeMemberRepository.findByThesis`) hold a seat on another thesis with a non-cancelled `Defense` at the same `scheduledAt`.

**9. Notifications.** Reused `DEFENSE_REQUESTED` (creation → STUDENT_SERVICE; body reworded from "review the request and schedule" to "review and approve or reject the proposal," since the event's meaning changed) and `DEFENSE_SCHEDULED` (approval → student + committee; UNCHANGED, same custom-message pattern). Added exactly one new type, `DEFENSE_REQUEST_REJECTED` (rejection → student, carries the reason as the custom message via the existing 4-arg `notify(..., customMessage)` overload — no entity crosses the `@Async` boundary, same pattern used everywhere else in the codebase). `isSent`/email-retry and `isRead` remain completely untouched and independent — nothing about the read/unread or retry paths was touched.

**10. Authorization.** `createDefenseRequest`: `requireRole(STUDENT)` then explicit ownership check (`thesis.student.id == caller.id`) — mirrors every other student-owned-thesis-action in `ThesisServiceImpl`/`DefenseServiceImpl`. `decideDefenseRequest`: `requireRole(STUDENT_SERVICE)`. `getDefenseRequests`: `ThesisReadAccessPolicy.requireReadAccess` — the same shared policy used by `getThesisById`/`getActiveDefense`/`getAllDefenses`/etc., so a student can only ever see their OWN thesis's proposal history. No new authorization mechanism was introduced; `cancelDefense`'s existing student-owner-or-mentor rule is untouched.

**11. Live HTTP smoke test (actually executed against the running app, not simulated).** Restarted the backend fresh (`spring-boot:run`, JBR JDK 25) so all new code was live. Registered two brand-new STUDENT accounts via the real public `/api/auth/register` (proving BUG-2's role-lock still holds), set both to 240 credits via STUDENT_SERVICE, and drove BOTH theses through the ENTIRE real workflow over live HTTP (create → eligibility → mentor request/accept → submit application → archive/service validate → upload+mark-final version → mentor approve-final → propose+approve committee → accept review → verify defense eligibility) to `PENDING_DEFENSE_SCHEDULING`. Then: Student A proposed "Shared Hall" for the same instant as Student B's proposal — both came back `PENDING`. STUDENT_SERVICE approved A → `APPROVED`, thesis A → `DEFENSE_SCHEDULED` (confirmed via `GET /api/theses/{id}`). STUDENT_SERVICE then attempted to approve B → came back `REJECTED` with `reason: "Просторијата е зафатена во тој термин."` (verified as correct UTF-8 via raw `curl`, not just the PowerShell console — the earlier mangled console display was purely a PowerShell codepage rendering artifact, not a data bug); thesis B correctly stayed `PENDING_DEFENSE_SCHEDULING`. Also verified live: a STUDENT attempting to call the decision endpoint → 403; a proposal missing `room` → 400; a proposal 2 days out (below the 5-day floor) → 400. This is the exact race scenario the spec calls out in §15/§21, proven against the real database, not mocked.

**12. Exact files changed.**
- *Backend (new):* `model/enums/DefenseRequestStatus.java`, `model/DefenseRequest.java`, `repository/DefenseRequestRepository.java`, `dto/defense/DefenseRequestCreateRequest.java`, `dto/defense/DefenseRequestDecisionRequest.java`, `dto/defense/DefenseRequestResponse.java`.
- *Backend (changed):* `repository/DefenseRepository.java` (+`findByRoomAndIsCancelledFalse`), `model/Defense.java` (+room index), `model/enums/NotificationType.java` (+`DEFENSE_REQUEST_REJECTED`, reworded `DEFENSE_REQUESTED`), `service/DefenseService.java` (interface rewritten: `requestDefense`/`scheduleDefense` → `createDefenseRequest`/`decideDefenseRequest`/`getDefenseRequests`), `service/impl/DefenseServiceImpl.java` (full rewrite of the request/schedule logic; `cancelDefense`/`getActiveDefense`/`getAllDefenses` unchanged), `controller/DefenseController.java` (endpoints updated as in §3).
- *Backend (deleted):* `dto/defense/ScheduleDefenseRequest.java`.
- *Backend (tests, new):* `test/service/DefenseRequestWorkflowTest.java` (28 Mockito tests, letters A-U from the spec checklist), `test/integration/DefenseRequestIntegrationTest.java` (6 real-DB/real-HTTP tests incl. the double-booking scenario).
- *Backend (tests, changed):* `test/service/DefenseServiceNotificationTest.java` (trimmed to the still-valid `cancelDefense` notification test; the two obsolete `scheduleDefense`-based tests were superseded by the new file), `test/integration/AbstractWorkflowIntegrationTest.java` (`requestDefense`/`scheduleDefense` helpers replaced with `requestDefense(room,at)`/`approveDefenseRequest`/`rejectDefenseRequest`; `advanceToDefenseScheduled` updated internally, same signature/behavior for callers), `test/integration/ThesisWorkflowIntegrationTest.java` (happy-path defense steps updated to propose+approve), `test/integration/AuthorizationWorkflowIntegrationTest.java` (Test D rewritten for the new role boundaries; the old direct-schedule "too early" test retargeted to the new endpoint).
- *Backend (tests, deleted):* `test/service/DefenseRequestSchedulingTest.java` (tested the removed `requestDefense(UUID)`/`scheduleDefense(UUID,...)` method signatures — could not compile against the new API; its still-valid cancel/authorization cases were preserved into the new/updated files).
- *Frontend (new):* `features/defense/ProposeDefenseModal.tsx` (student proposal form), `features/defense/RejectDefenseRequestModal.tsx` (Student Service reject-with-reason, mirrors the existing `RejectValidationModal` pattern).
- *Frontend (deleted):* `features/defense/ScheduleDefenseModal.tsx` (superseded by `ProposeDefenseModal`).
- *Frontend (changed):* `types/api.ts` (+`DefenseRequest`, +`DefenseRequestStatus`), `api/defenseApi.ts` (`request`/`schedule` → `createRequest`/`decideRequest`/`getRequests`), `features/defense/DefenseSection.tsx` (major rework — shows the PENDING proposal with Одобри/Одбиј for Student Service, Чека одобрување for the student, the REJECTED reason + "Предложи нов термин" button, gates on the new `eligibleForProposal` = `PENDING_DEFENSE_SCHEDULING` OR `DEFENSE_SCHEDULED`-with-no-active-Defense), `pages/DefensesPage.tsx` (removed the direct-schedule quick action; shows a "Чека одобрување" badge when a pending proposal exists), `pages/NotificationsPage.tsx` (+label for `DEFENSE_REQUEST_REJECTED`, reworded `DEFENSE_REQUESTED` label).
- `CLAUDE.md` — this entry + CURRENT NEXT STEP rewrite.
- No other files touched. All prior work (the entire audited, professor-ready state) was preserved untouched — no reset/restore/checkout/clean/stash.

**13. Focused test results (real, JBR JDK 25, live local Postgres).** `-Dtest=DefenseRequestWorkflowTest,DefenseServiceNotificationTest,DefenseRequestIntegrationTest,ThesisWorkflowIntegrationTest,AuthorizationWorkflowIntegrationTest,ArchiveNotesIntegrationTest,DefenseEligibilityVerificationTest,DefenseReminderNotificationTest,DefenseResultGradingAuthorizationTest,DefenseResultGradeValidationTest,DefenseResultServiceNotificationTest,CommitteeWorkflowIntegrationTest` → **85/85, BUILD SUCCESS.** (One real bug caught and fixed during this run: two of my own new integration-test assertions compared an `OffsetDateTime` sent as `+02:00` against the value Postgres round-trips as `Z` — same instant, different offset representation, so `assertEquals` failed even though the data was correct. Fixed by asserting `isEqual()` instead — an `OffsetDateTime` test-comparison bug, not a product bug.)

**14. Full backend suite.** `./mvnw.cmd -o test` → **Tests run: 274, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS.** Baseline was 256.

**15. Frontend build.** `npm run build` (`tsc -b && vite build`) → **EXIT 0**, `✓ 1852 modules transformed` (was 1851 — net +1 file after removing `ScheduleDefenseModal.tsx` and adding `ProposeDefenseModal.tsx` + `RejectDefenseRequestModal.tsx`). Zero TypeScript errors.

**16. Remaining limitations (documented, not fixed — all explicitly in-scope-to-skip per the task spec).**
- Optional committee-member availability check (§8 above) — not implemented.
- Multi-instance / true-concurrent-transaction double-approval race (§7 above) — not implemented (no distributed lock; documented, matches the project's existing P2.6 ShedLock-limitation precedent).
- Room-name matching is exact-string (trimmed) — no case-insensitive or fuzzy room-name normalization; acceptable since rooms are typically chosen from a known small set at a single institution.
- The frontend's `min`/`max` datetime-local bounds on the proposal form are a UX nicety only — the backend is the sole authority on the 5-15 day rule (verified live: a 2-day-out proposal was rejected server-side with a clear 400).

**17. Exact next task.** None — per the task's explicit instruction, STOP after this redesign. If resumed later, the two documented limitations above (§8 optional committee-conflict check, §7 multi-instance locking) are the natural follow-ups, but neither should be started without being asked for.

**18. Honesty notes.** ACTUALLY EXECUTED: the 85-test focused run, the 274-test full suite (both BUILD SUCCESS, JBR JDK 25, live local Postgres `diploma_system`), `npm run build` (EXIT 0), and a full live HTTP walkthrough against a freshly-restarted real server process — including the exact double-booking scenario, three negative/authorization checks, and a raw-`curl` UTF-8 verification of the Cyrillic rejection reason. STATICALLY VERIFIED: `git status`/grep confirmed no stray references to the removed `ScheduleDefenseRequest`/old method signatures remain anywhere in `src/`. NOT executed: a live browser/DOM walkthrough of the new `ProposeDefenseModal`/`RejectDefenseRequestModal` UI (covered instead by the green `tsc`/`vite build` and the fact that the components call the same live-verified API); a real multi-instance/clustered deployment test of the documented concurrency limitation.

---

**Final Readiness Audit — DONE (2026-08-18). 🏁 VERDICT: READY FOR PROFESSOR. Backend suite 256/256, 0 failures, 0 errors; frontend `npm run build` EXIT 0; browser smoke test PASS.**

**1. Scope.** A final, pre-demo correctness/security/UX audit — NOT a feature task. No new roadmap items started (no RS256/JWKS, no refresh tokens, no infra, no schema redesign, no workflow-semantics changes). Goal: find and fix only issues a professor would reasonably expect fixed before a demo; document the rest.

**2. The one issue found and fixed (Medium — read-side IDOR / data exposure).**
- **Problem.** `ThesisServiceImpl.findByRegistrationNumber` (backing `GET /api/theses/by-registration-number/{n}`) looked the thesis up and returned `ThesisResponse.from(thesis)` with NO authorization. Every OTHER thesis-level read (`getThesisById`, `getStatusHistory`, `getCommittee`, `getActiveDefense`, `getAllDefenses`, `getResult`, `downloadApplicationPdf`, version reads) enforces `ThesisReadAccessPolicy.requireReadAccess`; this one was missed. Because registration numbers are sequential (`DT-YYYY-NNNN`), any authenticated user (e.g. a STUDENT) could enumerate the number space and harvest every archived thesis — including the internal `studentComment`/`mentorComment`/`archiveComment`/`serviceComment`/`archiveNotes` carried by `ThesisResponse`.
- **File.** `src/main/java/com/praksa/service/impl/ThesisServiceImpl.java` (`findByRegistrationNumber`).
- **Fix.** Added `thesisReadAccessPolicy.requireReadAccess(thesis, securityUtils.getCurrentUser())` immediately after the lookup, before returning the DTO — identical to `getThesisById`. Lookup-then-guard ordering matches `getThesisById` (404 for a non-existent number, 403 for an existing-but-unrelated thesis).
- **Why it's safe.** STUDENT_SERVICE and ARCHIVE (the legitimate archive-search users) have unconditional read access in the policy, so the archive lookup UI (`ArchivePage`, `ThesesListPage`) keeps working; the student-owner/assigned-mentor/seated-committee still resolve their own. The frontend already only uses this endpoint to resolve a number→id then navigates to the already-access-controlled detail page, so no legitimate flow regresses. No contract/DTO/schema change.
- **Regression tests.** Added a `FindByRegistrationNumber` nested class (2 tests: unrelated→403 guard invoked, related→passes) to `src/test/java/com/praksa/service/ThesisReadIdorGuardTest.java`, matching the existing IDOR-guard test style. Suite 254 → **256**.

**3. Everything else audited and found CORRECT (no change made).**
- **Auth.** `register` hard-codes `role=STUDENT` (BUG-2). `login` goes through `AuthenticationManager.authenticate` → wrong password AND unknown email both surface as `AuthenticationException` → **403** with a single non-enumerating message ("Invalid email or password"); no 500, no account-existence leak.
- **Authorization/IDOR.** All other ID-taking reads/mutations enforce `requireReadAccess` / role+seat guards at the service layer before any repo query; versions are thesis-scoped (`findVersion` rejects cross-thesis ids); notification mark-read/unread-count/read-all are user-scoped (404 unknown, 403 other-user's); grading is committee-seat-scoped (BUG-13). `GET /api/users?role=` is per-role authorized (P2).
- **Error handling.** `GlobalExceptionHandler` maps 400/403/404 correctly, auth→403, and a catch-all→500 with a generic message (no stack trace leaked).
- **Notifications.** Wire keys verified consistent (`isSent` boolean getter serializes as `sent`, `isRead`→`read`; frontend uses `sent`/`read` — P3.4 fix holds).
- **Config/secrets.** `application.properties` has NO inline secrets (all `${ENV}`-backed; DB pw + `jwt.secret` fail-fast); `.gitignore` covers `application-local.properties` + `.env`; only `application-local.properties.example` is tracked. CORS is an explicit origin list (never `*`), `allowCredentials=false`.
- **Security grep.** No `printStackTrace`/`System.out` in main (one benign `System.err.println` warning in `FileStorageService` file-cleanup); no password/secret logging; `permitAll` limited to `/api/auth/**`, swagger, `/actuator/health`.

**4. Issues intentionally NOT fixed (optional / out of scope — documented, not implemented).**
- `GlobalExceptionHandler.handleValidation` builds a per-field error map then discards it (returns a flat "Validation failed" 400). Harmless; returning field-level messages is a UX nicety, not a defect — would change the error response shape the frontend already handles.
- `ThesisVersionServiceImpl.checkThesisReadAccess` duplicates `ThesisReadAccessPolicy`'s logic (consistent, but could be de-duplicated — cosmetic, noted as P2.8).
- `FileStorageService` uses `System.err.println` for a failed-cleanup warning instead of a logger (cosmetic).
- The lookup-then-guard ordering exposes a 404-vs-403 existence oracle on sequential registration numbers; matches `getThesisById` and is acceptable (number existence is not sensitive; the DATA leak was the real issue and is closed).
- Larger optional roadmap items remain deliberately unstarted: P3.1 (RS256/JWKS), P3.5 (refresh tokens), mobile-responsive layout.

**5. Exact files changed THIS task.**
- `src/main/java/com/praksa/service/impl/ThesisServiceImpl.java` — added the read-access guard to `findByRegistrationNumber` (+ explanatory comment).
- `src/test/java/com/praksa/service/ThesisReadIdorGuardTest.java` — added the `FindByRegistrationNumber` nested test class (2 tests).
- `CLAUDE.md` — this entry + CURRENT NEXT STEP rewrite.
(No other files touched. All pre-existing uncommitted work from earlier sessions was preserved — no reset/restore/checkout/clean/stash.)

**6. Backend tests.** `./mvnw.cmd -o test` (JBR JDK 25, live local Postgres `diploma_system`) → **Tests run: 256, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS.** Baseline 254 → 256 (+2 = the new IDOR regression tests). The one ERROR log line during the run is a deliberately-injected failure inside `NotificationRetryServiceTest`, not a test failure.

**7. Frontend build.** `npm run build` (`tsc -b && vite build`) → **EXIT 0**, 1851 modules transformed.

**8. Browser smoke test (executed).** Vite dev (5173) + Spring Boot (8080, `JWT_SECRET`/`SPRING_DATASOURCE_PASSWORD=123`/`MAIL_ENABLED=false`). Verified: frontend loads → logout clears session → login page renders with seeded accounts → valid login (`student@test.com`) → dashboard ("Welcome back, Test") → Notifications page (friendly type labels, thesis titles, timestamps, unsent "Pending" badges) → My Theses (student's thesis listed) → sidebar navigation works. HTTP checks: `by-registration-number/DT-2026-9999` → 404 authenticated (not 500), 403 unauthenticated. (No archived thesis exists in the dev DB, so a live archived-lookup 403 wasn't exercised via HTTP — that path is covered by the new unit tests + `ThesisReadAccessPolicyTest`.)

**9. Professor readiness.** YES. Backend green (256/256), frontend green, no known security vulnerability remaining, core workflow + auth + authorization + notifications all verified, contracts consistent, no broken UI flow found. Remaining items are optional/architectural.

**10. Recommended next action.** STOP DEVELOPMENT. SEND PROJECT TO PROFESSOR FOR FEEDBACK. Do not auto-start P3.1/P3.5/mobile — those are only if the professor asks.

---

**(prior) CURRENT NEXT STEP = P2.2 + P3.3 + P3.4 are now DONE (2026-08-18) — see the dated "Combined P2.2 + P3.3 + P3.4" entry directly below.** The ARCHIVE role can now add/edit archive notes on an archived thesis (`PATCH /api/theses/{id}/archive-notes`, reusing the existing `Thesis.archiveNotes` field — no schema change); configuration-driven CORS is now in place for a future split deployment (BUG-14 fixed — `app.cors.allowed-origins`, default `http://localhost:5173`, never a wildcard, Bearer-header compatible); and a frontend/backend consistency audit fixed one real contract mismatch (the notification `isSent` wire key was `sent`). Full backend suite is **254 tests, 0 failures, 0 errors** (was 235; +19). Frontend `npm run build` EXIT 0. The recommended next task is one of the remaining larger optional roadmap items — **P3.1** (RS256/JWKS external auth), **P3.5** (refresh tokens), or **P3.4-roadmap** (mobile-responsive layout). Do NOT start any of them automatically.

---

**Combined P2.2 + P3.3 + P3.4 — Archive notes editor + CORS/deployment config + frontend/backend consistency audit — DONE (2026-08-18). 🏁 Backend suite 254/254, 0 failures, 0 errors; frontend `npm run build` EXIT 0.**

**1. Overall verdict.** FIXED / COMPLETE for all three parts. P2.2 (ARCHIVE archive-notes editor) implemented end-to-end reusing the existing `Thesis.archiveNotes` field (no DB change); P3.3 (CORS) implemented as the smallest configuration-driven setup (BUG-14 closed) without weakening auth or introducing a wildcard; P3.4 (consistency audit) found and fixed exactly one real inconsistency (a notification response-shape mismatch) and confirmed the rest of the contract/role-gating is already correct. All verified against the running app: 19 new backend tests pass, full suite 254/254, frontend build green.

**2. P2.2 — Archive notes.**
- *Existing architecture discovered (verified from source):* `Thesis.archiveNotes` (`@Column(name="archive_notes", columnDefinition="text")`) already existed and was already exposed by `ThesisResponse.archiveNotes` and the TS `Thesis.archiveNotes` type — it was preserve-only (set nowhere, editable nowhere; BUG-12). No new field/column was needed. The archive record card in `ThesisDetailPage` and the archived cards in `ArchivePage` already rendered `archiveNotes` read-only.
- *Implementation:* new `dto/thesis/ArchiveNotesRequest { @Size(max=5000) String notes }`; `ThesisService.updateArchiveNotes(UUID, ArchiveNotesRequest)`; `ThesisServiceImpl.updateArchiveNotes` — `requireRole(ARCHIVE)` FIRST (before loading the thesis), then `findThesis` (404), then `requireStatus(ARCHIVED)` (400), then set `archiveNotes` (trimmed; blank/null clears) and `thesisRepository.save`. NO `transitionStatus` (→ no status change, no `ThesisStatusHistory` row) and NO notification. Reuses the existing `requireRole`/`requireStatus`/`findThesis` guards and conventions.
- *Authorization:* server-side and authoritative. ARCHIVE → allowed; STUDENT/MENTOR/STUDENT_SERVICE/COMMITTEE → 403 (role checked before the thesis is even loaded, so a non-ARCHIVE caller cannot probe a thesis id); unauthenticated → 403 (Spring Security). ARCHIVE is a global validator role (like `archiveValidate`), so it legitimately edits any ARCHIVED thesis — the IDOR concern (a user modifying an unrelated thesis by knowing its id) is fully covered because every non-ARCHIVE caller is rejected regardless of id, and a non-ARCHIVED thesis is rejected with 400. Nonexistent thesis → 404.
- *API:* `PATCH /api/theses/{id}/archive-notes`, body `{ "notes": "…" }`, returns `ApiResponse<ThesisResponse>`. Requires role ARCHIVE + status ARCHIVED.
- *Frontend:* editor added ONLY where it naturally belongs — inside the existing "Official Archive Record" card in `ThesisDetailPage.tsx` (shown when `status === 'ARCHIVED'`). ARCHIVE users see an "Add notes"/"Edit notes" affordance that opens an inline textarea (max 5000) with Save/Cancel; Save calls `thesisApi.updateArchiveNotes` and updates the thesis in place. No new page/dashboard; reused `input-field`/`btn-primary`/`btn-secondary`/`sonner`. Non-ARCHIVE users see the notes read-only exactly as before. `thesisApi.updateArchiveNotes(id, notes)` added.
- *Tests:* `ArchiveNotesServiceTest` (6 Mockito) — sets/edits/clears notes with no status change / no history / no notification; non-ARCHIVE roles 403 before loading the thesis; nonexistent → 404; non-ARCHIVED → 400. `ArchiveNotesIntegrationTest` (8 `@SpringBootTest`/MockMvc over the real security chain + real Postgres) — ARCHIVE sets notes (asserts status still ARCHIVED, history-row count unchanged, notification count unchanged); update/overwrite/clear; STUDENT/MENTOR/STUDENT_SERVICE/COMMITTEE/an unrelated professor all 403 (and the seeded note is unchanged); unauthenticated rejected; nonexistent → 404; owner-STUDENT (a related user) still 403 (IDOR — editing notes is not their action); non-ARCHIVED thesis → 400; and the EXISTING archive validation/rejection workflow (reject-with-reason → `APPLICATION_REJECTED_BY_ARCHIVE`, resubmit, approve → `PENDING_SERVICE_VALIDATION`) still works.

**3. P3.3 — CORS/deployment audit.**
- *Existing configuration:* NONE. `SecurityConfig` never called `http.cors(...)` and there was no `CorsConfigurationSource` bean (BUG-14). The frontend worked ONLY via the Vite dev proxy (`vite.config.ts` proxies `/api` → `http://localhost:8080`); `client.ts` uses `baseURL='/api'`. Auth is a JWT **Bearer token in the `Authorization` header** (Zustand → axios request interceptor) — NOT cookies.
- *Findings:* CORS was genuinely missing and a future split frontend/backend deployment would break in the browser. A change was warranted (not an unnecessary one).
- *Changes (smallest appropriate):* `SecurityConfig` now enables `.cors(Customizer.withDefaults())` and defines a `CorsConfigurationSource` bean driven by a new property `app.cors.allowed-origins` (`${CORS_ALLOWED_ORIGINS:http://localhost:5173}` in `application.properties`). Allowed origins are an explicit, comma-separated list — **never `*`**. Methods: GET/POST/PUT/PATCH/DELETE/OPTIONS. Allowed headers: Authorization, Content-Type, Accept, Origin. Exposed header: Content-Disposition (so the SPA can read streamed-PDF filenames cross-origin). `allowCredentials=false` — deliberate and correct because auth is a Bearer header, not cookies; the Authorization header is explicitly whitelisted so tokens still flow. Default is the local Vite origin so local dev is unaffected (and in dev the proxy means CORS isn't even exercised); production supplies real origins via `CORS_ALLOWED_ORIGINS`. NO fake production domain introduced.
- *Security implications:* CSRF stays disabled/stateless (unchanged, correct for a Bearer-token API). No wildcard. `allowCredentials=false` means the browser is never asked to send cookies cross-origin. Authentication is unchanged; the JwtAuthFilter never blocks preflight (OPTIONS carries no Bearer token and Spring Security's CORS filter short-circuits preflight before authorization). No production-deployment compatibility is *claimed* — only that the config is now suitable for one and is verified against the local origin.
- *Tests:* `CorsConfigurationIntegrationTest` (5 `@SpringBootTest`/MockMvc) — preflight from the allowed origin is approved, echoes the origin, and allows Authorization; preflight from a disallowed origin returns no `Access-Control-Allow-Origin`; an authenticated GET from the allowed origin succeeds with the ACAO header (Authorization usable cross-origin); a no-token protected request from the allowed origin is still 4xx with the ACAO header present (auth still enforced, CORS is not the blocker); the auth endpoint works cross-origin (preflight + actual register both succeed with ACAO).

**4. P3.4 — Frontend/backend consistency audit.**
- *Areas inspected:* every notification endpoint (`GET /my`, `GET /unsent`, `GET /unread-count`, `PATCH /{id}/read`, `PATCH /read-all`) — URL/method/body/response DTO/auth all match `notificationApi.ts`; the `read` (P3.6) wire key is handled correctly (frontend reads `notification.read`, matching Jackson's `isRead`→`read`). Defense endpoints + `DefenseSection` role gating (`canGrade = isMentor || COMMITTEE`, `canSchedule`/`canRequest`/`canCancel`/`canVerifyEligibility` — all match the backend's STUDENT_SERVICE-scheduling / seated-committee-grading / student-request / owner-or-mentor-cancel rules). Committee, version, and thesis endpoints (URLs/methods verified against the controllers). Archive endpoints (the new one plus validate/search).
- *Inconsistencies found:* exactly ONE real defect — the frontend `Notification` type declared `isSent: boolean`, but the backend serializes that boolean getter as the wire key **`sent`** (Jackson strips the `is` prefix, same as `read`). `NotificationsPage.tsx` read `notification.isSent`, which was therefore ALWAYS `undefined`/falsy, so the per-notification "Sent/Pending" email-delivery badge was permanently stuck on "Pending" regardless of the real value. (This was the pre-existing latent bug noted-but-deferred in the P3.6 entry; the P3.4 audit is exactly where it belongs, so it was fixed.)
- *Fixes made:* `types/api.ts` — `Notification.isSent` → `Notification.sent` (with a comment explaining the Jackson wire-key). `NotificationsPage.tsx` — `notification.isSent` → `notification.sent`. The badge now reflects the real delivery state. No other frontend caller referenced the field (grep-confirmed).
- *Areas confirmed correct (left alone):* all notification/defense/committee/version/thesis/archive endpoint URLs, methods, request bodies, and response shapes; the `read` field handling; defense/grading/schedule/cancel/eligibility role gating; the `403 != logout` interceptor split; the mentor-picker/credit-list PII scoping. No backend contract was changed to suit the frontend. Frontend role gating remains UX-only; the backend stays authoritative.

**5. Exact files changed (this task only).**
- *Backend (production, 4):* `security/SecurityConfig.java` (enable CORS + `CorsConfigurationSource` bean + `app.cors.allowed-origins`), `service/ThesisService.java` (+`updateArchiveNotes`), `service/impl/ThesisServiceImpl.java` (+`updateArchiveNotes`), `controller/ThesisController.java` (+`PATCH /{id}/archive-notes`), `resources/application.properties` (+`app.cors.allowed-origins` block).
- *Backend (new, 1 DTO + 3 tests):* `dto/thesis/ArchiveNotesRequest.java`; `test/.../service/ArchiveNotesServiceTest.java`; `test/.../integration/ArchiveNotesIntegrationTest.java`; `test/.../integration/CorsConfigurationIntegrationTest.java`.
- *Frontend (4):* `src/api/thesisApi.ts` (+`updateArchiveNotes`), `src/pages/ThesisDetailPage.tsx` (archive-notes editor in the archive record card), `src/types/api.ts` (`Notification.isSent`→`sent`), `src/pages/NotificationsPage.tsx` (`notification.isSent`→`sent`).
- `CLAUDE.md` — this entry + CURRENT NEXT STEP / roadmap updates.
- All the many PRE-EXISTING uncommitted changes from prior tasks were preserved untouched; no `git reset/restore/checkout/clean/stash` was run.

**6. Security verification.** ARCHIVE archive-notes editing is server-side and role-gated before the thesis is loaded (no id-probing); non-ARCHIVE and unauthenticated are rejected (403); IDOR covered (any non-ARCHIVE caller rejected regardless of id; non-ARCHIVED status → 400). Editing notes never changes thesis status and never creates a notification (asserted in integration tests via history-row-count and notification-count invariants). CORS uses an explicit origin list (no wildcard), `allowCredentials=false`, Authorization allowed; CSRF/stateless/JWT unchanged; no secrets introduced. No JWT/password/authentication semantics touched. No other user's notifications/thesis data exposed. `NotificationType`, recipients, and email `isSent`/retry (P2.6) semantics unchanged.

**7. Focused test results (real, JBR JDK 25 via `./mvnw.cmd -o`, `--release 21`, live local Postgres).** `-Dtest=ArchiveNotesServiceTest,ArchiveNotesIntegrationTest,CorsConfigurationIntegrationTest` → **19/19, BUILD SUCCESS** (6 + 8 + 5).

**8. Full backend test results.** `./mvnw.cmd -o test` → **Tests run: 254, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS.** Baseline was 235; the +19 are exactly the new tests. Suite is fully green.

**9. Frontend build results.** `npm run build` (`tsc -b && vite build`) → **EXIT 0**, `✓ 1851 modules transformed`, `dist/` emitted. No TS errors.

**10. Working-tree verification.** `git status` before/after confirms this task added only 4 new backend files (1 DTO + 3 tests) and modified 4 backend production/config files + 4 frontend files + CLAUDE.md. All prior uncommitted work preserved. No destructive git ran.

**11. CLAUDE.md updates.** This dated entry added; CURRENT NEXT STEP rewritten to mark P2.2/P3.3/P3.4 DONE and point at the next optional item; BUG-12 (archive-notes editor) and BUG-14 (CORS) marked FIXED; P2.2/P3.3 roadmap rows marked DONE; SINGLE BEST NEXT TASK updated. Historical entries preserved.

**12. Remaining issues.** None introduced. Pre-existing/known: notification-retry multi-instance dedup limitation (P2.6, needs ShedLock — documented); `JwtUtil` is symmetric HMAC only (no JWKS — P3.1); no refresh tokens (P3.5); fixed-256px sidebar / not mobile-responsive (P3.4-roadmap); `checkReadAccess` stale doc comment (P2.8). No test failures, no build blockers.

**13. Exact next task (do NOT start).** A larger optional roadmap item — **P3.1** (RS256/JWKS external auth, remove/disable local login), **P3.5** (refresh tokens / session renewal), or **P3.4-roadmap** (mobile-responsive layout). Small alternative: **P2.8** (fix the stale "admin" doc comment in `ThesisVersionServiceImpl.checkReadAccess`).

**14. Honesty notes.** ACTUALLY EXECUTED (JBR JDK 25 against live local Postgres `diploma_system`): the 19 new focused tests, the full 254-test suite (BUILD SUCCESS), and `npm run build` (EXIT 0). STATICALLY VERIFIED: the exact changed-file set (`git status`), the notification wire-key mismatch (grep — only two usages), and that every frontend API module's URLs/methods match the backend controllers. NOT TESTED: a live browser walkthrough of the archive-notes editor (it needs a thesis driven all the way to ARCHIVED; the behavior is instead covered end-to-end by the 8 `@SpringBootTest`/MockMvc integration tests over the real security chain + real Postgres, plus the green frontend build) and real cross-origin CORS from a second deployed origin (covered by MockMvc preflight/actual-request tests against the configured origin). ENVIRONMENT: build ran on the JetBrains Runtime JDK 25 (compiled `--release 21`, standard for this repo); tests used the live local Postgres, which was available.

---

**(prior) CURRENT NEXT STEP = P3.6 (notification read/unread) is now DONE (2026-08-18) — see the dated "P3.6 — Notification read/unread — COMPLETE" entry directly below.** Notifications now carry an application-side `isRead` flag (independent of the email `isSent` flag), new notifications default to unread, and there are secure user-scoped endpoints: `PATCH /api/notifications/{id}/read` (ownership enforced server-side — 403 for another user's notification, 404 for an unknown id, idempotent), `PATCH /api/notifications/read-all`, and `GET /api/notifications/unread-count`. The frontend shows unread notifications distinctly, a per-notification "Mark read" action, a "Mark all as read" button, and a Sidebar unread badge. Full backend suite is a clean **235 tests, 0 failures, 0 errors** (was 220; +15 new P3.6 tests). Frontend `npm run build` EXIT 0. P2.6 retry behavior verified intact and independent of read state; email/`isSent` semantics unchanged. The recommended next task is one of the remaining larger optional roadmap items — **P2.2** (ARCHIVE-role archive-notes editor), **P3.3** (CORS config for split deployment), or **P3.1** (RS256/JWKS external auth). Do NOT start any of them automatically.

_(Historical note: the paragraph below described the state BEFORE P3.6. Retained for context.)_

**CURRENT NEXT STEP (pre-P3.6) = the frontend production build is now FULLY GREEN. `npm run build` (`tsc -b && vite build`) succeeds with EXIT 0 — the last blocker, the `tsconfig.app.json` `baseUrl` deprecation (TS5101), was FIXED 2026-08-18 by REMOVING `baseUrl` and keeping a `paths`-only alias setup (see the dated "Frontend TS5101 — `baseUrl` removed, `paths`-only alias" entry directly below). The `FileText` TS6133 blocker was fixed earlier the same day. The backend suite baseline is a clean 220/220 (0 failures, 0 errors), NOT re-run for this frontend-only config change. No `npm run build` blockers remain. The recommended next task is one of the larger optional roadmap items — P2.2 (archive-notes editor), P3.6 (notification read/unread flag), P3.3 (CORS config for split deployment), or P3.1 (RS256/JWKS external auth). Do NOT start any of them automatically.**

---

**P3.6 — Notification read/unread — COMPLETE (2026-08-18). 🏁 Backend suite 235/235, 0 failures, 0 errors; frontend `npm run build` EXIT 0.**

Adds an application-side read/unread state to notifications, end-to-end (entity → repository → service → controller → DTO → frontend API → notification UI → tests), reusing the existing notification architecture. Completely separate from the P2.6 email `isSent`/retry path. No existing notification behavior, type, recipient, or email semantics changed.

**1. Overall verdict.** FIXED / COMPLETE. All acceptance criteria met and verified against the running application: backend focused tests (15) pass, full suite is 235/235 (0 failures/errors), frontend build is green, and a live browser + live-API walkthrough confirmed the behavior.

**2. Exact files changed (this task only).**
- *Backend (production, 6):* `model/Notification.java` (add `isRead` boolean + `@ColumnDefault("false")`), `repository/NotificationRepository.java` (`countByUserAndIsReadFalse`, `@Modifying markAllReadByUser`), `service/NotificationService.java` (interface: `markAsRead`, `markAllAsRead`, `getUnreadCount`), `service/impl/NotificationServiceImpl.java` (implement the three, + `ResourceNotFoundException` import), `dto/notification/NotificationResponse.java` (add `isRead` field), `controller/NotificationController.java` (3 new endpoints).
- *Backend (tests, 2 NEW):* `src/test/java/com/praksa/service/NotificationReadServiceTest.java` (8 Mockito tests), `src/test/java/com/praksa/integration/NotificationReadIntegrationTest.java` (7 `@SpringBootTest`/MockMvc tests over the real security chain + real Postgres).
- *Frontend (4):* `src/types/api.ts` (`Notification.read: boolean`), `src/api/notificationApi.ts` (`getUnreadCount`, `markRead`, `markAllRead`), `src/pages/NotificationsPage.tsx` (unread styling, per-notification "Mark read", "Mark all as read", unread count), `src/components/layout/Sidebar.tsx` (unread badge, refreshed on route change).
- `CLAUDE.md` — this entry + CURRENT NEXT STEP update.
- NOTE: `git diff --stat` on `NotificationServiceImpl.java` shows more lines because that file already carried PRE-EXISTING uncommitted P2.6 (retry) + BUG-19 (requireRole) changes from earlier tasks; my P3.6 additions there are only the `markAsRead`/`markAllAsRead`/`getUnreadCount` methods and the one import. Those prior changes were preserved untouched.

**3. Existing notification architecture discovered (verified against source).** `NotificationServiceImpl.notify(recipient, thesis, type[, custom])` saves a `Notification` row (`isSent=false`) synchronously in the caller's transaction, extracts primitives, and dispatches `EmailService.sendAsync`. `EmailService` (`@Async`, own tx) is the SOLE authority that flips `isSent=true` (only on a genuine send; skipped cleanly when `app.mail.enabled=false`). The P2.6 `retryUnsentNotifications` job walks a bounded, age-filtered page of `isSent=false` rows and re-dispatches via the same `sendAsync`. `Notification` had `user` (LAZY, non-null), `thesis` (LAZY, nullable), `type` (String), `isSent`, `sentAt`, `createdAt`. The only creation site is `notify()`.

**4. Data model.** Added one field: `Notification.isRead` (`@Column(name="is_read", nullable=false)` + `@ColumnDefault("false")`). `false = unread`, `true = read`. New notifications default to unread at the ENTITY level — the Lombok `@Builder` leaves the primitive `boolean` false, and `notify()` never sets it, so every creation path is unread with zero per-call-site changes. `isRead` is completely independent of `isSent`; all four combinations remain valid. No `readAt` timestamp added (a simple boolean is sufficient; task-preferred).

**5. Backend API (new, all user-scoped, all require authentication).**
- `PATCH /api/notifications/{id}/read` → marks ONE notification read; returns the updated `NotificationResponse`. Ownership verified server-side. Idempotent. Never sends email, never touches `isSent`/`sentAt`/type/recipient/thesis, never creates a row.
- `PATCH /api/notifications/read-all` → marks all the caller's unread notifications read; returns `{ "markedRead": N }`.
- `GET /api/notifications/unread-count` → returns `{ "unreadCount": N }` for the caller only.
- Existing `GET /my` and `GET /unsent` unchanged (both now also carry the new `read` field in each row where applicable).

**6. Authorization (how cross-user access is prevented).** `markAsRead` resolves the current user from `SecurityUtils.getCurrentUser()` (the authenticated principal — never a client-supplied id), loads the notification (`ResourceNotFoundException` → 404 if absent), then compares `notification.getUser().getId()` to the current user's id; a mismatch throws `UnauthorizedException` → **403** ("You cannot modify another user's notification"). No request body/param is ever trusted for authorization. `markAllAsRead` and `getUnreadCount` are scoped by the current-user argument in the repository query (`WHERE n.user = :user`), so they can only ever affect/count the caller's own rows. Error contract follows the project convention (load→404, ownership→403), matching `ThesisReadAccessPolicy`.

**7. Frontend.** `NotificationsPage` now: receives `read` per notification; renders unread rows distinctly (brand-tinted background, left accent bar, bold title, an "Unread" dot); shows a per-notification "Mark read" button (only on unread) that calls the endpoint and flips that row in place; shows a "Mark all as read (N)" header button that calls read-all and updates all rows + a success toast; derives the unread count from the loaded list. `Sidebar` shows an unread-count badge on the Notifications nav item, fetched from `unread-count` and refreshed on every route change (no polling, no new state library). Existing display/navigation behavior preserved; no redesign; reused existing `card`/`btn-secondary`/`cn`/`sonner` conventions. The wire key is `read` (Jackson strips the `is` prefix from the boolean getter, exactly as the existing `isSent` serializes as `sent` — verified empirically and against the live API); the frontend reads `notification.read`.

**8. Unread count / mark-all.** BOTH implemented, because they fit the existing UI: the app already has a Notifications nav item + page, so a nav unread badge and a page-level count/mark-all are natural, minimal additions (not a new dashboard). Both are strictly user-scoped server-side.

**9. P2.6 compatibility.** Email delivery/retry is fully independent of read state, verified by grep + tests: `EmailService` and `ScheduledTasksService` contain ZERO references to `isRead`/read; the retry query is still purely `isSent`-based (`findByIsSentFalseAndCreatedAtBeforeOrderByCreatedAtAsc`); `setRead(true)` exists ONLY in `markAsRead`; `setSent(true)` exists ONLY in `EmailService`. Marking read never triggers a send and never sets `isSent`/`sentAt`; retry never marks anything read; a read notification is still retried for delivery if unsent. All 4 state combinations valid. The full notification/email/scheduled regression group (56 tests incl. `NotificationRetryServiceTest`, `EmailServiceTest`, `DefenseReminderNotificationTest`, etc.) passes.

**10. Database impact.** One new column on `notifications`: `is_read boolean NOT NULL DEFAULT false`. The project uses Hibernate `ddl-auto=update` (no Flyway/Liquibase — none was introduced). `@ColumnDefault("false")` makes Hibernate emit `DEFAULT false` in the generated `ALTER TABLE ... ADD COLUMN`, so the NOT NULL column back-fills existing rows safely. VERIFIED on the live DB: the column exists as `boolean NO false`, and the 4 pre-existing notification rows were all back-filled to `is_read=false` (unread) with no data loss and no failed startup. No other table touched; no index added (unread-count over a small per-user set does not need one at school scale).

**11. Tests added.** `NotificationReadServiceTest` (8): new notification defaults unread (+entity default); `notify()` still dispatches email; owner mark-read sets read + response reflects it + no email + `isSent` untouched; idempotent (already-read → no save, no email); cross-user → 403, stays unread, no save/email; unknown id → 404; `getUnreadCount` user-scoped; `markAllAsRead` user-scoped. `NotificationReadIntegrationTest` (7, real security chain + Postgres): real-workflow notification is unread then read (unread-count updates, `isSent` untouched); cross-user mark-read → 403, stays unread; unknown id → 404; idempotent over HTTP (no duplicate row); read-all user-scoped (only caller's rows); unread-count user-scoped; endpoints require auth.

**12. Focused test results (real, JBR JDK 25 via `./mvnw.cmd`, `--release 21`, live local Postgres).** `-Dtest=NotificationReadServiceTest` → **8/8, BUILD SUCCESS**. `-Dtest=NotificationReadIntegrationTest` → **7/7, BUILD SUCCESS**. Combined 15/15.

**13. Existing notification tests.** Regression group `-Dtest=NotificationReadServiceTest,NotificationReadIntegrationTest,NotificationUnsentAuthorizationTest,NotificationRetryServiceTest,ScheduledTasksRetryTest,EmailServiceTest,DefenseReminderNotificationTest,CommitteeServiceNotificationTest,DefenseServiceNotificationTest,DefenseResultServiceNotificationTest,ThesisVersionServiceNotificationTest,AutoAdvanceCommitteeReviewTest` → **56/56, BUILD SUCCESS**.

**14. Full backend suite.** `./mvnw.cmd test` → **Tests run: 235, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS.** Baseline was 220; the +15 are exactly the new P3.6 tests. (The previously-only-failing `login_wrongPassword_returns403` was fixed earlier 2026-08-18, so the suite is fully green.)

**15. Frontend build.** `npm run build` (`tsc -b && vite build`) → **EXIT 0**, `✓ 1851 modules transformed`, `dist/` emitted. No TS errors.

**16. Manual verification (live, actually executed).** Started the backend (`:8080`, health UP) and Vite (`:5173`). Live API (curl): student login → `unread-count=2`; list shows `read=false` on both rows; `PATCH {id}/read` → 200 with `read:true, sent:false`; second call → 200 (idempotent); mentor marking the student's notification → **403**; unknown id → **404**; unauthenticated → **403**; `unread-count` → **1**. Live browser (student): Notifications page shows the API-read row without an unread marker and the unread row with an "Unread" dot + "Mark read" button; header shows "Mark all as read (1)"; Sidebar shows a "1 unread" badge; clicking "Mark read" removed the row's unread styling and the mark-all button in place; navigating away and the Sidebar badge cleared (unread-count refetched → 0). No console errors.

**17. Security verification.** Ownership enforced server-side from the authenticated principal (not any client-supplied id) — confirmed by the 403 test AND the live mentor-vs-student 403. Unread-count and mark-all are user-scoped (`WHERE n.user = :user`) — confirmed by tests + live. Notification id alone cannot bypass ownership (403 with real auth). No endpoint accepts a userId as an authorization source. Endpoints require authentication (401/403 unauthenticated). No notification/thesis data made public; no new roles/authorities; no secrets introduced. `NotificationType` values and recipients unchanged. Grep confirms `isSent` semantics untouched and read/email paths fully separated.

**18. Working-tree verification.** This task changed ONLY: 6 backend production files (all `Notification*`/`notification` scoped), 2 new backend test files, 4 frontend files (`types/api.ts`, `notificationApi.ts`, `NotificationsPage.tsx`, `Sidebar.tsx`), and this CLAUDE.md. The many pre-existing uncommitted changes from prior tasks (backend + frontend) were preserved untouched. No `git reset/restore/checkout/clean/stash` was run.

**19. CLAUDE.md.** Added this dated entry; updated CURRENT NEXT STEP to mark P3.6 DONE and point at the next optional item. Historical entries preserved.

**20. Remaining issues.** None introduced. Pre-existing/known: the notification-retry multi-instance dedup limitation (P2.6, needs ShedLock — documented). The pre-existing latent frontend bug where `notification.isSent` reads a non-existent wire key (`isSent` vs actual `sent`) is UNCHANGED and out of scope — it is masked in dev because mail is disabled so all rows are unsent; my `read` field does NOT share this bug (frontend reads the correct `read` key, verified live).

**21. Exact next task (do NOT start).** A larger optional roadmap item: **P2.2** (ARCHIVE-role archive-notes editor), **P3.3** (CORS config for split deployment), or **P3.1** (RS256/JWKS external auth).

**22. Honesty notes.** ACTUALLY EXECUTED: all 15 new tests (focused), the 56-test notification/email/scheduled regression group, the full 235-test suite (all BUILD SUCCESS on JBR JDK 25 against live Postgres), `npm run build` (EXIT 0), a live curl API walkthrough, a live browser UI walkthrough, and a direct psql inspection of the `is_read` column + back-filled rows. STATICALLY VERIFIED: the read/email path separation (grep) and the exact changed-file set (`git status`). ENVIRONMENT: build ran on the JetBrains Runtime JDK 25 (compiled `--release 21`, standard for this repo); `@SpringBootTest`/manual run used the live local Postgres `diploma_system`, which was available.

_(Historical note: the sentence below described the state BEFORE the login-500 fix. It is retained for context; the login-500 failure it points at is now resolved.)_

**~~CURRENT NEXT STEP = fix the pre-existing `AuthIntegrationTest.login_wrongPassword_returns403` failure (wrong password → 500 instead of 4xx), OR the trivial frontend build cleanup.~~ P2.4 (workflow integration tests) is now DONE (2026-08-18) — see the dated P2.4 entry directly below.** All P1, all authorization P2, all config-secrets P2, the stale-frontend-text P2.7, the P2.6 notification-retry job, AND now the P2.4 workflow integration coverage are DONE. The thesis workflow now has REAL integration coverage: 3 new `@SpringBootTest` classes (16 tests) driving the full stack — controllers → services → repositories → the local PostgreSQL `diploma_system` DB — through the happy path, revision loop, rejection loop, the real committee auto-advance scheduled job, authorization boundaries, and invalid-transition guards. All 16 pass; full suite is **220 tests, 1 failure** (the SAME known pre-existing `AuthIntegrationTest.login_wrongPassword_returns403`). The single remaining failing test is now the recommended next task: the DB-backed login path returns 500 for a wrong password instead of a 4xx (an auth-exception-mapping gap in `GlobalExceptionHandler` — Spring Security's `BadCredentialsException` falls through to the generic 500 handler). Alternatively, the **trivial quick win**: delete the pre-existing unused `FileText` import in `praksa-frontend/src/features/versions/VersionUploader.tsx` (TS6133) to unblock a fully clean `npm run build`. Do NOT start either automatically.

> ~~P2.6 / notification retry job: `Notification` rows whose email send failed (or was skipped because mail is disabled) stayed `isSent=false` forever with no retry~~ — **DONE 2026-08-17** (added a `@Scheduled` `retryUnsentNotifications` job in `ScheduledTasksService` that delegates to `NotificationServiceImpl.retryUnsentNotifications`, which walks a bounded, age-filtered, oldest-first page of unsent rows and re-dispatches each via the existing `EmailService.sendAsync` — no new rows, nothing marked sent by the retry itself). See the dated P2.6 entry directly below.

> ~~P2.7 / stale defense reschedule text: `DefenseSection.tsx` cancel toast said "mentor can reschedule", contradicting the STUDENT_SERVICE-only reschedule rule (Item #6)~~ — **FIXED 2026-08-17** (corrected the toast copy to "Defense cancelled — Student Service can schedule a new one"; role gating was already correct — the Schedule/reschedule button is `isService`-only and mentors are never offered it). See the dated P2.7 entry directly below.

> ~~Over-broad application-PDF access: `downloadApplicationPdf` let ANY COMMITTEE user fetch any thesis's submitted application PDF (no membership/ownership check)~~ — **FIXED 2026-08-17** (now delegates to the shared `ThesisReadAccessPolicy.requireReadAccess`, so the PDF download authorizes against THIS specific thesis exactly like the other thesis-level reads; the bare COMMITTEE role no longer grants access). See the dated entry directly below.

> ~~Duplicate `DEFENSE_REMINDER` to the mentor (`ScheduledTasksService.sendDefenseReminders`)~~ — **FIXED 2026-08-17** (removed the explicit mentor notify; the mentor's `MENTOR_MEMBER` committee seat means the committee loop already notifies them exactly once, matching `scheduleDefense`/`cancelDefense`). See the dated entry at the very bottom of this file.

> ~~User enumeration + PII leak via `GET /api/users?role=`~~ — **FIXED 2026-08-17** (per-role authorization in `UserServiceImpl.getUsersByRole` before any repository query; purpose-specific `UserSummaryResponse`; mentor picker no longer exposes email/index/credits). See the dated entry near the bottom of this file.

The main roadmap (Items #1–#12) is COMPLETE, plus **Item #13 — STUDENT_SERVICE credit-management UI DONE 2026-08-17**, **Item #14 — `ELIGIBILITY_REJECTED` terminal dead-end RESOLVED 2026-08-17**, and **Item #15 — production-safe SMTP / BUG-6 FIXED 2026-08-17** (env-backed provider-agnostic mail config, `app.mail.enabled` gate, secrets removed from tracked config; see the dated Item #15 entry below). **BUG-2 / P0.2 (register privilege escalation) FIXED**, **read-side IDOR (P1) FIXED**, the **write-side grading IDOR (P1, superset of BUG-13) FIXED**, and **BUG-19 / P2 (`/api/notifications/unsent` no role check) FIXED 2026-08-17** (now STUDENT_SERVICE-only at the service boundary; see the dated BUG-19 entry below) — all 2026-08-17. The happy-path lifecycle (registration → eligibility → mentor → application → archive/service validation → in-progress → versions → committee → defense → grade → archive) is coherent and fully wired on both backend and frontend, and the notification email channel is now configurable and honest.

With BUG-6, BUG-19, the user-enumeration/PII leak, the duplicate `DEFENSE_REMINDER`, the over-broad `downloadApplicationPdf` access, **BUG-15 (secrets hardening)**, **P2.7 (stale defense reschedule copy)**, and now **P2.6 (notification retry job) all resolved**, **no P1, no authorization P2, no config-secrets P2, no UI-accuracy items, and no notification-reliability items remain**. Every thesis-level read plus the grade write is scoped to the specific thesis, the tracked config carries no inline secret defaults, the frontend copy matches the enforced backend workflow, and unsent notifications are now eventually retried. The recommended next task is now **P2.4 — workflow integration tests** (happy path + revision loop + rejection loop + committee auto-advance): the project has no integration coverage of the thesis workflow. Alternatively, a **trivial quick win** unblocks a fully clean `npm run build`: remove the pre-existing unused `FileText` import in `praksa-frontend/src/features/versions/VersionUploader.tsx` (TS6133). Do not start either automatically.

Other open P2/P3 items: a read/unread flag on notifications (P3.6), and the two pre-existing non-blocking issues below.

**Pre-existing issues (not introduced by any recent task):**
- ~~Frontend `npm run build` blocked by the `tsconfig.app.json` `baseUrl` deprecation (TS5101)~~ — **FIXED 2026-08-18** (removed `baseUrl`, kept a `paths`-only alias; `npm run build` now EXIT 0). The unused-`FileText`-import blocker (TS6133) was FIXED earlier 2026-08-18. Both `npm run build` blockers are gone.
- Backend `AuthIntegrationTest.login_wrongPassword_returns403` — per the dated "Authentication login 500 → 403" entry this was FIXED 2026-08-18 (suite reported 220/220); an older paragraph above still describes the pre-fix state and is retained for history.

---

**Frontend TS5101 — `baseUrl` removed, `paths`-only alias (2026-08-18). 🏁 `npm run build` now fully green.**

Config-only frontend fix. One config file changed (`praksa-frontend/tsconfig.app.json`) plus this CLAUDE.md entry. No application/component/hook/API/style change, no dependency change, no backend change, no authorization/workflow/notification change. All existing `@/…` imports still resolve identically.

- **Root cause of TS5101.** `tsconfig.app.json` set `"baseUrl": "."` (alongside `"paths": { "@/*": ["src/*"] }`). TypeScript 6.0.2 (the project's `typescript` version, `~6.0.2`) emits `error TS5101: Option 'baseUrl' is deprecated and will stop functioning in TypeScript 7.0`. `npm run build` runs `tsc -b && vite build`, so `tsc` failed on TS5101 and the whole build aborted — even though `vite build` alone passed.
- **Why `baseUrl` was NOT actually required.** The only reason `baseUrl` existed was to anchor the `@/*` path alias. But `tsconfig.app.json` uses `"moduleResolution": "bundler"`, and under bundler (and node16/nodenext) resolution TypeScript resolves non-rooted `paths` targets **relative to the tsconfig's own directory** when `baseUrl` is absent — so the alias works without `baseUrl`. Vite's alias is defined separately in `vite.config.ts` (`'@': path.resolve(__dirname, './src')`) and is completely independent of `baseUrl`, so removing it cannot desync TS and Vite. `ignoreDeprecations: "6.0"` was deliberately NOT used — it only silences the warning while keeping the soon-to-break `baseUrl`; removing `baseUrl` is the smaller, TS7-ready, genuinely-correct fix.
- **Exact change (`tsconfig.app.json`).** Removed the line `"baseUrl": ".",` and changed the alias target from `"@/*": ["src/*"]` to `"@/*": ["./src/*"]` (explicit `./` prefix so the intent — relative to this tsconfig's dir — is unambiguous). `paths` is preserved; `tsconfig.json` (references) and `tsconfig.node.json` were not touched (only `tsconfig.app.json` had `baseUrl`).
- **Alias / import verification.** `@/…` aliases are used 153× across 42 files. After the change: `npx tsc -p tsconfig.app.json --noEmit` → **EXIT 0, zero errors** (previously the sole error was TS5101); the full `vite build` transformed **1851 modules** successfully, proving every alias import still resolves. TypeScript and Vite remain consistent — both map `@/*` → `src/*` relative to the project root.
- **TypeScript result (real, executed):** `npx tsc -p tsconfig.app.json --noEmit` → **EXIT 0**, no TS5101, no other errors.
- **Production build result (real, executed):** `npm run build` (`tsc -b && vite build`) → **EXIT 0**, `✓ 1851 modules transformed`, `✓ built in ~9.5s`, `dist/` emitted (index.html + hashed css/js). No TS5101, no TS errors, Vite build successful.
- **Dev-server verification:** NOT performed. This is a compile-time module-resolution config change fully proven by a clean `tsc` + a successful `vite build`; a running dev server would exercise the same resolution and add nothing. No backend was needed for this frontend-only config task.
- **Backend:** untouched, not re-run. Documented baseline remains **220/220 tests passing** (0 failures, 0 errors) from the prior task — NOT freshly verified here.
- **Working tree:** the many pre-existing uncommitted frontend changes from prior tasks are preserved untouched. `git diff` shows `tsconfig.app.json` as the ONLY change introduced by this task (all other modified files pre-date it, including the earlier `VersionUploader.tsx` FileText fix). No `git reset/restore/checkout/clean/stash` was run.
- **Honesty:** ACTUALLY EXECUTED — `npx tsc -p tsconfig.app.json --noEmit` (EXIT 0) and `npm run build` (EXIT 0, 1851 modules). STATICALLY VERIFIED — that only `tsconfig.app.json` changed (`git diff --stat`), and the bundler-resolution rationale. NOT tested — a live dev-server/browser round-trip (unnecessary for a resolution-config change) and the backend suite (no backend change).
- **Exact next task (do NOT start):** a larger optional roadmap item — P2.2 (ARCHIVE-role archive-notes editor), P3.6 (notification read/unread flag), P3.3 (CORS config for split deployment), or P3.1 (RS256/JWKS external auth). No build blockers remain.

---

**Frontend build cleanup — unused `FileText` import removed (2026-08-18).**

Trivial, single-line frontend build hygiene fix. One file changed (plus this CLAUDE.md entry). No backend change, no functional/behavioral change, no dependency change, no authorization/workflow/notification change.

- **File changed:** `praksa-frontend/src/features/versions/VersionUploader.tsx` — line 2 import trimmed from `import { UploadCloud, FileText, Loader2 } from 'lucide-react'` to `import { UploadCloud, Loader2 } from 'lucide-react'`. Nothing else in the file touched (JSX, upload logic, validation, drag-and-drop, progress bar all unchanged).
- **Why it was safe:** `FileText` was imported but never referenced anywhere in the file — confirmed by reading the whole component and by `grep FileText VersionUploader.tsx` (the import line was the only match; the JSX renders only `UploadCloud` and `Loader2`). Removing an unused symbol cannot change runtime behavior. `lucide-react` stays a dependency (used elsewhere).
- **Build result (real, executed):** `npm run build` (`tsc -b && vite build`) — the `VersionUploader.tsx` TS6133 (`'FileText' is declared but its value is never read`) error is GONE. The build now fails on ONLY the pre-existing `tsconfig.app.json(25,5): error TS5101 baseUrl is deprecated` (a separate config issue, intentionally NOT fixed here). `npx tsc -p tsconfig.app.json --noEmit --ignoreDeprecations 6.0` → **EXIT 0** (zero errors), proving TS6133 is fully resolved and TS5101 is the sole remaining blocker.
- **TS5101 remains:** YES — still OPEN, unchanged. `tsconfig.app.json` was NOT modified. The full `npm run build` is not yet clean solely because of TS5101.
- **Backend:** untouched. Baseline remains **220/220 tests passing** (0 failures, 0 errors) — not re-run for this frontend-only change.
- **Working tree:** the many pre-existing uncommitted changes from prior tasks are preserved. `git diff src/features/versions/VersionUploader.tsx` shows exactly the one-line import change; no `git reset/restore/checkout/clean/stash` was run.
- **Honesty:** ACTUALLY EXECUTED — `npm run build` (confirmed TS6133 gone, only TS5101 left) and `npx tsc -p tsconfig.app.json --noEmit --ignoreDeprecations 6.0` (EXIT 0). NOT run — a live browser/dev-server round-trip (the change is a static unused-import removal with no observable UI effect) and the backend suite (no backend change).
- **Exact next task (do NOT start):** resolve the pre-existing `tsconfig.app.json` TS5101 `baseUrl` deprecation to make `npm run build` fully green — either add `"ignoreDeprecations": "6.0"` to the compiler options, or migrate off `baseUrl` to a `paths`-only alias setup for TypeScript 7 readiness. This is a separate configuration decision and was deliberately left untouched by this task.

---

**Authentication login 500 → 403 (wrong password) — FIXED (2026-08-18). 🏁 MILESTONE: backend suite now 220/220, 0 failures, 0 errors.**

Targeted authentication error-handling fix. Backend production change is ONE file (`GlobalExceptionHandler`); no test files were changed for this task. No change to JWT generation/validation, password hashing/encoder, user lookup, roles, authorization rules, DB schema, notifications, workflow, or the frontend.

**1. Overall verdict.** FIXED. Wrong-password login now returns **HTTP 403** (a 4xx authentication response) instead of HTTP 500. The previously-failing `AuthIntegrationTest.login_wrongPassword_returns403` passes, and the full suite is a clean **220 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**.

**2. Root cause (traced in source, not assumed).** `AuthServiceImpl.login` (`service/impl/AuthServiceImpl.java:67-71`) calls `authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, password))`. On a wrong password Spring Security's `DaoAuthenticationProvider` throws `org.springframework.security.authentication.BadCredentialsException` (a subclass of `org.springframework.security.core.AuthenticationException`). This exception propagates out of the service and controller unhandled. `GlobalExceptionHandler` had `@ExceptionHandler` methods for `MethodArgumentNotValidException`, `ResourceNotFoundException` (404), `BadRequestException` (400), `UnauthorizedException` (403), and a catch-all `@ExceptionHandler(Exception.class)` → **HTTP 500**. With no handler for `AuthenticationException`/`BadCredentialsException`, the wrong-password exception fell through to that generic handler and produced 500.

**3. Exact exception involved.** `org.springframework.security.authentication.BadCredentialsException` (handled via its superclass `org.springframework.security.core.AuthenticationException`).

**4. Before vs after.**
- Before: wrong password → `BadCredentialsException` → generic `@ExceptionHandler(Exception.class)` → **500** (`{"success":false,"message":"An unexpected error occurred"}`).
- After: wrong password → `BadCredentialsException` → new targeted `@ExceptionHandler(AuthenticationException.class)` → **403** (`{"success":false,"message":"Invalid email or password"}`).

**5. Exact files changed.**
- `src/main/java/com/praksa/exception/GlobalExceptionHandler.java` — added `import org.springframework.security.core.AuthenticationException;` and one new handler method (`handleAuthentication`) returning `403 FORBIDDEN` with a generic `ApiResponse.error("Invalid email or password")`, placed just above the catch-all `Exception` handler so the narrower type wins.
- `CLAUDE.md` — this entry + CURRENT NEXT STEP update.
- NO other files. `AuthIntegrationTest.java` was NOT modified by this task (its ` M` in git status is the pre-existing BUG-15 `@ActiveProfiles`/index-number edits).

**6. Implementation (minimal fix).** A single `@ExceptionHandler(AuthenticationException.class)` was added to the EXISTING `GlobalExceptionHandler`, reusing the existing `ApiResponse.error(...)` response body used by every other handler. No broad `catch (Exception)` was added inside `AuthServiceImpl.login`; the exception is not swallowed; no fake login response is returned. `AuthenticationException` (the Spring Security superclass) was chosen over only `BadCredentialsException` so that other authentication failures (disabled/locked accounts, etc.) also map to a 4xx auth response rather than 500 — all of them are authentication failures, and the type is narrow (Spring Security auth only), so it cannot accidentally catch unrelated exceptions.

**7. Why 403 (not 401).** The application's established auth-failure contract is 403, determined from the code, not the test name alone:
   - The sibling test `AuthIntegrationTest.protectedEndpoint_noToken_returns403` asserts **403** for unauthenticated access to a protected endpoint — Spring Security's filter chain returns 403 here, so 403 is already this app's "authentication failure" status.
   - The failing test is named `login_wrongPassword_returns403` and asserts `status().is4xxClientError()` (accepts any 4xx); 403 satisfies it and keeps the test name honest — no test change was needed.
   - Frontend contract (`praksa-frontend/src/api/client.ts` response interceptor): a **401** during login is effectively silent — its logout/toast branch is gated behind `if (useAuthStore.getState().token)`, and there is no token yet at login, so the user would see NO error feedback. A **403** hits the interceptor's dedicated branch that toasts `backendMessage` (here "Invalid email or password") WITHOUT logging out or redirecting — exactly the desired login-error UX. `LoginPage.tsx` relies on that interceptor toast (its `catch {}` is empty). So 403 is the status that actually works end-to-end for this app.

**8. User-enumeration protection preserved.** `DaoAuthenticationProvider` hides user-not-found by default (`hideUserNotFoundExceptions=true`), so both a nonexistent email and a wrong password throw the SAME `BadCredentialsException` before the code ever reaches the `findByEmail` lookup — the responses were already indistinguishable. The new handler returns a single generic message ("Invalid email or password") for all `AuthenticationException`s, so no new signal was introduced. No enumeration difference exists between "email does not exist" and "password is wrong".

**9. Tests added/updated.** None added or updated — the existing `AuthIntegrationTest.login_wrongPassword_returns403` (asserts `is4xxClientError`) is the exact regression test for this fix and now passes; `login_success` proves valid credentials still return 200 with a token; `registerPrivilegedRole_isCoercedToStudent` and the register tests cover the register path; `protectedEndpoint_noToken_returns403` confirms the filter-chain 403 is unaffected (it is produced by Spring Security before the DispatcherServlet, so `@RestControllerAdvice` never sees it — no interference from the new handler). Adding a further test would duplicate existing coverage, so none was added.

**10. Focused test results (real, JetBrains Runtime JDK 25 via `./mvnw.cmd`, project `--release 21`, live local PostgreSQL).** `./mvnw.cmd -Dtest=AuthIntegrationTest test` → **Tests run: 8, Failures: 0, Errors: 0, Skipped: 0** (all of `AuthIntegrationTest`, including the previously-failing `login_wrongPassword_returns403` and `login_success`).

**11. Full-suite results.** `./mvnw.cmd test` → **Tests run: 220, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS.** Baseline was 220 tests / 1 failure; the one failure (`login_wrongPassword_returns403`) is now green, giving the project's first clean full backend suite.

**12. Security verification.** Wrong password no longer returns 500 (now 403); invalid credentials return the intended 4xx; successful login unchanged (200 + token, `login_success` passes); JWT generation/validation unchanged (`JwtUtil` untouched); password hashing/encoder unchanged; roles/authorities unchanged; `SecurityConfig` authorization rules unchanged; no user-enumeration introduced (generic message; provider already hides user-not-found); no secrets/hard-coded credentials added; no notification or workflow behavior changed. `git diff` confirms the ONLY production file changed is `GlobalExceptionHandler.java` (+18 lines); a search of the diff shows no `JwtUtil`, `SecurityConfig`, password-encoder, role, or authorization changes.

**13. Frontend impact.** None. No frontend file changed. The existing axios interceptor already handles 403 correctly for the login page (toasts the backend message, no logout/redirect).

**14. Working-tree verification.** `git status` before and after confirms the many pre-existing uncommitted changes from prior tasks (BUG-15, P2.4, notification/workflow work, etc.) are untouched. `GlobalExceptionHandler.java` was NOT among the originally-modified files — it is the sole file this task modified. No `git reset/checkout/restore/clean/stash` was run.

**15. Remaining open bugs.** Frontend `npm run build` blockers (pre-existing, unrelated): unused `FileText` import in `praksa-frontend/src/features/versions/VersionUploader.tsx` (TS6133) and the `tsconfig.app.json` `baseUrl` TS5101 deprecation. P3.6 (notification read/unread flag). The intentionally-documented multi-instance notification-retry dedup limitation (P2.6, needs ShedLock). No backend test failures remain.

**16. Exact next task (do NOT start).** The trivial quick win: delete the unused `FileText` import in `praksa-frontend/src/features/versions/VersionUploader.tsx` to unblock a fully clean `npm run build`. (Larger optional items: P2.2 archive-notes editor, P3.6 notification read/unread flag, CORS config for split deployment, RS256/JWKS external auth — all future/optional.)

**17. Honesty notes.** ACTUALLY TESTED (executed on JBR JDK 25 against the live local PostgreSQL `diploma_system`): the focused `AuthIntegrationTest` (8/8) and the full 220-test suite (220/220, BUILD SUCCESS). STATICALLY VERIFIED: that only `GlobalExceptionHandler.java` was modified (`git diff`), and that the change contains no JWT/SecurityConfig/encoder/role/authorization edits. NOT tested: a live browser round-trip of the login-error toast (the assertion is covered by the integration test + the interceptor code inspection). ENVIRONMENT: build ran on the JetBrains Runtime JDK 25 rather than the project's Java 21 target (compiled with `--release 21`, standard for this repo); `@SpringBootTest` requires the live local Postgres, which was available.

---

**P2.4 — Workflow integration tests: DONE (2026-08-18).**

Adds the project's first REAL integration coverage of the thesis workflow. Test-only change: 4 new files under `src/test/java/com/praksa/integration/`, no production code touched, no schema/auth/authorization/notification/frontend change. Verdict: **FIXED** — the required integration scenarios (happy path, revision loop, rejection loop, committee auto-advance, authorization boundaries, invalid-state transitions) are implemented and executed green against the real Spring context + local PostgreSQL.

**1. Overall verdict.** FIXED. 16 new integration tests, all passing, exercising the full stack (controller → service → repository → PostgreSQL → transactions → authorization → workflow transitions → notification rows). They caught nothing broken — the workflow behaves correctly end-to-end — but they now guard it against regressions that the existing Mockito unit tests structurally cannot catch (real security filter, real JWT decode, real `@Transactional` behavior, real DB constraints, real `ThesisReadAccessPolicy`).

**2. Exact files changed (all NEW, test-only).**
- `src/test/java/com/praksa/integration/AbstractWorkflowIntegrationTest.java` — shared base: `@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional`; fixture-user factories (process-unique emails/index numbers), JWT minting, authenticated MockMvc helpers, one-call-per-business-operation step helpers, and compound "advance to state X" helpers, plus notification-count assertion helpers.
- `src/test/java/com/praksa/integration/ThesisWorkflowIntegrationTest.java` — Test 1 (happy path), Test 2 (revision loop), Test 3 (rejection loop). 3 tests.
- `src/test/java/com/praksa/integration/CommitteeWorkflowIntegrationTest.java` — Test 4 (committee formation + real auto-advance job) + completed-review guard + approve-committee role/count guard. 3 tests.
- `src/test/java/com/praksa/integration/AuthorizationWorkflowIntegrationTest.java` — Test 5 (authorization boundaries A–F) + Test 6 (invalid state transitions). 10 tests.
- `CLAUDE.md` — this entry + CURRENT NEXT STEP / references updates.
- NO other files. `AuthIntegrationTest.java` was NOT modified by this task (its ` M` in git status is the pre-existing BUG-15 `@ActiveProfiles` edit).

**3. Workflow architecture discovered (verified from source, authoritative state machine).**
`PENDING_ELIGIBILITY_CHECK` → (STUDENT_SERVICE eligibility approve) `TOPIC_SELECTION` → (STUDENT mentor-request) `PENDING_MENTOR_APPROVAL` → (assigned MENTOR decision): ACCEPT → `APPLICATION_SUBMITTED` (misnamed: not yet submitted); REJECT → `MENTOR_REJECTED_TOPIC` (mentor cleared); REQUEST_CHANGES → `MENTOR_REQUESTED_CHANGES` (revisionCount++, mentor kept, comment required) → (STUDENT revise-proposal) back to `PENDING_MENTOR_APPROVAL`. Then (STUDENT submit-application) → `PENDING_ARCHIVE_VALIDATION` → (ARCHIVE) approve → `PENDING_SERVICE_VALIDATION` / reject → `APPLICATION_REJECTED_BY_ARCHIVE`; (STUDENT_SERVICE) approve → `IN_PROGRESS` / reject → `APPLICATION_REJECTED_BY_SERVICE`. Resubmission from EITHER rejection status always restarts at `PENDING_ARCHIVE_VALIDATION` (archive-first invariant). `IN_PROGRESS` → (STUDENT upload version + mark-final) `FINAL_SUBMITTED` → (MENTOR approve-final) `MENTOR_APPROVED` → (MENTOR propose committee: auto-seats mentor as MENTOR_MEMBER + 2 FORMAL_MEMBERs, no status change) → (STUDENT_SERVICE approve committee, requires exactly 3) `COMMITTEE_REVIEW` (stamps `committeeReviewStartedAt`) → EITHER (STUDENT_SERVICE accept-review) OR (scheduled `autoAdvanceStaleCommitteeReviews` after 5 business days) → `COMMITTEE_ACCEPTED` → `PENDING_DEFENSE_CHECK` → (STUDENT_SERVICE verify defense-eligibility, BOTH booleans true) `PENDING_DEFENSE_SCHEDULING` → (STUDENT request defense: signal only, no row, no status change) → (STUDENT_SERVICE schedule) `DEFENSE_SCHEDULED` → (seated committee professor record grade 5–10) `ARCHIVED` (assigns `DT-YYYY-NNNN`, archiveDate, archivedBy). Role ownership: STUDENT owns create/mentor-request/revise/submit/version/mark-final/request-defense/cancel; assigned MENTOR owns mentor-decision/approve-final/propose-committee/cancel; STUDENT_SERVICE owns eligibility/service-validate/approve-committee/accept-review/verify-defense-eligibility/schedule; ARCHIVE owns archive-validate; grading is restricted to a seated committee professor of THAT thesis (not by role). **Note (adapted from the brief):** there is NO per-member "one active member at a time" sequential activation — the brief's Test-4 sketch doesn't match the code; the real model seats all 3 at once, each submits notes independently, and completion is whole-committee (manual accept or the 5-business-day auto-advance job). The tests were written to the REAL model and this divergence is documented in `CommitteeWorkflowIntegrationTest`'s class Javadoc.

**4. Integration tests added (what each proves).**
- **Test 1 — happy path** (`happyPath_fullLifecycle`): one thesis walks create → eligibility → mentor accept → submit → archive approve → service approve → version+mark-final → mentor approve-final → propose committee → approve committee → submit review → accept review → verify defense eligibility → request → schedule → grade → ARCHIVED. Asserts persisted status after EVERY transition, plus: deadline anchored on create; mentor assignment; application PDF path set on submit; 3 committee seats with exactly 1 MENTOR_MEMBER (the mentor) + 2 FORMAL_MEMBER; `committeeReviewStartedAt` stamped and all seats `approvedBy`-stamped on approval; review notes persisted on the seat; no Defense row created by a request; Defense row (room/thesis/not-cancelled) on schedule; DefenseResult grade + `DT-` registration number + archiveDate + archivedBy on grading. Notification ROWS asserted at each step (ELIGIBILITY_APPROVED, MENTOR_REQUEST_RECEIVED, MENTOR_ACCEPTED_TOPIC, APPLICATION_PENDING_ARCHIVE, APPLICATION_PENDING_SERVICE, APPLICATION_VALIDATED, FINAL_VERSION_SUBMITTED, MENTOR_APPROVED_THESIS, COMMITTEE_FORMED (student + mentor once), COMMITTEE_REVIEW_ACCEPTED, DEFENSE_ELIGIBILITY_VERIFIED, DEFENSE_REQUESTED, DEFENSE_SCHEDULED, THESIS_GRADED, THESIS_ARCHIVED).
- **Test 2 — revision loop** (`revisionLoop_requestChangesThenReviseThenAccept`): REQUEST_CHANGES → `MENTOR_REQUESTED_CHANGES` with `revisionCount=1`, mentor retained, MENTOR_REQUESTED_CHANGES notification; revise → `PENDING_MENTOR_APPROVAL`, title updated, same owner + mentor, `revisionCount` unchanged by the student action, STUDENT_RESUBMITTED_PROPOSAL to mentor; loop then continues normally (mentor ACCEPT). Plus: an unrelated STUDENT cannot revise (ownership → 403).
- **Test 3 — rejection loop** (`rejectionLoop_archiveRejectsThenStudentResubmits`): reject-without-comment → 400 with no transition/history; a non-ARCHIVE user cannot reject → 403; archive reject with reason → `APPLICATION_REJECTED_BY_ARCHIVE`, `archiveComment` stored, notification; student resubmit → `PENDING_ARCHIVE_VALIDATION`; also drives the SERVICE-reject branch → `APPLICATION_REJECTED_BY_SERVICE` (comment stored, notification) and proves resubmission restarts at archive; finally completes to `IN_PROGRESS`.
- **Test 4 — committee auto-advance** (`autoAdvance_staleCommitteeReview_advancesAndNotifies`): drives to `COMMITTEE_REVIEW` via the real workflow; a fresh review is a no-op for the real `ScheduledTasksService.autoAdvanceStaleCommitteeReviews()`; backdating `committeeReviewStartedAt` (a timestamp-only simulation of elapsed time, NOT a status change) then running the REAL job advances `COMMITTEE_REVIEW → COMMITTEE_ACCEPTED → PENDING_DEFENSE_CHECK`; both auto transitions recorded with `changedBy=null`; COMMITTEE_REVIEW_AUTO_ADVANCED sent once each to student + mentor (via MENTOR_MEMBER seat, no duplicate) + both formal members; a second run is idempotent (no further transitions/notifications). Companion tests: a seated member cannot submit review notes once the review is complete (status left COMMITTEE_REVIEW → 400, notes unchanged); approve-committee requires exactly 3 members AND STUDENT_SERVICE (mentor approve → 403, seats stay unstamped; re-propose after a committee exists → 400).
- **Test 5 — authorization boundaries** (6 tests): (A) unrelated student cannot mentor-request another's thesis (403, no mentor assigned); (B) a non-assigned mentor cannot decide the request (403); (C) a committee member seated on thesis A cannot grade thesis B's defense (cross-thesis seat check → 403, B stays DEFENSE_SCHEDULED, no result); (D) schedule-defense is STUDENT_SERVICE-only (mentor AND student → 403, no Defense row; service succeeds); (E) knowing the thesis UUID does not grant a transition (COMMITTEE user eligibility → 403; non-owner student submit-application → 403); (F) cannot skip ahead (approve-committee on a fresh thesis → 400; approve-final before FINAL_SUBMITTED → 400).
- **Test 6 — invalid state transitions** (4 tests), each asserting the DB stays consistent: resubmit an already-submitted application → 400, status/history/ARCHIVE-notification-count unchanged; approve-final too early → 400, status/history unchanged; schedule defense before ready → 400, no Defense row; partial defense-eligibility (one boolean false) → 400, no transition, no history row, no DEFENSE_ELIGIBILITY_VERIFIED notification (and a student cannot request a defense while still PENDING_DEFENSE_CHECK → 400).

**5. PostgreSQL / test environment.** Real local PostgreSQL 18 `diploma_system` on `localhost:5432` (the same DB the app uses), reached through the full Hibernate/JPA stack — nothing mocked at the repository or DB layer. `@SpringBootTest` loads the entire application context (all beans, the real Spring Security filter chain, the real `ScheduledTasksService`); `@AutoConfigureMockMvc` drives real HTTP endpoints without a socket. `@ActiveProfiles("test")` supplies the BUG-15 throwaway secrets so the context boots. Every state assertion is a genuine `repository.findById(...)` read against Postgres inside the test transaction. The `@Async` `emailTaskExecutor` runs but, with `app.mail.enabled=false` (inherited), skips SMTP and touches no DB — so notification ROWS remain synchronously persisted by `NotificationServiceImpl.notify` in the caller's transaction and are asserted directly.

**6. Authentication and authorization coverage.** Uses the project's real JWT mechanism, not a test shortcut: a fixture user is persisted, then `JwtUtil.generateToken(email, role)` mints a token exactly as `AuthServiceImpl` does at login, sent as `Authorization: Bearer`; the real `JwtAuthFilter` decodes it and `SecurityUtils.getCurrentUser()` resolves the DB row, so each call executes as the correct principal. Public registration is STUDENT-only (BUG-2), so privileged fixture users (MENTOR/STUDENT_SERVICE/ARCHIVE/COMMITTEE) are created directly via `UserRepository` (the administrative provisioning path) — matching how the app itself seeds them. Authorization tested at the real `ThesisReadAccessPolicy` / service-guard layer: role gates (eligibility/validate/schedule/approve-committee), ownership gates (mentor-request/revise/submit), assigned-mentor gate (mentor-decision/approve-final), and thesis-scoped committee-seat gate for grading (cross-thesis IDOR). No authorization logic is duplicated in the tests — they prove the running app rejects the operation with the real status code (403 vs 400).

**7. Notification coverage.** For each workflow event the test asserts the notification ROW(s): correct `type` (enum name string), correct recipient `user`, and correct owning `thesis`. Verified single-delivery invariants that unit tests can't (mentor notified exactly once via the MENTOR_MEMBER seat for COMMITTEE_FORMED, COMMITTEE_REVIEW_ACCEPTED, DEFENSE_SCHEDULED, and the auto-advance job). Verified NEGATIVE cases: no duplicate APPLICATION_PENDING_ARCHIVE on a rejected re-submit; no DEFENSE_ELIGIBILITY_VERIFIED on a failed partial verification. No real email is sent (mail disabled); notification TYPES/RECIPIENTS were only asserted, never changed.

**8. Database / state assertions.** Beyond HTTP status: thesis `status` after every transition; thesis owner + assigned mentor; `submissionDeadline` set on create; `applicationPdfPath` set on submit; `revisionCount` semantics; `archiveComment`/`serviceComment` reason storage; committee seat count + `memberRole` split + `approvedBy` stamping; `committeeReviewStartedAt` stamping; review-notes persistence; absence of a Defense row where the flow forbids one; Defense row fields (room/thesis/not-cancelled); DefenseResult grade; archive metadata (`DT-` registration number, archiveDate, archivedBy); `ThesisStatusHistory` row counts (added on valid transitions, NOT added on rejected ones) and `changedBy=null` for system auto-advance.

**9. Focused test results (real, JetBrains Runtime JDK 25 via `./mvnw`, project `--release 21`).**
`./mvnw -Dtest='ThesisWorkflowIntegrationTest,CommitteeWorkflowIntegrationTest,AuthorizationWorkflowIntegrationTest' test` →
- `ThesisWorkflowIntegrationTest` — **Tests run: 3, Failures: 0, Errors: 0, Skipped: 0.**
- `CommitteeWorkflowIntegrationTest` — **Tests run: 3, Failures: 0, Errors: 0, Skipped: 0.**
- `AuthorizationWorkflowIntegrationTest` — **Tests run: 10, Failures: 0, Errors: 0, Skipped: 0.**
- Combined **16/16 pass, BUILD SUCCESS** for the focused run.

**10. Existing workflow tests re-run (regression guard, all green in the full run).** `AutoAdvanceCommitteeReviewTest` (5), `DefenseReminderNotificationTest` (4), `CommitteeServiceNotificationTest` (3), `DefenseServiceNotificationTest` (3), `DefenseResultServiceNotificationTest` (2), `ThesisVersionServiceNotificationTest` (2), `DefenseRequestSchedulingTest` (14), `DefenseEligibilityVerificationTest` (7), `DefenseResultGradingAuthorizationTest` (8), `DefenseResultGradeValidationTest` (5), `ThesisReadIdorGuardTest` (12), `ThesisReadAccessPolicyTest` (10), `EligibilityRejectedNewThesisTest` (9), `ScheduledTasksBusinessDaysTest` (8) — **all pass**, unaffected.

**11. Full-suite results.** `./mvnw test` → **Tests run: 220, Failures: 1, Errors: 0, Skipped: 0.** Baseline was 204; the +16 are exactly the new integration tests. The single failure is the KNOWN PRE-EXISTING, unrelated `AuthIntegrationTest.login_wrongPassword_returns403` (wrong password → 500 instead of a 4xx; a DB-backed auth-exception-mapping issue that does not touch the workflow). NOT modified by this task (as instructed). Everything else — including the two `@SpringBootTest` context-load / auth tests that need live Postgres — passes. **Not "all green" solely because of that one pre-existing failure.**

**12. Bugs discovered.** None new. The workflow behaves correctly under real integration conditions; no production defect surfaced, so no production code was changed. The pre-existing `AuthIntegrationTest` login-500 failure is reconfirmed (unchanged, still open, now the recommended next task).

**13. Frontend impact.** None (expected). Backend test-only change.

**14. CLAUDE.md updates.** This dated entry added; CURRENT NEXT STEP rewritten to mark P2.4 DONE and point at the login-500 fix (or the trivial frontend build cleanup) as next; the P2 "useful" list and the historical P2.4 pointers updated below.

**15. Remaining open bugs.** `AuthIntegrationTest.login_wrongPassword_returns403` (500 vs 4xx) — pre-existing, only failing test. Frontend `npm run build` blockers (`VersionUploader.tsx` TS6133 unused import; `tsconfig.app.json` TS5101 `baseUrl`). P3.6 (notification read/unread flag). The intentionally documented multi-instance retry-dedup limitation (P2.6, needs ShedLock). No P1, no authorization P2, no config-secrets P2 remain.

**16. Exact next task.** Fix `AuthIntegrationTest.login_wrongPassword_returns403`: `GlobalExceptionHandler` has no handler for Spring Security's `AuthenticationException`/`BadCredentialsException` thrown by `authenticationManager.authenticate` in `AuthServiceImpl.login`, so a wrong password falls through to the generic `Exception → 500` handler. Add a targeted `@ExceptionHandler` mapping bad credentials to 401/403 (matching the test's `is4xxClientError`). Small, well-scoped, and clears the last failing test. (Alternative trivial quick win: remove the unused `FileText` import in `praksa-frontend/src/features/versions/VersionUploader.tsx`.)

**17. Honesty notes.** ACTUALLY TESTED (executed on JDK 25 / JBR against live Postgres): all 16 new integration tests (focused run + inside the full suite), plus the full 220-test suite. STATICALLY VERIFIED: that only the 4 new test files were added and no production/security/schema/notification/frontend file was modified (`git status`); the adapted committee model against the real source. NOT TESTED: real SMTP delivery (mail disabled by design); multi-instance behavior (single instance here); the pre-existing login-500 path (left failing, as instructed). ENVIRONMENT LIMITATIONS: build ran on the JetBrains Runtime JDK 25 rather than the project's Java 21 target (compiled with `--release 21`, standard for this repo); `@SpringBootTest` requires the live local Postgres, which was available; upload side-effect files land under the git-ignored `uploads/` directory (no tracked pollution).

---

**P2.6 — Notification retry job for unsent emails: DONE (2026-08-17).**

Adds a small, production-safe scheduled retry for `Notification` rows stuck `isSent=false` (send failed, or mail was disabled when the row was created). Reuses the existing notification/email architecture end-to-end — no second email implementation, no new notification-creation path, no schema change, no change to notification types/recipients/bodies or any workflow. Backend-only; frontend untouched.

**Existing architecture discovered (verified against source, not assumed).**
- `NotificationServiceImpl.notify(recipient, thesis, type[, custom])` saves a `Notification` row (`isSent=false`) **synchronously in the caller's transaction**, extracts primitives (id, recipient email, subject via `buildSubject`, body via `buildBody`), then calls `EmailService.sendAsync(id, email, subject, body)`. `notifyRole` fans out via `findByRole`. This is the ONLY notification-creation path (grep-confirmed: exactly one `Notification.builder()` site).
- `EmailService.sendAsync(UUID, String, String, String)` is `@Async("emailTaskExecutor")` + its own `@Transactional`. If `app.mail.enabled=false` it logs and returns **before** touching SMTP, leaving the row `isSent=false`. If enabled: builds `SimpleMailMessage`, `mailSender.send`, and on success calls `markNotificationSent` (`setSent(true)`/`sentAt`, saving the SAME row via `notificationRepository` directly to avoid a NotificationService↔EmailService cycle). On `MailException` it logs and swallows — row stays `isSent=false`. `setSent(true)` exists **only** here.
- `Notification` stores `user` (LAZY, non-null), `thesis` (LAZY, nullable), `type` (String enum name), `isSent`, `sentAt`, `createdAt`. The custom message body is NOT persisted.
- `NotificationRepository` already had `findByIsSentFalse()` (used only by the STUDENT_SERVICE oversight endpoint `getUnsentNotifications`). `ScheduledTasksService` had three `@Scheduled(fixedDelay=30min)` jobs. No retry job or retry config existed.

**Retry implementation (exact behavior).**
- **New repository query** `NotificationRepository.findByIsSentFalseAndCreatedAtBeforeOrderByCreatedAtAsc(OffsetDateTime cutoff, Pageable)` — bounded (page limit), oldest-first, age-filtered. The existing `findByIsSentFalse()` is unchanged and still used by `getUnsentNotifications`.
- **New service method** `NotificationServiceImpl.retryUnsentNotifications(int batchSize, OffsetDateTime createdBefore)` (`@Transactional(readOnly=true)`, added to the `NotificationService` interface): loads the bounded page; for each row, inside a **per-notification try/catch**, rebuilds `type = NotificationType.valueOf(row.type)`, reads `user`/`thesis` (lazy loads happen inside this read tx), extracts primitives, rebuilds subject/body via the SAME `buildSubject`/`buildBody` (using `type.getDefaultBody()`), and calls `emailService.sendAsync(...)`. Returns the count dispatched. It performs **zero** DB writes itself and creates **no** new rows.
- **New scheduled job** `ScheduledTasksService.retryUnsentNotifications()` — a thin, non-`@Transactional` delegator: computes `cutoff = now − retryMinAgeMs` and calls `notificationService.retryUnsentNotifications(retryBatchSize, cutoff)`, wrapped in a top-level try/catch so a run failure can't kill the scheduled thread.

**Scheduling / configuration (new `notification.retry.*`, all `${ENV:default}`).**
- `notification.retry.interval-ms` (default **900000 = 15 min**) → `@Scheduled(fixedDelayString=…)`. Deliberately unaggressive, consistent with the other jobs' 30-min cadence; nothing near "every few seconds".
- `notification.retry.initial-delay-ms` (default **150000 = 150s**) → sequenced after the three existing jobs (60/90/120s).
- `notification.retry.batch-size` (default **100**) → the page limit; bounds async fan-out on a backlog.
- `notification.retry.min-age-ms` (default **120000 = 2 min**) → the age threshold cutoff.
No secrets; safe dev defaults; documented in `application.properties`.

**Batching.** Bounded by `batch-size` via `PageRequest.of(0, batchSize)`. A large backlog drains a page per run rather than fanning out unbounded async sends in a single pass. No queueing infra (Redis/Kafka/RabbitMQ) — intentionally out of scope.

**Duplicate-send / concurrency protection.**
1. `findByIsSentFalse…` never returns already-sent rows — sent notifications are excluded by construction (Test 4).
2. The **age threshold** (`min-age-ms`, default 2 min) means a freshly created notification is not retried until its original normal-flow `sendAsync` has definitively succeeded (→ `isSent=true`, excluded) or failed (→ still false, legitimately needs retry). This is the smallest reasonable guard against racing/duplicating the normal flow's in-flight send.
3. `@Scheduled(fixedDelay)` runs on Spring's single-threaded scheduler, so two retry runs never overlap **within one instance** (fixedDelay, not fixedRate).
4. `EmailService` remains the single authority that flips `isSent=true` — and only on a genuine successful send. The retry never marks sent itself.
- **Documented limitation:** across MULTIPLE app instances each scheduler could pick up the same row and double-send; preventing that needs a shared lock (e.g. ShedLock), which is not present in this project. Acceptable at current single-instance scale; called out rather than silently ignored.

**Mail-disabled behavior.** Retry delegates to the SAME `sendAsync`, so when `app.mail.enabled=false` it performs **no** SMTP connection and marks **nothing** sent — rows stay `isSent=false` and are naturally picked up again once mail is enabled. Retry introduces no second interpretation of `MAIL_ENABLED` (Test 7 + Test 8b, proven with the REAL `EmailService`).

**Failure behavior.** An SMTP `MailException` is caught inside `sendAsync` (row stays unsent); a malformed row (e.g. unknown type) is caught by the retry loop's per-notification try/catch; an unexpected run error is caught by the job's top-level catch. One failure never aborts the batch or the schedule (Tests 3, 9).

**Async / transaction safety.** The retry method reads lazy `user`/`thesis` while its own read tx is open, extracts primitives, and hands only primitives to the async layer — mirroring `notify()` and avoiding `LazyInitializationException`. The scheduled method is intentionally non-transactional (no open persistence context in the scheduler thread); the service method owns the tx.

**Custom-message limitation (documented).** The original custom body (e.g. the room/time in a `DEFENSE_SCHEDULED` message) is not persisted on the row, so a retried email uses the type's default body. Faithful reconstruction would require persisting the body (a schema change, explicitly out of scope). Honest and acceptable per the task.

**Files changed (P2.6 only).**
- `src/main/java/com/praksa/repository/NotificationRepository.java` — added the bounded age-filtered retry query.
- `src/main/java/com/praksa/service/NotificationService.java` — added `retryUnsentNotifications` to the interface.
- `src/main/java/com/praksa/service/impl/NotificationServiceImpl.java` — implemented `retryUnsentNotifications` (reuses `buildSubject`/`buildBody`, delegates to `EmailService.sendAsync`).
- `src/main/java/com/praksa/service/ScheduledTasksService.java` — new `@Scheduled retryUnsentNotifications` job + two `@Value` config fields.
- `src/main/resources/application.properties` — new `notification.retry.*` block.
- `src/test/java/com/praksa/service/NotificationRetryServiceTest.java` — **new** (11 tests).
- `src/test/java/com/praksa/service/ScheduledTasksRetryTest.java` — **new** (3 tests).
- `CLAUDE.md` — this entry + reference-section updates.

**Tests added.**
- `NotificationRetryServiceTest` (11, real `EmailService` + mocked collaborators, so genuine send/mark-sent/disabled semantics are exercised — `@Async` runs inline without a Spring proxy, making outcomes observable synchronously): Test 1 unsent discovered via the bounded age-filtered query and dispatched; Test 2 successful send marks the SAME row `isSent=true` with correct recipient/subject/default-body; Test 3/9 SMTP failure on one leaves it unsent and the next still processed; Test 9 malformed (unknown type) row skipped, batch continues; Test 4 already-sent excluded by the `isSentFalse` query (`findByIsSentFalse`/`findAll` never used); Test 5 empty queue no-op; Test 6 multiple rows each dispatched once to the correct recipient; Test 7 mail-disabled sends nothing and marks nothing; Test 8 the only save is `EmailService`'s mark-sent of the SAME entity (never a new row); Test 8b disabled → zero saves; plus batch-size passed as the page limit.
- `ScheduledTasksRetryTest` (3, mocked `NotificationService`): Test 10 configured batch size + `now−minAge` cutoff wired through; a different batch size flows through; a delegate failure is swallowed so the scheduled thread survives.

**Focused test results (real, JetBrains Runtime JDK 25 via `./mvnw`, project targets Java 21 → `--release 21`; Mockito self-attaches with the usual JDK-25 warning).**
- `./mvnw -Dtest=NotificationRetryServiceTest,ScheduledTasksRetryTest test` → **Tests run: 14, Failures: 0, Errors: 0, BUILD SUCCESS.**

**Existing notification/scheduled tests re-run (regression guard).**
- `EmailServiceTest` (3), `NotificationUnsentAuthorizationTest` (5), `DefenseReminderNotificationTest` (4), `AutoAdvanceCommitteeReviewTest` (5), `ScheduledTasksBusinessDaysTest` (8), `DefenseServiceNotificationTest` (3), `DefenseResultServiceNotificationTest` (2), `CommitteeServiceNotificationTest` (3), `ThesisVersionServiceNotificationTest` (2) → **35/35 pass, BUILD SUCCESS.** The normal notification workflow is unaffected.

**Full-suite result.**
- `./mvnw test` → **204 tests, 1 failure, 0 errors.** The single failure is the KNOWN PRE-EXISTING, unrelated `AuthIntegrationTest.login_wrongPassword_returns403` (wrong password → 500 instead of 4xx; a DB-backed auth-exception-mapping issue that does not touch the notification path). Previous full-suite baseline was 190; the +14 are exactly the new retry tests. NOT clean solely because of that pre-existing failure — not caused or affected by this change.

**Security / correctness verification (grep + diff).** Exactly one `Notification.builder()` creation site (the normal `notify()` path); retry creates no rows. `setSent(true)` exists only in `EmailService.markNotificationSent`; retry never marks sent. `ScheduledTasksService` contains no `SimpleMailMessage`/`mailSender`/`JavaMailSender` — retry does not bypass `EmailService`. `MAIL_ENABLED=false` never yields `isSent=true` (real-EmailService Test 7/8b). Notification types, recipients, and bodies unchanged; the three existing scheduled jobs unchanged; no `DEFENSE_REMINDER` duplication reintroduced; no authorization rule touched; no secret introduced.

**Frontend impact.** None — backend-only; no API contract, DTO, or endpoint change.

**Honesty notes.** Actually executed: all 14 new tests, the 35 existing notification/scheduled tests, and the full 204-test suite (JDK 25 / JBR). Statically verified: the grep/diff invariants above. NOT tested: a real end-to-end SMTP retry over the wire (needs `MAIL_ENABLED=true` + a live relay) and multi-instance duplicate behavior (no clustered deployment here — documented limitation). Environment: build ran on the JetBrains Runtime JDK 25 rather than the project's Java 21 target (compiled with `--release 21`); `@SpringBootTest`/`AuthIntegrationTest` still need a live Postgres, which was available.

---

**P2.7 — Stale frontend defense reschedule text: FIXED (2026-08-17).**

Frontend-only copy correction so the defense UI accurately reflects the enforced backend workflow. No backend change, no authorization change, no API-contract change, no change to any other defense behavior.

**Backend workflow re-verified against source (not assumed).** `DefenseServiceImpl.scheduleDefense` (`praksa/src/main/java/com/praksa/service/impl/DefenseServiceImpl.java:102-104`) begins with `requireRole(service, Role.STUDENT_SERVICE)` — scheduling AND rescheduling are STUDENT_SERVICE-only (the same method handles both the first schedule from `PENDING_DEFENSE_SCHEDULING` and a reschedule from `DEFENSE_SCHEDULED`). The class comment (lines 95-97) states explicitly "Only STUDENT_SERVICE schedules … Mentors can NOT schedule." `cancelDefense` is the only defense action a MENTOR (or the student owner) may perform. So a mentor genuinely cannot reschedule — the backend is correct and was NOT touched.

**The stale text (the one and only misleading string).** `praksa-frontend/src/features/defense/DefenseSection.tsx`, in `handleCancel`, the success toast read:
`toast.success('Defense cancelled — mentor can reschedule')`.
This told whoever cancelled (a student or a mentor) that the mentor would reschedule, contradicting the STUDENT_SERVICE-only rule.

**Before → after.**
- Before: after cancelling a defense, the toast said "Defense cancelled — mentor can reschedule".
- After: the toast says "Defense cancelled — Student Service can schedule a new one".

**Role gating — inspected, already correct, NOT changed.** The component never offered a reschedule action to a mentor:
- `canSchedule = isService && (isScheduling || (thesis.status === 'DEFENSE_SCHEDULED' && !defense))` (`DefenseSection.tsx:68`) — the Schedule/reschedule button is gated to STUDENT_SERVICE only. After a cancel, the active defense is gone, so a STUDENT_SERVICE user correctly sees "Schedule Defense" to book a new slot; a mentor/student never sees it.
- `canCancel = (isStudent || isMentor) && defense && !defense.isCancelled` (`DefenseSection.tsx:69`) — cancel is correctly available to the student owner or assigned mentor, matching `cancelDefense`.
- The `// Only STUDENT_SERVICE schedules … or to reschedule after a cancellation.` comment (`DefenseSection.tsx:67`) was already accurate.
No gating logic was modified; only the toast string changed. A broader search of the frontend (`reschedule`, `scheduleDefense`, `canReschedule`, `schedule defense`, `mentor can`) found no other stale copy — `ScheduleDefenseModal.tsx` and `DefensesPage.tsx` only expose scheduling to `isService`.

**Files changed.**
- `praksa-frontend/src/features/defense/DefenseSection.tsx` — one line, the `handleCancel` success toast copy. Nothing else.

**Verification (real results).**
- `npx tsc -p tsconfig.app.json --noEmit --ignoreDeprecations 6.0` → the only error is the PRE-EXISTING TS6133 unused `FileText` import in `features/versions/VersionUploader.tsx` (a file not touched here); the changed `DefenseSection.tsx` type-checks clean.
- `npx vite build` → **EXIT 0, 1851 modules transformed, built successfully.**
- No live browser walkthrough: the toast only fires on a real cancel round-trip, which needs the running Spring backend (not runnable here); the change is a pure static-string edit fully covered by the type-check + production build.

**Pre-existing issues (unchanged, not introduced here).** The full `npm run build` (`tsc -b && vite build`) still trips the pre-existing `VersionUploader.tsx` TS6133 unused import and the `tsconfig.app.json` TS5101 `baseUrl` deprecation; backend `AuthIntegrationTest.login_wrongPassword_returns403` still fails (500 vs 4xx). None are related to this change.

---

**BUG-15 — inline secret defaults removed + local/external JWT key split: COMPLETE (2026-08-17).**

Completes the BUG-15 remainder (the SMTP half was done in Item #15). Config-hardening only — no change to authentication semantics, workflow, roles, authorization, schema, or the frontend.

**What was wrong.** `application.properties` still shipped LOCAL-DEV-ONLY inline fallbacks: `spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:123}` and `jwt.secret=${JWT_SECRET:4a8f3b…8f3b}`. The app could start with a known/static DB password and a known/static JWT signing key if the env vars were absent. Worse, `auth.external-enabled=true` reused that SAME `jwt.secret` to verify external tokens — so enabling external auth silently trusted the committed dev key.

**Exact configuration changes (`src/main/resources/application.properties`).**
- `spring.datasource.password=${SPRING_DATASOURCE_PASSWORD}` — inline `:123` default REMOVED. Unresolved placeholder → datasource creation fails → startup aborts (fail fast). `url`/`username` keep their non-secret local defaults.
- `jwt.secret=${JWT_SECRET}` — inline hex default REMOVED. Missing → `JwtUtil.validateKeyConfiguration()` (`@PostConstruct`) throws `IllegalStateException` at startup.
- `auth.external-enabled=${AUTH_EXTERNAL_ENABLED:false}` — now env-overridable, still false by default.
- `auth.external-jwt-secret=${EXTERNAL_JWT_SECRET:}` — **NEW** separate external signing key, empty default. Empty/unused in local mode (intentional; not security-sensitive). Required ONLY when external auth is enabled.

**Local vs production/external JWT behavior (`JwtUtil`).**
- The single `secret` field was split into `localSecret` (`jwt.secret`) and `externalSecret` (`auth.external-jwt-secret`), plus the existing `externalAuthEnabled`.
- `getKey()` now selects by mode: local mode → local key; external mode → external key. Sign AND verify use `getKey()`, so each mode is self-consistent (local login still round-trips; external tokens verify with the external key). External mode never touches the local/dev key.
- New `@PostConstruct validateKeyConfiguration()` fails fast when: local key missing/blank, OR `auth.external-enabled=true` while the external key is missing/blank (never falls back to the local key).
- Unchanged: token structure (`sub`/`role`/`iat`/`exp`), 24h expiration, `generateToken(email, role)`, claim extraction, HMAC-SHA. `JwtAuthFilter`, `SecurityConfig`, `AuthServiceImpl` untouched. Default local mode (`auth.external-enabled=false`) behaves exactly as before.

**Local development.** `application-local.properties.example` updated: `jwt.secret` and `spring.datasource.password` are now shown as REQUIRED placeholders (uncommented, `CHANGE_ME_…`), with an optional external-auth block. Developers supply real values in the git-ignored `application-local.properties` (loaded via the existing `spring.config.import`) or via env vars — a local fallback that is fully isolated from the committed production config. Placeholders only; no real secret committed.

**Required environment variables (production).** `SPRING_DATASOURCE_PASSWORD` (required), `JWT_SECRET` (required), `EXTERNAL_JWT_SECRET` (required only if `AUTH_EXTERNAL_ENABLED=true`). Optional: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `JWT_EXPIRATION_MS`, `AUTH_EXTERNAL_ENABLED`.

**Tests (all real results, JetBrains Runtime JDK 25 via `./mvnw`, `--release 21`).**
- **NEW** `src/test/java/com/praksa/security/JwtUtilBug15Test.java` — 6 tests: local round-trip preserves email+role; expired token invalid (expiry preserved); external mode rejects a locally-signed token (no dev-secret reuse); external mode accepts an externally-signed token; missing local key fails fast; external-enabled-without-key fails fast. → **6/6 pass.**
- **NEW** `src/test/java/com/praksa/config/ProductionConfigSecretsTest.java` — 3 tests inspecting the committed `application.properties`: DB password is a bare required placeholder (no inline default, old `:123` gone); `jwt.secret` is a bare required placeholder (old hex default gone); external key is a distinct property defaulting to empty and never falling back to `jwt.secret`. → **3/3 pass.**
- **NEW** `src/test/resources/application-test.properties` + `@ActiveProfiles("test")` on `PraksaApplicationTests` and `AuthIntegrationTest` — supplies throwaway test secrets so the full context still loads now that the production defaults are gone. (Test-only values, not runtime secrets.)
- **Full suite** `./mvnw test`: **190 tests, 1 failure, 0 errors.** The single failure is the KNOWN PRE-EXISTING `AuthIntegrationTest.login_wrongPassword_returns403` (wrong password → 500 instead of 4xx; an auth-exception-mapping issue, unrelated to secrets). All other tests — including `PraksaApplicationTests.contextLoads` and the rest of `AuthIntegrationTest` (login_success, register flows) which need a live Postgres — **passed**, confirming the app boots cleanly with the inline defaults removed.

**Security grep (post-change, tracked files).** Old JWT hex default `4a8f3b…` → gone everywhere. No hard-coded JWT secret in code (only the required placeholder + comments + clearly-labeled test strings). No inline `:123` DB default in tracked config. Only literal `123` remaining is in `src/test/resources/application-test.properties` (throwaway test value = documented local dev DB password, not a production secret).

**Files changed (BUG-15 only).**
- `src/main/resources/application.properties` — removed inline DB/JWT defaults; added `auth.external-jwt-secret`; `AUTH_EXTERNAL_ENABLED`.
- `src/main/java/com/praksa/security/JwtUtil.java` — local/external key split + `@PostConstruct` fail-fast guard.
- `application-local.properties.example` — required-placeholder section + external-auth block.
- `src/test/resources/application-test.properties` — **new** (test secrets).
- `src/test/java/com/praksa/PraksaApplicationTests.java`, `src/test/java/com/praksa/integration/AuthIntegrationTest.java` — added `@ActiveProfiles("test")`.
- `src/test/java/com/praksa/security/JwtUtilBug15Test.java`, `src/test/java/com/praksa/config/ProductionConfigSecretsTest.java` — **new** focused tests.

**Known issues remaining (unchanged by this task).** Pre-existing `AuthIntegrationTest.login_wrongPassword_returns403` (500 vs 4xx). `JwtUtil` is still symmetric HMAC only — no JWKS/public-key support for a real external IdP (documented scaffold limitation). Stale frontend `DefenseSection.tsx` reschedule copy (now the CURRENT NEXT STEP). Notification retry (P2.6) and workflow integration tests (P2.4) still not built.

---

**Local development startup restored after BUG-15 (2026-08-17).** Environment/config only — no code, security, or workflow change; BUG-15 production hardening stays fully intact.

- **Symptom.** Frontend (`:5173`) showed the generic `Server error. Please try again later.` toast (`praksa-frontend/src/api/client.ts`, the `status >= 500` branch) on every `/api` call. Root cause: the backend was not running on `:8080`, so the Vite `/api` proxy had no target. Not a frontend bug. The backend could not start because BUG-15 removed the inline `jwt.secret` / DB-password defaults and the git-ignored `application-local.properties` did not exist yet.
- **Fix.** Created `application-local.properties` **at the project root** (next to `pom.xml` — the path the existing `spring.config.import=optional:file:./application-local.properties` loads; NOT under `src/main/resources`), from `application-local.properties.example`. It supplies `jwt.secret` (freshly generated strong random local key), `spring.datasource.password` (the documented local dev value, see §1 / `application-test.properties`), and keeps `app.mail.enabled=false`. The file is git-ignored (`.gitignore:8`) and holds the only copy of the local secrets — no secret was added to any tracked file or to this document.
- **Runtime.** No JDK on PATH; used the IntelliJ JetBrains Runtime (`…\IntelliJ IDEA 2026.1.1\jbr`, **JDK 25**) as `JAVA_HOME` with `./mvnw.cmd spring-boot:run`. Project targets Java 21; it compiles and runs cleanly under JDK 25 via `--release 21`.
- **Verified.** PostgreSQL 18 running on `:5432`; `diploma_system` already exists (not recreated); `postgres` authenticates. Backend booted ("Started PraksaApplication", Tomcat on 8080); `/actuator/health` → `200 {"status":"UP"}`. Login through the Vite proxy (`:5173/api/auth/login`, seed user `student@test.com`) → `200` with a valid JWT signed by the new local key; browser runtime fetch confirmed the same. The `Server error` toast is no longer caused by backend unavailability.
- **Tests.** `./mvnw test` (JDK 25): **190 tests, 1 failure, 0 errors, 0 skipped** — the single failure is the KNOWN PRE-EXISTING `AuthIntegrationTest.login_wrongPassword_returns403` (500 vs 4xx), intentionally left as-is.
- **BUG-15 still intact.** `application.properties` keeps `${SPRING_DATASOURCE_PASSWORD}` / `${JWT_SECRET}` with no inline defaults; `JwtUtil` fail-fast unchanged; generated local secret absent from all tracked files.

---

**Over-broad application-PDF access — `downloadApplicationPdf` allowed ANY COMMITTEE user: FIXED (2026-08-17).**

Closes the last remaining authorization P2. Downloading a thesis's generated application PDF is now authorized against the SPECIFIC requested thesis, reusing the existing `ThesisReadAccessPolicy` — no new permission framework, no new tables/columns, no duplicated seat logic, no `SecurityConfig` change.

**Existing behavior discovered (verified against source).**
- `GET /api/theses/{id}/application-pdf` → `ThesisController.downloadApplicationPdf` → `ThesisServiceImpl.downloadApplicationPdf(UUID)`. The thin controller only streams the returned `Resource`; all authorization is (and remains) at the service boundary. It is the ONLY caller of the service method (grep-verified across backend + frontend).
- The service method resolved the thesis via `findThesis(id)` and then authorized with an **inline role check**: allowed if owner STUDENT, assigned MENTOR, `STUDENT_SERVICE`, `ARCHIVE`, **or any `COMMITTEE`-role user**. The bare `COMMITTEE` clause meant any COMMITTEE user who knew (or guessed) a thesis UUID could download that thesis's application PDF regardless of whether they sat on its committee — over-broad, and inconsistent with the read endpoints (which had already been tightened to `ThesisReadAccessPolicy`) and with the seat-scoped record-PDF/grading policies.
- `ThesisReadAccessPolicy.requireReadAccess(thesis, user)` already encodes EXACTLY the policy required here (owner STUDENT / assigned MENTOR / committee member seated on THIS thesis via `existsByThesisAndProfessor` / STUDENT_SERVICE / ARCHIVE; everyone else 403). The policy was already injected into `ThesisServiceImpl` (used by `getThesisById` and `getStatusHistory`).

**Exact fix implemented (smallest correct change).**
- `service/impl/ThesisServiceImpl.downloadApplicationPdf`: deleted the inline `boolean allowed = … role == COMMITTEE …` block and replaced it with a single call to `thesisReadAccessPolicy.requireReadAccess(thesis, securityUtils.getCurrentUser())`.
- **Authorization now runs BEFORE the PDF-existence probe and any file I/O.** The guard was moved above the `applicationPdfPath == null` check, so an unauthorized caller gets 403 without the method revealing whether a PDF exists (no 404 information leak) and without constructing/reading any `UrlResource`. Authorized users hit the unchanged existence/file path exactly as before.
- No change to the endpoint URL, HTTP method, `Content-Type: application/pdf`, `Content-Disposition` filename, return type, or PDF bytes for authorized users. `GlobalExceptionHandler` still maps `UnauthorizedException → 403` and `ResourceNotFoundException → 404`. The controller's Swagger `@Operation`/`@ApiResponses` description was corrected to state the thesis-specific policy (was: "Accessible by … COMMITTEE").

**Exact authorization policy after the fix (identical to `ThesisReadAccessPolicy`).** A caller may download iff they are one of: (1) the thesis owner STUDENT; (2) the assigned MENTOR; (3) a `CommitteeMember` seated on THIS specific thesis (checked via `existsByThesisAndProfessor(thesis, user)` against the REQUESTED thesis); (4) STUDENT_SERVICE; (5) ARCHIVE. Everyone else → 403. Knowing the UUID never bypasses this.

**Role matrix:**
| Caller | Result |
|---|---|
| Thesis owner STUDENT | ✅ allowed, PDF returned |
| Assigned MENTOR | ✅ allowed, PDF returned |
| Committee member seated on THIS thesis | ✅ allowed, PDF returned |
| STUDENT_SERVICE | ✅ allowed, PDF returned |
| ARCHIVE | ✅ allowed, PDF returned |
| Unseated COMMITTEE user | ❌ 403 |
| COMMITTEE member seated on ANOTHER thesis | ❌ 403 (seat checked against the requested thesis) |
| Unrelated (unassigned, unseated) MENTOR | ❌ 403 |
| Unrelated (non-owner) STUDENT | ❌ 403 |

**Tests.** New `src/test/java/com/praksa/service/ApplicationPdfDownloadAuthorizationTest.java` — 11 Mockito tests exercising the **real** `ThesisReadAccessPolicy` (only its `CommitteeMemberRepository` is mocked), so the seat check runs genuinely against the requested thesis rather than being stubbed. Covers: all five allowed roles (each returns a real `Resource`; the privileged roles short-circuit WITHOUT querying the committee repo — asserted via `never()`); the four denied cases (unseated COMMITTEE, COMMITTEE seated on another thesis, unrelated MENTOR, unrelated STUDENT); an explicit "seat is verified against the REQUESTED thesis" assertion (`verify(existsByThesisAndProfessor(eq(thesis), eq(user)))`); an ordering test proving authorization precedes the existence probe (unauthorized caller on a thesis with NO generated PDF still gets 403, not 404 — so no file work runs); and an authorized-but-file-missing case (→ 404) proving authorized reads still reach the file layer.

**Verification actually executed (JDK 25 / JBR via `./mvnw`; project targets Java 21, so Mockito self-attaches with the usual warning).**
- `./mvnw -Dtest=ApplicationPdfDownloadAuthorizationTest test` → **Tests run: 11, Failures: 0, Errors: 0, BUILD SUCCESS**.
- Full `./mvnw test` → **181 tests run, 1 failure**. The single failure is the **known, pre-existing, unrelated** `AuthIntegrationTest.login_wrongPassword_returns403` (expects 4xx, gets 500 — a DB-backed auth-login test that does not touch the PDF path). All other 180 pass, including the 11 new tests and every existing authorization/service test. **The full suite is NOT clean** solely because of that pre-existing failure — not caused or affected by this change.

**Frontend impact — none needed, none made.** The only caller is `ThesisDetailPage.tsx`'s download button, shown behind `thesis.hasApplicationPdf` on a page already gated by the same `ThesisReadAccessPolicy` read guard (from the read-side IDOR fix, via `getThesisById`). Any user who can see the button is exactly a user now authorized to download, so no authorized user hits a spurious 403. The axios interceptor already splits 401 (logout) from 403 (toast, keep session), so an unlikely 403 degrades gracefully. `grep` confirms no other frontend caller.

**Limitations.** Full end-to-end HTTP verification (real Spring MVC 403/404 over the wire) was not run — the integration slice needs a live Postgres, unavailable here; authorization is proven at the service boundary (the authoritative layer) by the Mockito suite. Build ran on JDK 25 (JBR) rather than the project's Java 21 target.

---

**Item #15 — Production-safe SMTP / email configuration (BUG-6): DONE (2026-08-17).**

Makes email delivery configurable, provider-agnostic, and honest, and removes SMTP secrets from tracked source. Backend/config-only; no frontend, no workflow, no notification-type or recipient changes.

**Existing email architecture discovered (verified against source, not the prior handoff).**
- `NotificationServiceImpl.notify(recipient, thesis, type[, customMessage])` saves a `Notification` row (`isSent=false`) **synchronously in the caller's transaction**, then extracts primitives (id, recipient email, subject, body) and calls `EmailService.sendAsync(...)`. `notifyRole(role, …)` fans out via `findByRole`. Unchanged.
- `EmailService.sendAsync(UUID, String, String, String)` is `@Async("emailTaskExecutor")` + its own `@Transactional`. It built a `SimpleMailMessage`, called `JavaMailSender.send`, and on success marked the row `isSent=true`/`sentAt` (via `notificationRepository` directly, to avoid a NotificationService↔EmailService cycle); on `MailException` it logged and returned, leaving `isSent=false`. No retry job exists (by design — out of scope here).
- Mail dependency = `spring-boot-starter-mail` (pom). Executor = `emailTaskExecutor` in `AsyncConfig` (2/5/50). Config lived in `application.properties` with placeholder Gmail creds; `management.health.mail.enabled=false` was the workaround.

**Exact SMTP configuration mechanism implemented.**
- `application.properties` mail block rewritten to `${ENV:default}` placeholders — `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME` (default **empty**), `MAIL_PASSWORD` (default **empty**), `MAIL_SMTP_AUTH`, `MAIL_SMTP_STARTTLS`, plus `MAIL_FROM`. Provider is pure configuration (Gmail / Mailtrap / university relay / any SMTP) — nothing Gmail-specific in code.
- New master switch **`app.mail.enabled`** (`${MAIL_ENABLED:false}`). `EmailService` reads it via `@Value`. When **false** (default): `sendAsync` logs and returns **before** touching `JavaMailSender` — no connection attempt, and the row is **left `isSent=false`** (never falsely marked sent). When **true**: unchanged send path (success → `isSent=true`; `MailException` → logged, stays `isSent=false`).
- `fromAddress` now `@Value("${app.mail.from:${spring.mail.username:no-reply@diploma-system.local}}")` (was `${spring.mail.username}`).
- `management.health.mail.enabled` tied to `${MAIL_ENABLED:false}` so the mail health indicator activates only when mail is actually on.
- Local override without committing secrets: `spring.config.import=optional:file:./application-local.properties` loads a git-ignored file if present; env vars still take precedence. Template committed as `application-local.properties.example` (placeholders only).

**Configuration values now environment-backed.** `MAIL_ENABLED`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_SMTP_AUTH`, `MAIL_SMTP_STARTTLS`, `MAIL_FROM`; plus `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`, `JWT_SECRET`, `JWT_EXPIRATION_MS` (BUG-15).

**Secrets removed from tracked source.** SMTP username/password placeholders are **gone** (empty env defaults). DB password + JWT secret are now env-overridable but still ship **local-dev-only** `${ENV:default}` fallbacks to preserve one-command dev (see BUG-15 — remaining work documented, not silently dropped).

**`.gitignore` change.** Added `application-local.properties` and `.env` under a new "Local secret / config overrides" section. No broad patterns that could hide legitimate source.

**Notification failure/sent semantics preserved.** The row is created `isSent=false` in the caller tx; it becomes `isSent=true` **only** on a genuine successful `JavaMailSender.send`; a send failure OR the disabled path both leave it `isSent=false`. The app never pretends an email was sent. No retry system introduced. No change to notification types, recipients, bodies, or any workflow.

**Files changed.**
- `src/main/resources/application.properties` — env-backed datasource/JWT/mail; `app.mail.enabled`/`app.mail.from`; `spring.config.import`; mail health switch.
- `src/main/java/com/praksa/service/EmailService.java` — `mailEnabled` guard; `app.mail.from` sender.
- `.gitignore` — ignore `application-local.properties`, `.env`.
- `application-local.properties.example` — **new**, placeholder template.
- `src/test/java/com/praksa/service/EmailServiceTest.java` — **new**, 3 mocked-`JavaMailSender` tests.

**Tests executed (real results).** JDK/Maven were not on PATH; built with the bundled JetBrains Runtime (JDK 25) via `./mvnw`. `EmailServiceTest` → **3/3 pass** (success marks sent; `MailException` leaves unsent; disabled skips SMTP and leaves unsent). Re-ran the four existing notification unit tests alongside it → **13/13 pass, BUILD SUCCESS**. Full-suite `mvn test` NOT run: `PraksaApplicationTests`/`AuthIntegrationTest` need a live Postgres, and the runtime is JDK 25 vs. the project's Java 21 target (Mockito self-attaches with a warning under 25) — so only the DB-free Mockito unit tests were exercised.

**Limitations.** (1) DB password + JWT secret still have inline dev defaults (BUG-15 remainder). (2) No retry for `isSent=false` rows (P2.6 — intentionally not built). (3) Real end-to-end SMTP send not verified (no live mail server; would need `MAIL_ENABLED=true` + creds). (4) Full context-load/integration tests unrun (no Postgres; JDK-version mismatch).

---

## WORKFLOW AUDIT (2026-08-17) — verified against current source

**Verdict:** workflow logic is coherent end-to-end; blocked from being production-safe by one unauthenticated privilege-escalation (P0), a systemic read-side IDOR (P1), and two workflow gaps that can strand real users (P1). Email delivery is non-functional (BUG-6). Nothing below was fixed — this is a prioritized list only.

### CONFIRMED (still open in current code)

| Pri | Issue | Where (exact) | Why it matters | Evidence |
|---|---|---|---|---|
| ~~P0~~ **FIXED (2026-08-17)** | **BUG-2 / P0.2 — register accepts arbitrary role** | `service/impl/AuthServiceImpl.java` | Was: anyone could self-register as `STUDENT_SERVICE`/`ARCHIVE`/`COMMITTEE`/`MENTOR` and receive a privileged JWT. Now: `register()` hard-codes `role = Role.STUDENT` and ignores the requested role. | Enforced in `AuthServiceImpl.register`; covered by `AuthServiceRegistrationTest` + an integration coercion test |
| ~~P1~~ **FIXED (2026-08-17)** | **Read-side IDOR — thesis reads lack per-resource authorization** | `ThesisServiceImpl.getThesisById`, `getStatusHistory`; `CommitteeServiceImpl.getCommittee`; `DefenseServiceImpl.getActiveDefense`,`getAllDefenses`; `DefenseResultServiceImpl.getResult` | Was: any authenticated user could enumerate UUIDs and read any thesis's PII / history / committee / defense schedule / **grade**. Now: all six delegate to the shared `ThesisReadAccessPolicy.requireReadAccess(thesis, user)` (owner STUDENT / assigned MENTOR / seated committee member / STUDENT_SERVICE / ARCHIVE; everyone else 403). See the dated entry at the bottom. | Fixed by `security/ThesisReadAccessPolicy` + guards in all six methods; covered by `ThesisReadAccessPolicyTest` + `ThesisReadIdorGuardTest` |
| ~~P1~~ **FIXED (2026-08-17)** | **Grading not scoped to the thesis's committee (superset of BUG-13)** | `DefenseResultServiceImpl.recordResult` | Was: **any** `COMMITTEE`-role user *or* `STUDENT_SERVICE` could grade+archive **any** `DEFENSE_SCHEDULED` thesis without a committee-seat check. Now: `recordResult` calls `requireCommitteeSeat(thesis, recorder)` → `committeeRepository.existsByThesisAndProfessor(thesis, recorder)` before any mutation. Only a seated member of THIS committee may grade; STUDENT_SERVICE/ARCHIVE/STUDENT owner/unseated COMMITTEE → 403. Resolves BUG-13. | Fixed by `requireCommitteeSeat` guard; covered by `DefenseResultGradingAuthorizationTest` + updated recordResult tests |
| ~~P1~~ **FIXED (2026-08-17, Item #13)** | **Credits cannot be set from the UI (partial Item #5)** | Was: `UserController.updateCredits` + `api/userApi.ts updateCredits` existed but no page called it. Now: STUDENT_SERVICE-only `/students` page (`ManageCreditsPage` + `features/credits/EditCreditsModal`) lists students and calls the existing `PATCH /api/users/{id}/credits`. | Every newly-registered student had `credits = null` and no in-app way for Student Service to record them; now there is. Backend endpoint unchanged/authoritative; only a `UserSummaryResponse` DTO enrichment (index/credits) was added so the list can display them. | Fixed — see the Item #13 dated entry below |
| ~~P1~~ **FIXED (2026-08-17, Item #14)** | **`ELIGIBILITY_REJECTED` was a terminal dead-end** | `ThesisServiceImpl.createThesis` (one-active-thesis rule) + new `isActiveStatus` helper | Was: nothing transitions out of `ELIGIBILITY_REJECTED`, and `createThesis` blocked a student who had any non-`ARCHIVED` thesis — an eligibility-rejected student was permanently locked out. Now: the one-active-thesis check excludes BOTH `ARCHIVED` and `ELIGIBILITY_REJECTED`, so a rejected student may create a fresh thesis. The rejected row is preserved untouched (never deleted, archived, or re-statused) as historical/audit data. `decideEligibility` remains the only writer of `ELIGIBILITY_REJECTED`. | Fixed by `isActiveStatus` in `ThesisServiceImpl`; covered by `EligibilityRejectedNewThesisTest` |
| ~~P1~~ **FIXED (2026-08-17, Item #15)** | **BUG-6 — SMTP not configured** | `application.properties` mail block; `service/EmailService.java` | Was: placeholder Gmail creds → every send failed, rows stuck `isSent=false`. Now: env-backed provider-agnostic SMTP + `app.mail.enabled` gate; disabled-by-default in dev skips the send cleanly and honestly leaves `isSent=false`; enabling + real creds sends. Notification failure/sent semantics unchanged. | `EmailServiceTest` (3 tests) covers success/failure/disabled |
| ~~P2~~ **FIXED (2026-08-17)** | **Duplicate `DEFENSE_REMINDER` to mentor** | `ScheduledTasksService.sendDefenseReminders` | Was: notified student, then mentor explicitly, then looped committee members — the mentor holds a `MENTOR_MEMBER` seat, so was notified **twice**. Now: the explicit mentor notify is removed; the committee loop notifies the mentor exactly once, consistent with `scheduleDefense`/`cancelDefense`. Recipients otherwise unchanged (student + each committee member once); timing, window, and `reminderSentAt` semantics untouched. | Fixed — covered by `DefenseReminderNotificationTest` (4 tests); see the dated entry at the bottom |
| ~~P2~~ **FIXED (2026-08-17)** | **BUG-19 — `/api/notifications/unsent` had no role check** | `NotificationServiceImpl.getUnsentNotifications` | Was: any authenticated user could enumerate system-wide unsent notifications. Now: the method's FIRST statement is `requireRole(securityUtils.getCurrentUser(), Role.STUDENT_SERVICE)`, so STUDENT/MENTOR/COMMITTEE/ARCHIVE get 403 and the repository is never queried; only STUDENT_SERVICE reaches `findByIsSentFalse()`. Controller/SecurityConfig unchanged; 401 for unauthenticated still handled by Spring Security. | Fixed by `requireRole` guard in the service method; covered by `NotificationUnsentAuthorizationTest` (5 tests) |
| ~~P2~~ **FIXED (2026-08-17)** | **User enumeration / PII leak via `GET /api/users?role=`** | `UserServiceImpl.getUsersByRole` (auth now in the service, before any repo query); `UserSummaryResponse` is now purpose-specific | Was: any authenticated user could list all users of any role — including email and (since Item #13) index number + credits. Now: the endpoint is per-role authorized in the service layer BEFORE `findByRole` runs — `role=STUDENT` is STUDENT_SERVICE-only (full student fields); `role=MENTOR` is the mentor picker (STUDENT/MENTOR/STUDENT_SERVICE, identity only — no email/index/credits); any other requested role → 403 for everyone. Swapping `role=` cannot widen access or leak PII. See the dated entry below. | Fixed by per-role authorization in `getUsersByRole` + purpose-specific `UserSummaryResponse` factories; covered by `UserEnumerationAuthorizationTest` (13 tests) |
| ~~P2~~ **FIXED (2026-08-17)** | **Over-broad application-PDF access — `downloadApplicationPdf` allowed ANY COMMITTEE user** | `ThesisServiceImpl.downloadApplicationPdf` | Was: authorized by role only — the check `role == COMMITTEE` let any COMMITTEE user (and any STUDENT_SERVICE/ARCHIVE/owner/assigned-mentor) download any thesis's application PDF with just the UUID; an unseated COMMITTEE user or one seated on another thesis got the PDF. Now: the inline check is replaced by `thesisReadAccessPolicy.requireReadAccess(thesis, currentUser)`, run BEFORE the PDF-path/file work — scoped to THIS thesis (owner / assigned mentor / seated committee member of this thesis / STUDENT_SERVICE / ARCHIVE); bare COMMITTEE role no longer suffices. | Fixed by reusing `ThesisReadAccessPolicy`; covered by `ApplicationPdfDownloadAuthorizationTest` (11 tests) |
| ~~P2~~ **FIXED 2026-08-17** | **Stale/contradictory frontend text** | `features/defense/DefenseSection.tsx` (`handleCancel` toast) | Was: the cancel toast said "mentor can reschedule", contradicting the STUDENT_SERVICE-only reschedule rule (Item #6). Now: toast reads "Defense cancelled — Student Service can schedule a new one"; role gating was already correct (Schedule/reschedule is `isService`-only, mentors never see it). Frontend copy only. | Fixed — see the dated P2.7 entry above; `npx vite build` passes (1851 modules) |
| ~~P2~~ **FIXED 2026-08-17** | **BUG-15 — secrets committed** | `application.properties` datasource + jwt blocks; `JwtUtil` | FULLY FIXED: SMTP creds removed (Item #15); inline DB-pw + `jwt.secret` defaults removed (fail fast if unset); external mode gets its own `auth.external-jwt-secret` (fail fast if external enabled without it). `JwtUtil` splits local vs external key. Tests: `JwtUtilBug15Test` (6), `ProductionConfigSecretsTest` (3), all pass. | Env vars `SPRING_DATASOURCE_PASSWORD`, `JWT_SECRET`, `EXTERNAL_JWT_SECRET` (only if external enabled); `application-local.properties` (git-ignored) |

### ALREADY FIXED / false positives (documented as bugs but resolved in current source — do NOT re-fix)
- **BUG-1** broken sidebar routes — `/committee`,`/defenses`,`/archive` pages + routes exist.
- **BUG-3 / P1.1** COMMITTEE `getMyTheses` scope — now scoped (`ThesisServiceImpl.getMyTheses` + `getCommitteeTheses`/`getDefenseTheses`).
- **BUG-4 / P1.2** committee version visibility — `ThesisVersionServiceImpl.checkThesisReadAccess` + `canSeeVersion` (final-only for seated committee, non-members denied). Solid.
- **BUG-5 / P0.3** 7 dead notification types — all emitted (verified in Committee/Defense/DefenseResult/Version services).
- **BUG-7 / P1.4** explicit defense-eligibility step — `verifyDefenseEligibility` (Item #8).
- **BUG-8 / P1.5** student-initiated defense request — `DefenseServiceImpl.requestDefense` (Item #6).
- **BUG-9** defense record PDF — `DefenseRecordPdfService` (Item #11).
- **BUG-10** 200-credit gate — enforced in `createThesis` (but see the P1 "no UI to set credits" gap above).
- **BUG-11** `submissionDeadline` — now populated (`createThesis`) + enforced (`submitApplication`) + reported (`reportExpiredPendingApplications`).

### DESIGN LIMITATIONS (intentional — not bugs)
- `submitReviewNotes` checks seat membership, not `Role` (MENTOR-role users hold the seats).
- `archiveNotes` is preserve-only, no editor (future **P2.2**).
- ~~Application-PDF endpoint (`downloadApplicationPdf`) allows **any** COMMITTEE user — over-broad but tracked~~ — **FIXED 2026-08-17**: now delegates to `ThesisReadAccessPolicy.requireReadAccess`, scoped to THIS thesis (owner / assigned mentor / seated committee member / STUDENT_SERVICE / ARCHIVE); bare COMMITTEE role no longer suffices. Consistent with the read endpoints and the intentionally-strict record-PDF policy.
- `acceptCommitteeReview` fast-forwards `COMMITTEE_ACCEPTED` as a pass-through — intentional.
- Symmetric HMAC JWT, no refresh tokens, `EnableMethodSecurity` present but unused (all auth in-service) — future/prod items. (CORS config added 2026-08-18, P3.3.)

### OPTIONAL IMPROVEMENTS
- ~~Notification retry job for `findByIsSentFalse()` (**P2.6**)~~ — **DONE 2026-08-17** (see the dated P2.6 entry).
- ~~Integration tests for the workflow (**P2.4**)~~ — **DONE 2026-08-18** (see the dated P2.4 entry near the top).
- ~~Move secrets to env vars (**P2.5**)~~ — DONE (BUG-15).
- Read/unread flag on notifications (**P3.6**).

### SINGLE BEST NEXT TASK
> **UPDATE (2026-08-31, final):** this paragraph is historical. The authoritative, continuously-updated pointer is now the **CURRENT NEXT STEP** section near the top of this file. As of 2026-08-31: the project has been finally committed and pushed to GitHub — see the dated "Final Git Commit + Push" entry near the top. **There is no next task.** Development is STOPPED pending professor feedback. Do NOT start a new feature, refactor, or translation touch-up automatically — wait for explicit direction from the user, driven by whatever the professor says.

~~Fix BUG-2 / P0.2~~ — **DONE 2026-08-17**. ~~Read-side IDOR (P1)~~ — **DONE 2026-08-17**. ~~Write-side grading IDOR (P1, superset of BUG-13)~~ — **DONE 2026-08-17**. ~~credits-cannot-be-set-from-UI gap (P1)~~ — **DONE 2026-08-17 (Item #13)**. ~~`ELIGIBILITY_REJECTED` terminal dead-end (P1)~~ — **DONE 2026-08-17 (Item #14)**. ~~BUG-6 SMTP not configured (P1)~~ — **DONE 2026-08-17 (Item #15)** (production-safe env-backed SMTP + `app.mail.enabled` gate; secrets removed from tracked config). ~~BUG-19 `/api/notifications/unsent` no role check (P2)~~ — **DONE 2026-08-17** (STUDENT_SERVICE-only guard in `getUnsentNotifications`, before any repo query). ~~User enumeration + PII leak via `GET /api/users?role=` (P2)~~ — **DONE 2026-08-17** (per-role authorization in `UserServiceImpl.getUsersByRole` before any repo query; purpose-specific `UserSummaryResponse`; mentor picker no longer exposes email/index/credits; `UserEnumerationAuthorizationTest`, 13 tests). ~~Duplicate `DEFENSE_REMINDER` to the mentor (P2)~~ — **DONE 2026-08-17** (removed the explicit mentor notify in `ScheduledTasksService.sendDefenseReminders`; the `MENTOR_MEMBER` committee seat already notifies the mentor once, consistent with `scheduleDefense`/`cancelDefense`; `DefenseReminderNotificationTest`, 4 tests). ~~Over-broad `downloadApplicationPdf` (P2)~~ — **DONE 2026-08-17** (replaced the inline role check with `thesisReadAccessPolicy.requireReadAccess`, run before any file work; scoped to THIS thesis; bare COMMITTEE role no longer suffices; `ApplicationPdfDownloadAuthorizationTest`, 11 tests). ~~BUG-15 remainder — inline DB/JWT secret defaults + local/external JWT key split (P2)~~ — **DONE 2026-08-17** (removed inline `${ENV:default}` fallbacks for the DB password and `jwt.secret`; startup fails fast when unset; added a separate `auth.external-jwt-secret` for external mode with fail-fast when external auth is enabled without it; `JwtUtilBug15Test` 6 tests + `ProductionConfigSecretsTest` 3 tests, all pass; full suite 190 tests, only the pre-existing `login_wrongPassword_returns403` fails). ~~Stale "mentor can reschedule" defense copy (P2.7)~~ — **DONE 2026-08-17** (corrected the `DefenseSection.tsx` cancel toast to "Defense cancelled — Student Service can schedule a new one"; role gating was already correct — Schedule/reschedule is `isService`-only; `npx vite build` passes EXIT 0 / 1851 modules, changed file type-checks clean). ~~Notification retry job (P2.6)~~ — **DONE 2026-08-17** (added the `@Scheduled` `retryUnsentNotifications` job in `ScheduledTasksService` delegating to `NotificationServiceImpl.retryUnsentNotifications`, which walks a bounded, age-filtered, oldest-first page of `isSent=false` rows and re-dispatches via the existing `EmailService`; no new rows, retry never marks sent itself; `NotificationRetryServiceTest` 11 tests + `ScheduledTasksRetryTest` 3 tests, all pass; full suite 204 tests, only the pre-existing `login_wrongPassword_returns403` fails). No P1, no authorization P2, no config-secrets P2, no UI-accuracy items, and no notification-reliability items remain. ~~P2.4 — workflow integration tests~~ — **DONE 2026-08-18**. ~~Backend login 500→403~~ — **DONE 2026-08-18** (suite 220/220). ~~Frontend `FileText` TS6133~~ — **DONE 2026-08-18**. ~~Frontend `tsconfig.app.json` `baseUrl` TS5101 build blocker~~ — **DONE 2026-08-18** (removed `baseUrl`, kept a `paths`-only alias under `moduleResolution: "bundler"`; `npm run build` now EXIT 0, 1851 modules; no `ignoreDeprecations` needed). ~~P2.2 (ARCHIVE-role archive-notes editor)~~ — **DONE 2026-08-18**. ~~P3.3 (CORS config for split deployment)~~ — **DONE 2026-08-18**. ~~P3.4 (frontend/backend consistency audit)~~ — **DONE 2026-08-18** (fixed the notification `isSent`→`sent` wire-key mismatch; rest of the contract confirmed correct). **No build blockers and no known test failures remain (full suite 254/254).** The next best task is now one of the remaining larger optional roadmap items: **P3.1** (RS256/JWKS external auth), **P3.5** (refresh tokens), or the roadmap **P3.4** (mobile-responsive layout); small alternative **P2.8** (stale `checkReadAccess` doc comment). Do NOT start automatically.

---

**BUG-19 — `GET /api/notifications/unsent` restricted to STUDENT_SERVICE: FIXED (2026-08-17).**

Closes the P2 authorization gap where any authenticated user could enumerate the system-wide list of unsent notifications. Backend service-layer change (plus tests); no controller, SecurityConfig, or frontend change.

**Existing behavior discovered (verified against source, not the prior handoff).**
- `NotificationController.getUnsent` (`GET /api/notifications/unsent`) delegated straight to `NotificationService.getUnsentNotifications()` with no guard. The controller only requires authentication (via `SecurityConfig`, which requires auth for everything outside the `/api/auth/**` + swagger + health permit list).
- `NotificationServiceImpl.getUnsentNotifications()` called `notificationRepository.findByIsSentFalse()` and mapped every row (across ALL users) to `NotificationResponse` — no role check anywhere on the path.
- The project's established authorization idiom is a service-layer `requireRole(user, Role)` helper that throws `UnauthorizedException`, which `GlobalExceptionHandler` maps to **HTTP 403**. `SecurityUtils.getCurrentUser()` resolves the authenticated principal to a `User` entity. `Role` has exactly `{STUDENT, MENTOR, STUDENT_SERVICE, COMMITTEE, ARCHIVE}` — **there is no ADMIN role** in this project (it was already standardized away), so there is no ADMIN case to handle.

**Exact fix implemented (smallest correct change).**
- `service/impl/NotificationServiceImpl.java`:
  - `getUnsentNotifications()` now runs `requireRole(securityUtils.getCurrentUser(), Role.STUDENT_SERVICE)` as its **first statement**, before `notificationRepository.findByIsSentFalse()`. An unauthorized caller therefore never reaches the repository.
  - Added a private `requireRole(User, Role)` helper identical in behavior to the one in `ThesisServiceImpl` (throws `UnauthorizedException` → 403 when the role does not match). Added the `UnauthorizedException` import.
- No change to the controller, `SecurityConfig`, notification types, recipients, sending, `isSent` semantics, email behavior, scheduler, or any DTO.

**Exact authorization behavior after the fix.**
- Caller role = `STUDENT_SERVICE` → allowed; `findByIsSentFalse()` is queried and the mapped list is returned unchanged.
- Caller role = `STUDENT` / `MENTOR` / `COMMITTEE` / `ARCHIVE` → `UnauthorizedException` → **HTTP 403**; the repository is **never** queried.
- Unauthenticated caller → still rejected upstream by Spring Security (**401**) before the service runs. (There is no ADMIN role to consider.)
- Validation order: authenticate (Spring Security, 401 if absent) → resolve current user → `requireRole` STUDENT_SERVICE (403 if wrong) → THEN query `findByIsSentFalse()`.

**Tests.** New `src/test/java/com/praksa/service/NotificationUnsentAuthorizationTest.java` (Mockito, mirrors `DefenseResultGradingAuthorizationTest` style), 5 tests:
- STUDENT_SERVICE → allowed, `findByIsSentFalse()` queried exactly once, returned `NotificationResponse` list is a faithful/unchanged mapping (id, thesisId/title incl. the null-thesis case, type, isSent).
- STUDENT → 403 (`UnauthorizedException`), `findByIsSentFalse()` never called.
- MENTOR → 403, never called.
- COMMITTEE → 403, never called.
- ARCHIVE → 403, never called.

**Verification actually executed (JDK 25 / JBR via `./mvnw`).**
- `./mvnw -Dtest=NotificationUnsentAuthorizationTest test` → **Tests run: 5, Failures: 0, Errors: 0, BUILD SUCCESS**.
- Full `./mvnw test` → **153 tests run, 1 failure**. The single failure is `AuthIntegrationTest.login_wrongPassword_returns403` (expects 4xx, gets 500) — a **pre-existing, unrelated** DB-backed auth-login test that does not touch the notification path; the other 152 (including all notification/service tests) pass. Not caused by this change.

**Frontend impact.** None. `grep` for `unsent`/`getUnsent` across `praksa-frontend` returns zero matches — no page or API module calls this endpoint, so no frontend change was needed or made.

---

**Item #14 — `ELIGIBILITY_REJECTED` terminal dead-end: RESOLVED (2026-08-17).**

Fixes the P1 workflow trap where an eligibility-rejected student was permanently locked out. Backend-only change (plus tests); no frontend change was needed.

**Existing behavior discovered (verified against source, not the prior handoff).**
- `ThesisStatus` (`model/enums/ThesisStatus.java`) is unchanged at 20 values; `ELIGIBILITY_REJECTED` already exists. **No new status was created** — inspection confirmed the existing model did not require one.
- `ThesisServiceImpl.decideEligibility` is the ONLY writer of `ELIGIBILITY_REJECTED` (`PENDING_ELIGIBILITY_CHECK → ELIGIBILITY_REJECTED` when Student Service rejects eligibility). Nothing else reads from or transitions out of that status — it genuinely had no outgoing transition.
- `ThesisServiceImpl.createThesis` enforced the one-active-thesis rule as: `findByStudent(student).stream().anyMatch(t -> t.getStatus() != ARCHIVED)`. So "active" meant *any status except `ARCHIVED`* — which included `ELIGIBILITY_REJECTED`. Combined with the dead-end, a rejected student could never create another thesis.

**Exact fix implemented (smallest correct change).**
- `service/impl/ThesisServiceImpl.java`:
  - The one-active-thesis predicate now delegates to a new private helper `isActiveStatus(ThesisStatus)` and the check is `anyMatch(t -> isActiveStatus(t.getStatus()))`.
  - `isActiveStatus` returns `false` for `ARCHIVED` **and** `ELIGIBILITY_REJECTED`, `true` for every other status. Both are non-active terminal states that no longer block a new thesis; every other status (`PENDING_ELIGIBILITY_CHECK`, `TOPIC_SELECTION`, `IN_PROGRESS`, … `DEFENSE_SCHEDULED`, etc.) still counts as active and still triggers the existing `"You already have an active thesis"` rejection.
- No new status, no new endpoint, no new repository method, no change to `decideEligibility`, `ThesisStatus`, or any transition.

**Why the old rejected thesis stays intact.** `createThesis` only builds and saves the *new* thesis and writes a single `null → PENDING_ELIGIBILITY_CHECK` history row for it. It never loads the old rejected row for mutation, never calls `delete`/`deleteById`, and never transitions it. The rejected thesis therefore remains in `ELIGIBILITY_REJECTED` verbatim as historical/audit data — not deleted, not archived, not silently re-statused. `decideEligibility` is still its only writer.

**Exact one-active-thesis behavior after the fix.**
- Existing thesis = `ELIGIBILITY_REJECTED` (and no other active thesis) → student **MAY** create a new thesis.
- Existing thesis = any normal active/in-progress status (`PENDING_ELIGIBILITY_CHECK` … `DEFENSE_SCHEDULED`) → student still gets the `"You already have an active thesis"` rejection.
- Existing thesis = `ARCHIVED` → unchanged pre-existing behavior (student may create — `ARCHIVED` never blocked).
- A rejected row alongside a still-active thesis → still blocked (the active one counts).

**Authorization / credit / deadline behavior preserved.**
- `requireRole(student, Role.STUDENT)` runs first and is unchanged — only STUDENT can create; a non-STUDENT caller still gets `UnauthorizedException` (403). No authorization was weakened.
- The 200-credit gate (`REQUIRED_CREDITS_FOR_THESIS = 200`, null credits = ineligible) runs after the active-thesis check and is byte-for-byte unchanged — a rejected student with < 200 (or null) credits is still refused.
- `createdAt = now` and `submissionDeadline = now.plusMonths(1)` (real calendar-month arithmetic) are unchanged for the new thesis. Submission-deadline enforcement in `submitApplication` is untouched.

**Frontend.** No change needed and none made. `CreateThesisPage.tsx` calls `thesisApi.create` and lets the axios response interceptor toast the backend's 400 message; its only pre-emptive block is the client-side 200-credit hint (button disabled when `credits < 200`). It never inspects existing-thesis status, so once the backend permits creation for a rejected student, the flow simply succeeds. `StatusBadge`/`NotificationsPage`/`types/api.ts` already knew `ELIGIBILITY_REJECTED`.

**Tests written (backend).** `src/test/java/com/praksa/service/EligibilityRejectedNewThesisTest.java` — pure Mockito (`MockitoExtension`, `@InjectMocks ThesisServiceImpl`), mirroring `ThesisCreditsDeadlineTest`'s style and the real method signatures (`findByStudent`, `save`, `getCurrentUser`):
  1. `create_withRejectedThesis_allowed` — only thesis is `ELIGIBILITY_REJECTED` → creation succeeds, new status `PENDING_ELIGIBILITY_CHECK`.
  2. `create_withRejectedThesis_oldRowUnchanged` — old rejected row's status/id unchanged; `save` called exactly once with the NEW thesis (different id, `PENDING_ELIGIBILITY_CHECK`); `delete`/`deleteById` never called; exactly one history row written (no accidental transition of the old row).
  3. `create_withRejectedThesis_timestampsAndDeadline` — new thesis gets `createdAt` + `submissionDeadline = createdAt + 1 month`.
  4. `create_withActiveThesis_rejected` — `IN_PROGRESS` existing thesis still blocks (no save).
  5. `create_withActiveAndRejected_rejected` — active + rejected together still blocks.
  6. `create_withArchivedThesis_allowed` — pre-existing `ARCHIVED` behavior preserved.
  7. `create_noThesis_allowed` — no thesis → normal creation.
  8. `create_withRejectedThesis_stillNeedsCredits` — 199 credits + rejected row → still refused (credit gate intact).
  9. `create_nonStudent_rejected` — MENTOR caller → `UnauthorizedException` (authorization unchanged).

**Build/test honesty.** ⚠️ **No JDK is installed on this machine** (`java`/`javac` absent from PATH, `JAVA_HOME` unset, no JDK found under `C:\`), and the Maven wrapper needs a JDK to run. The backend tests were therefore **written but NOT compiled or executed** — no test results are claimed. They target the real, unchanged method signatures and follow the existing Mockito pattern, but must be run with `./mvnw -Dtest=EligibilityRejectedNewThesisTest test` (or the full `./mvnw test`) once a JDK 21 is available. The frontend was not rebuilt because no frontend file changed.

---

**Item #13 — STUDENT_SERVICE credit-management UI: DONE (2026-08-17).**

Closes the credits-cannot-be-set-from-UI gap (P1). Student Service can now find any student and set their credit balance from inside the app, wired to the pre-existing, server-side-restricted `PATCH /api/users/{id}/credits`. No new backend endpoint, no change to the 200-credit thesis gate, no change to registration, no weakening of the backend authorization.

**What was added / changed.**
- *Frontend (new):*
  - `src/pages/ManageCreditsPage.tsx` — STUDENT_SERVICE page. Lists all students via the existing `GET /api/users?role=STUDENT`; client-side search by name / email / index number (no UUID needed); each row shows full name, email, index number, and a credit badge (grey = not recorded, amber = below 200, emerald = ≥ 200) with a "below 200-credit thesis requirement" hint. Has a defensive in-page role check (renders an "only Student Service" notice for a non-STUDENT_SERVICE user who somehow reaches the route — this is UX, not the security boundary).
  - `src/features/credits/EditCreditsModal.tsx` — dialog reusing the shared `Modal`. Prefills the current balance, `type="number" min=0 step=1`, validates integer ≥ 0 in the UI (blocks empty / decimal / negative), and calls `userApi.updateCredits`. Copy makes explicit that it **SETS** the total (does not add). On success: `toast.success`, list updated in place (no refetch); on error: relies on the axios interceptor toast.
- *Frontend (wired):* `routes/AppRouter.tsx` (new `/students` route guarded `allowedRoles={['STUDENT_SERVICE']}`), `components/layout/Sidebar.tsx` (new "Student Credits" nav item, STUDENT_SERVICE only, `Coins` icon), `pages/DashboardPage.tsx` (new quick-action card, STUDENT_SERVICE only), `types/api.ts` (`UserSummary` gains `indexNumber: string | null` + `credits: number | null`).
- *Backend (one minimal, additive DTO change — NOT a new endpoint, NOT an auth change):* `dto/user/UserSummaryResponse.java` gains `indexNumber` + `credits`, populated from the `User` entity in the existing `from()` factory. This was necessary because the list endpoint's response is the only read path for those two fields and the task requires displaying them; enriching the existing summary avoided inventing a `GET /api/users/{id}` detail endpoint. Both fields are null for non-students. ⚠️ Side effect: `GET /api/users?role=` now also exposes `indexNumber`+`credits` to any authenticated caller — this slightly widens the already-tracked P2 user-enumeration leak (unchanged root cause: the endpoint has no role restriction; out of scope here, noted in the audit table).

**Existing API contract used (unchanged).** `PATCH /api/users/{id}/credits`, body `UpdateCreditsRequest { @NotNull @Min(0) Integer credits }`, returns `ApiResponse<UserDetailResponse>` (`id, email, fullName, role, indexNumber, credits`). Semantics: **sets** the absolute balance. `userApi.updateCredits(id, credits)` and `userApi.getByRole('STUDENT')` already existed.

**Authorization (authoritative, backend — unchanged by this task).** `UserServiceImpl.updateCredits` throws `UnauthorizedException` (403) unless the caller's role is `STUDENT_SERVICE`, throws `BadRequestException` (400) if the target is not a `STUDENT`, and there is no self-service path. The frontend route/sidebar/dashboard gating and the in-page check are usability only; a non-STUDENT_SERVICE user calling the API directly is still rejected server-side. There is exactly one frontend caller of `updateCredits` (the modal, reached only from the guarded page) — no student-facing credit editor exists (the student-facing `CreateThesisPage` only *reads* via `getMe()`).

**UI workflow.** STUDENT_SERVICE → sidebar "Student Credits" (or dashboard card) → `/students` → search/find student → "Set Credits" → modal prefilled with current balance → enter whole number ≥ 200 → "Save Credits" → success toast + row's credit badge updates immediately.

**Reused components/patterns.** `Modal`, `PageHeader`, `EmptyState`, `Skeleton`, `card`/`btn-primary`/`btn-secondary`/`input-field` classes, `sonner` toasts, the axios interceptor error handling, the `userApi` module, and the search-box pattern from `ThesesListPage`. No new UI framework or dependency.

**Build / test status (2026-08-17) — honest.**
- Frontend: `npx vite build` **passes** (EXIT 0, 1851 modules — +2 for the new page/modal). `npx tsc -p tsconfig.app.json --noEmit` (with `--ignoreDeprecations 6.0`) reports **zero errors in the changed/new files**; the only findings are pre-existing and unrelated — the documented `tsconfig.app.json` TS5101 `baseUrl` deprecation, and a TS6133 unused-import in `features/versions/VersionUploader.tsx` (a file not touched here). No frontend test runner is configured in this project (consistent with Items #1–#12); none was added.
- Backend: **NOT compiled or run** — this machine has no JDK/Maven (`java`/`mvn` not on PATH, `JAVA_HOME` empty). The single DTO change was written by inspection against the `User` entity getters (`getIndexNumber()`, `getCredits()`) already used by `UserDetailResponse`. **No claim is made that the backend compiles or that tests pass** — run `mvnw.cmd test` on a machine with a JDK. Existing `UserCreditsTest` is unaffected (the endpoint/service were not changed).
- **No live end-to-end browser test** was possible because the backend cannot run in this environment (the Vite dev server alone has no API to talk to).

**Limitations.** No live end-to-end verification (backend can't run here). The DTO enrichment widens the P2 user-enumeration leak surface as noted (root-cause fix is out of scope). Credits set to null cannot be re-nulled via this UI (the input requires ≥ 0) — intentional; there's no "clear credits" use case.

---

**Write-side grading IDOR (P1, superset of BUG-13) — `recordResult` not scoped to the thesis's committee: FIXED (2026-08-17).**

Recording a defense grade also archives the thesis (assigns the `DT-YYYY-NNNN` registration number, sets `archiveDate`/`archivedBy`, transitions to `ARCHIVED`, and sends `THESIS_GRADED` + `THESIS_ARCHIVED`). Previously `DefenseResultServiceImpl.recordResult` authorized only by **role** — it accepted any `COMMITTEE`-role user *or* any `STUDENT_SERVICE` and never checked the caller actually sat on THIS thesis's committee. Combined with `SecurityConfig.anyRequest().authenticated()`, any such user who knew (or guessed) a `DEFENSE_SCHEDULED` thesis+defense UUID could grade and archive another student's thesis. This is now scoped at the service layer (the security boundary), reusing the exact mechanism the read-access policy already uses.

**The exact grading authorization policy.** A caller may record a grade **iff they hold a `CommitteeMember` seat on the specific thesis being graded**, checked via `committeeRepository.existsByThesisAndProfessor(thesis, recorder)` — the authenticated professor against the **requested** thesis. There is no global-role shortcut. Because committee seats are only ever held by professors (`CommitteeServiceImpl.proposeCommittee` seats the mentor as `MENTOR_MEMBER` and requires each of the two `FORMAL_MEMBER`s to be `Role.MENTOR`; the dedicated `COMMITTEE`-role user is never auto-seated), this rule inherently:
- **allows** the mentor and the two formal members (the actual defense committee) to grade;
- **denies** a `COMMITTEE`-role user who is not seated, a professor seated only on some *other* thesis, `STUDENT_SERVICE`, `ARCHIVE`, and the `STUDENT` owner (ownership never confers grading).

Enforced by a new private helper `requireCommitteeSeat(Thesis, User)` → throws `UnauthorizedException` (→ 403). It is not a new permission system — it is the same `existsByThesisAndProfessor` seat check used by `ThesisReadAccessPolicy` and `requireRecordAccess`, applied with a **stricter** rule (seat-only; the read/record policies additionally allow the admin roles, which is correct for *reading* but not for *grading*).

**Role matrix (grade 5–10, i.e. the grade is never the reason for rejection):**
| Caller | Result |
|---|---|
| COMMITTEE-role, **seated** on this thesis | ✅ ALLOWED — grade saved, thesis archived, notifications sent |
| COMMITTEE-role, **not seated** on this thesis | ❌ 403, zero mutations |
| Professor seated only on **another** thesis | ❌ 403, zero mutations |
| Assigned MENTOR (holds a `MENTOR_MEMBER` seat) | ✅ ALLOWED (part of the committee) |
| MENTOR not seated on this thesis | ❌ 403, zero mutations |
| STUDENT (incl. the thesis **owner**) | ❌ 403, zero mutations |
| STUDENT_SERVICE | ❌ 403, zero mutations |
| ARCHIVE | ❌ 403, zero mutations |

**The STUDENT_SERVICE decision, and why.** STUDENT_SERVICE is now **restricted** (can no longer record grades). This was decided by verifying the actual spec/workflow, **not** by the old BUG-13 label alone: (1) every mention of STUDENT_SERVICE grading in this document flags it as an inconsistency — §8 BUG-13 "spec says only committee grades", §4 "recordResult allows STUDENT_SERVICE ... inconsistent with the spec", and the Item #10 note "orthogonal role-scope question ... BUG-13 note still stands"; (2) the coded workflow (§3/§4) has STUDENT_SERVICE performing scheduling, eligibility verification, and application/committee validation — never grading; (3) no requirement anywhere states STUDENT_SERVICE *must* record a result. The academic model is that the defense committee assigns the grade. STUDENT_SERVICE retains all of its legitimate read/oversight access (it is still allowed by `ThesisReadAccessPolicy` and can download the record PDF) — only the **write** action of grading is removed. ARCHIVE is likewise not a grader.

**Validation order / mutation safety.** `recordResult` now runs: (1) `validateGrade` (Item #7 defense-in-depth, 5–10 inclusive — kept FIRST so a bad grade is rejected regardless of caller, preserving the existing grade tests); (2) load current user; (3) `findThesis` (404 if missing); (4) **`requireCommitteeSeat` — authorization, 403 if not seated**; (5) `requireStatus(DEFENSE_SCHEDULED)`; (6) resolve defense + belongs-to-thesis (400) + not-cancelled (400) + no-existing-result (400); (7) save `DefenseResult`; (8) archive metadata; (9) `transitionStatus → ARCHIVED`; (10) notifications. Authorization happens **before** the status check and before **any** mutation, so an unauthorized request creates no `DefenseResult`, changes no status, writes no history row, generates no archive metadata, and sends neither `THESIS_GRADED` nor `THESIS_ARCHIVED`. The valid 5–10 workflow is unchanged (result saved, `ARCHIVED`, reg-number, both notifications, same transaction).

**No alternate bypass path.** Grep of `recordResult` / `DefenseResult` save / `ThesisStatus.ARCHIVED` / `setArchiveRegistrationNumber` across `src/main/java` confirms `DefenseResultServiceImpl.recordResult` (reached only via `POST /api/theses/{thesisId}/defenses/{defenseId}/result`) is the ONLY code path that saves a result or archives a thesis; every other `ARCHIVED` reference is a read-only list/limit filter in `ThesisServiceImpl`. No second grading endpoint exists.

**Files changed.**
- *Backend (production):* `service/impl/DefenseResultServiceImpl.java` — removed the role-only check; added `requireCommitteeSeat(Thesis, User)` (seat check via the already-injected `committeeRepository`) and call it before the status check. No new dependency (the `CommitteeMemberRepository` was already a field). Grade validation, archive metadata, transitions, and notifications untouched.
- *Backend (tests):* `src/test/java/com/praksa/service/DefenseResultGradingAuthorizationTest.java` (**new** — the full role matrix + mutation-safety); `DefenseResultServiceNotificationTest.java`, `DefenseResultGradeValidationTest.java`, `ArchiveMetadataTest.java` (**updated** — add the `CommitteeMemberRepository` mock and stub the seat for their seated-committee recorder; the STUDENT_SERVICE archive-metadata test was retargeted to a seated committee member since STUDENT_SERVICE can no longer grade; invalid-grade tests unchanged as they still reject at `validateGrade`).
- *Frontend:* `src/features/defense/DefenseSection.tsx` — `canGrade` no longer includes `STUDENT_SERVICE` (a role that can no longer grade) and now includes the assigned mentor (a seated committee member who can). COMMITTEE-role users are still offered the action but the backend rejects them with a clean 403 if not seated (post read-side-IDOR fix, a 403 no longer logs the user out). No backend authorization was weakened for the UI.

**Tests.**
- **New `DefenseResultGradingAuthorizationTest`** (pure Mockito): seated COMMITTEE → full workflow runs; seated MENTOR → allowed; unseated COMMITTEE → 403; professor seated only on another thesis → 403; STUDENT owner → 403; unseated MENTOR → 403; STUDENT_SERVICE → 403; ARCHIVE → 403. Every 403 asserts ZERO mutations (no `resultRepository.save`, no `statusHistoryRepository.save`, no `notificationService.notify` either overload, status still `DEFENSE_SCHEDULED`, all archive-metadata fields null) and that the defense is never even looked up (authorization precedes defense resolution).
- **Updated recordResult tests** keep asserting the valid workflow (result saved, `ARCHIVED`, reg-number, both notifications) but now for a *seated* committee grader.

**Build / test status (2026-08-17) — honest.**
- Backend: **NOT compiled or run** — this machine has no JDK/Maven (`java`/`mvn` not on PATH, `JAVA_HOME` empty; only `mvnw.cmd`, which itself needs a JDK). The production + test code was written and verified by inspection against the actual entity/repository/`SecurityUtils`/DTO signatures and the existing Mockito conventions (the new test mirrors `DefenseRecordPdfAccessTest`). **No claim is made that the backend compiles or that the tests pass** — run `mvnw.cmd test` on a machine with a JDK.
- Frontend: `npx vite build` **passes** (EXIT 0, 1849 modules). `npx tsc -p tsconfig.app.json --noEmit` reports only the **pre-existing** `tsconfig.app.json` TS5101 `baseUrl` deprecation (documented under prior items, unrelated) — zero errors in the changed file. Not exercised against a live backend (backend cannot run here).

**Explicitly left OPEN (out of scope here):** credits-cannot-be-set-from-UI (now the CURRENT NEXT STEP), `ELIGIBILITY_REJECTED` dead-end, BUG-6 (SMTP), BUG-19 (`/notifications/unsent` no role check), BUG-15 (secrets in source), duplicate defense reminder, user-enumeration via `GET /api/users?role=`, and the intentionally over-broad `downloadApplicationPdf` (any COMMITTEE user). None were changed.

---

**BUG-2 / P0.2 — Public registration privilege escalation: FIXED (2026-08-17).**

The public `POST /api/auth/register` endpoint no longer trusts the role supplied by the caller. A completely unauthenticated visitor can now only ever create a `STUDENT`.

**Enforcement location (the security boundary).** `com.praksa.service.impl.AuthServiceImpl.register`. The method now hard-codes `final Role role = Role.STUDENT;` and builds the `User` with that constant. `request.getRole()` is **read from the DTO but never used** to set the persisted role. Because every public registration is now a STUDENT, the index-number rules (non-blank + unique) always apply — previously they were gated behind `role == STUDENT`. No controller/security change was needed: `AuthController.register` is unchanged and `SecurityConfig` still (correctly) leaves `/api/auth/**` public so students can self-register; the coercion happens in the service, so it cannot be bypassed by calling the API directly.

**Security behavior — before vs after.**
- **Before:** `register()` built `User.role(request.getRole())`. An unauthenticated `POST /api/auth/register` with `role: "STUDENT_SERVICE"` (or `ARCHIVE`/`COMMITTEE`/`MENTOR`) created a privileged account and returned a valid JWT for that role → full privilege escalation.
- **After:** the requested role is ignored; the saved account is always `STUDENT` and the JWT is minted for `STUDENT`. Privileged roles (`MENTOR`, `STUDENT_SERVICE`, `COMMITTEE`, `ARCHIVE`) exist only via `DataInitializer` seeding / administrative provisioning.

**How privileged registration is prevented.** There is exactly one public code path that creates users — `AuthServiceImpl.register` — and it now pins the role to STUDENT. Grep of every `User.builder()` call site confirms the only other role-setting paths are: `DataInitializer` (trusted startup seed, not web-reachable) and `JwtAuthFilter.autoProvision` (external-auth scaffold, gated behind `auth.external-enabled=false` **and** requiring a token validly signed with the shared secret — not a public/unauthenticated path, and tied to the separate external-auth/BUG-15 work). No alternate public endpoint can set an arbitrary role.

**DTO decision.** `RegisterRequest.role` was intentionally **kept** (with its `@NotNull`) to avoid breaking existing clients that still send it (the frontend posts `role: "STUDENT"`). A comment documents that the field is now ignored server-side. This is compatible and avoids an unnecessary DTO redesign.

**Frontend.** `RegisterPage.tsx` already sent `role: "STUDENT"` with no role selector (Item #12) — unchanged behaviorally. Only its header doc-comment was updated to state the backend now enforces STUDENT-only (the old comment said the backend still accepted arbitrary roles, which is no longer true).

**Files changed.**
- *Backend (production):* `service/impl/AuthServiceImpl.java` (force `Role.STUDENT`, ignore requested role, always-apply index checks); `dto/auth/RegisterRequest.java` (comment only — documents the ignored field).
- *Backend (tests):* `src/test/java/com/praksa/service/AuthServiceRegistrationTest.java` (**new**, Mockito); `src/test/java/com/praksa/integration/AuthIntegrationTest.java` (updated — see below).
- *Frontend:* `src/pages/auth/RegisterPage.tsx` (header comment only — no behavior change).

**Tests.**
- **New `AuthServiceRegistrationTest`** (pure Mockito, matches the project's `service/*Test` style): role=STUDENT creates a STUDENT and mints a STUDENT token; a parameterized test over `{STUDENT_SERVICE, ARCHIVE, COMMITTEE, MENTOR}` asserts the persisted `User.role` is STUDENT (via `ArgumentCaptor`) and the token is issued for STUDENT, never the requested role; duplicate email, missing index (incl. a privileged request with a blank index), and duplicate index are all rejected with **no** `save`.
- **Updated `AuthIntegrationTest`**: three pre-existing tests registered a `MENTOR` **without** an index and expected success — invalid now that every registration is a STUDENT (index required), so they were given `Role.STUDENT` + an index number (`duplicate-email`, `login_success`, `login_wrongPassword`). Added `registerPrivilegedRole_isCoercedToStudent`: posts `role: STUDENT_SERVICE` to the real endpoint and asserts the response role is `STUDENT` **and** the DB row's role is `STUDENT`.

**Build / test status (2026-08-17) — honest.**
- Backend: **NOT compiled or run** — no JDK/Maven on this machine (`java`/`mvn` not on PATH, `JAVA_HOME` empty; only `mvnw.cmd`, which itself needs a JDK). The production change and both test files were written and verified by inspection against the actual DTO/entity/repository/`JwtUtil` signatures and the existing Mockito test conventions. **No claim is made that the backend compiles or that the tests pass** — they must be run with `mvnw.cmd test` on a machine with a JDK.
- Frontend: `npx vite build` **passes** (EXIT 0, 1849 modules). The full `npm run build` still trips only the **pre-existing** `tsconfig.app.json` TS5101 `baseUrl` deprecation (documented under Items #2/#3/#11/#12), unrelated to this change; the change itself is a comment.

**Remaining registration-related notes (OPEN, out of scope here).**
- `JwtAuthFilter.autoProvision` trusts the JWT `role` claim, but only when `auth.external-enabled=true` (off) and only for a validly-signed token — revisit alongside external-auth / **BUG-15** (secret in source), not as public-registration work.
- `RegisterRequest.role` is now dead input; a future cleanup could drop it from the DTO/types once no client sends it.

---

**Roadmap Item #12 — User registration page: COMPLETE (2026-08-17).**

A public frontend self-registration page was added and wired to the **existing** `POST /api/auth/register` endpoint. No new backend endpoint was created; **no backend production code was modified** (per the explicit scope decision for this task — see the security note below).

**Files created/modified (frontend only — `praksa-frontend/`):**
- **NEW** `src/pages/auth/RegisterPage.tsx` — the registration form (mirrors `LoginPage` conventions: `input-field` / `btn-primary` classes, `lucide-react` icons, `sonner` toasts, English UI, axios-interceptor error handling).
- `src/routes/AppRouter.tsx` — added the **public** route `/register` under `AuthLayout` (alongside `/login`), plus an import of `RegisterPage` and a doc-comment line. It is outside `ProtectedRoute`, so it needs no authentication.
- `src/pages/auth/LoginPage.tsx` — added a "Don't have an account? **Register**" `<Link to="/register">` (and imported `Link`). No other login behavior changed.
- **NO change** to `src/api/authApi.ts` (its `register()` already POSTs to `/auth/register`), `src/types/api.ts` (`RegisterRequest` already had `fullName`, `email`, `password`, `role`, `indexNumber`), `src/store/authStore.ts`, `src/api/client.ts`, or `src/layouts/AuthLayout.tsx` — all reused as-is.
- Environment file `C:\Users\bosko\.claude\launch.json` was created so the dev server could be launched for browser verification — not part of either repo.

**Actual request/response flow.** `authApi.register(data)` → `POST /api/auth/register` with JSON body `{ fullName, email, password, role: "STUDENT", indexNumber }` (exact shape of the backend `RegisterRequest` DTO; verified against `com.praksa.dto.auth.RegisterRequest`). Backend `AuthServiceImpl.register` checks duplicate email, requires a unique `indexNumber` for `STUDENT`, BCrypt-hashes the password, saves the `User`, and returns `ApiResponse<AuthResponse>` containing a JWT. **The frontend ignores the returned token** — it does not auto-authenticate.

**Roles that can publicly self-register, and why.** The registration UI **only** offers `STUDENT`: there is no role selector, and the request always sends `role: "STUDENT"`. Privileged accounts (`MENTOR`, `STUDENT_SERVICE`, `COMMITTEE`, `ARCHIVE`) are provisioned only through `DataInitializer` seeding / administrative mechanisms, so restricting the public form to `STUDENT` removes no legitimate workflow. A short in-page note states this ("Public registration is for students only. Mentor, student-service, committee, and archive accounts are created by the faculty administration.").

**⚠️ SECURITY NOTE (historical — superseded 2026-08-17).** At the time Item #12 was written the registration page was a **UX guard only** and the backend still accepted an arbitrary `role` (the pre-existing **BUG-2 / P0.2**). **That backend hole has since been FIXED** (2026-08-17): `AuthServiceImpl.register` now forces `role = STUDENT` and ignores the requested role, so a direct API call can no longer create a privileged account. See the dated **BUG-2 / P0.2 fix** entry below. The frontend was already STUDENT-only; the backend is now the enforcing boundary.

**Form fields + validation.** Full name, Email, Student index, Password (index is always shown/required because the only public role is STUDENT). Validation: all inputs `required`; email uses `type="email"`; password `minLength={8}` with an "At least 8 characters" hint; the submit handler additionally trims + guards empty fields and enforces the 8-char rule before calling the API (toast on failure). The backend remains authoritative (index uniqueness, email uniqueness, index-required-for-student).

**Success / error behavior.** On success: `toast.success('Account created! Please sign in.')` then `navigate('/login', { replace: true })` — **no auto-login** (matches the project's login-first flow, and avoids the `credits = null` incomplete-student state noted under Item #5). On error: the existing axios response interceptor surfaces the backend's `message` for 400s (duplicate email → "Email already in use", duplicate index → "Index number already in use", missing index → "Index number is required for students") as a toast; the page just resets its loading state. Raw stack traces are never shown. (Note: the interceptor's 401/403 logout-and-redirect branch is inert here because an unauthenticated visitor has no token.)

**Routing.** `/register` is public (under `AuthLayout`, outside `ProtectedRoute`); `/login` remains public; all protected routes unchanged. No "redirect authenticated users away from auth pages" convention exists in this project (LoginPage doesn't do it either), so RegisterPage doesn't add one — consistent with the existing code.

**Tests / build results (2026-08-17).**
- Frontend build: `npx vite build` **passes** (EXIT 0, 1849 modules — the new page bundled; was 1848 before). `npx tsc -p tsconfig.app.json --noEmit` reports only the **pre-existing** `tsconfig.app.json` TS5101 `baseUrl` deprecation (documented under Items #2/#3, unrelated to this task) — **zero** errors in the new/changed files.
- Manual browser verification (Vite dev server, no backend running): `/register` renders all four fields + the 8-char hint + the students-only note + the "Sign in" link, with **no role selector**; `/login` shows the new "Register" link; clicking it performs client-side navigation to the rendered RegisterPage; `/register` loads directly without authentication (ProtectedRoute does not bounce it). Actual form submission against the live API was **not** exercised because the backend cannot run here (no JDK/Maven).
- **No automated frontend test runner** is configured in this project (the `build` script is `tsc -b && vite build`; there is no vitest/testing-library) — consistent with Items #1–#11, none of which added one. No test framework was introduced (staying within Item #12 scope); verification was via the type-check, the production build, and the manual browser walkthrough above.
- Backend: **NOT compiled or tested** — this machine has no JDK/Maven (`java`/`mvn` not found, `JAVA_HOME` empty). No backend production code was changed, and the existing `AuthIntegrationTest` was left untouched. **No claim is made that backend tests pass.**

**Security decisions (summary).** (1) Public UI limited to STUDENT (no role selector; request hard-codes `role: "STUDENT"`). (2) No auto-login; redirect to `/login` on success. (3) Password never logged, never put in URL/query/localStorage, never hashed client-side — sent over the POST body for the backend's existing BCrypt encoder. (4) **BUG-2 / P0.2 (backend accepts arbitrary roles) intentionally left OPEN** — tracked separately, not fixed here.

---

**Read-side IDOR (P1) — thesis-related read endpoints lack per-resource authorization: FIXED (2026-08-17).**

Six authenticated read endpoints previously returned data to **any** logged-in user who knew (or guessed) a thesis/defense UUID — only `SecurityConfig.anyRequest().authenticated()` applied, with no owner/role/relationship check. A STUDENT could read another student's thesis title/comments/PII, full status history, committee membership, defense schedule, and **grade**. This is now closed at the service layer (the authoritative boundary), consistent with the pre-existing version-read (`checkThesisReadAccess`) and record-PDF (`requireRecordAccess`) policies.

**The read policy (single source of truth).** New Spring component `com.praksa.security.ThesisReadAccessPolicy`:
- `hasReadAccess(Thesis, User)` / `requireReadAccess(Thesis, User)` (throws `UnauthorizedException` → 403).
- **Allowed** to read a thesis and its related resources:
  1. the **STUDENT who owns** the thesis (`thesis.student.id == user.id`);
  2. the **assigned MENTOR** (`thesis.mentor.id == user.id`);
  3. **any user holding a CommitteeMember seat on THIS thesis** — checked via `CommitteeMemberRepository.existsByThesisAndProfessor(thesis, user)`, i.e. membership is verified **against the requested thesis**, never inferred from the role;
  4. **STUDENT_SERVICE** (workflow oversight);
  5. **ARCHIVE** (official record keeping).
- **Denied (403):** everyone else — an unrelated STUDENT, a MENTOR who is neither assigned nor seated, and (importantly) a `COMMITTEE`-role user with **no seat on this thesis**. Having a privileged role is not, by itself, access to an arbitrary thesis. This is the exact same rule already encoded in `ThesisVersionServiceImpl.checkThesisReadAccess` and `DefenseResultServiceImpl.requireRecordAccess`; the fix factors it out instead of adding a 3rd/4th copy.

**Endpoints/methods protected (all six now call `requireReadAccess` before returning any data):**
| # | Service method | Controller endpoint | Thesis resolved from |
|---|---|---|---|
| 1 | `ThesisServiceImpl.getThesisById` | `GET /api/theses/{id}` | path `id` |
| 2 | `ThesisServiceImpl.getStatusHistory` | `GET /api/theses/{id}/history` | path `id` |
| 3 | `CommitteeServiceImpl.getCommittee` | `GET /api/theses/{id}/committee` | path `id` |
| 4 | `DefenseServiceImpl.getActiveDefense` | `GET /api/theses/{id}/defenses/active` | path `id` |
| 5 | `DefenseServiceImpl.getAllDefenses` | `GET /api/theses/{id}/defenses` | path `id` |
| 6 | `DefenseResultServiceImpl.getResult` | `GET /api/theses/{id}/defenses/{did}/result` | `defense.getThesis()` (after the existing defense-belongs-to-thesis 400 check) |

Each method loads the thesis, then calls `thesisReadAccessPolicy.requireReadAccess(thesis, securityUtils.getCurrentUser())`. Invalid/missing resource behavior is unchanged (`ResourceNotFoundException` → 404; defense-not-belonging-to-thesis → `BadRequestException` → 400). No write authorization was touched. The six methods have **no internal service callers** (verified by grep) — only the thin controllers call them — so there is no double-guard or broken internal path.

**Role-by-role access matrix (applies to all six endpoints):**
| Caller | Result |
|---|---|
| Owner STUDENT | ✅ allowed |
| Unrelated STUDENT | ❌ 403 |
| Assigned MENTOR | ✅ allowed |
| Unassigned/unseated MENTOR | ❌ 403 |
| MENTOR holding a FORMAL committee seat on this thesis | ✅ allowed (via seat) |
| COMMITTEE-role user **seated** on this thesis | ✅ allowed |
| COMMITTEE-role user **not seated** on this thesis | ❌ 403 |
| STUDENT_SERVICE | ✅ allowed |
| ARCHIVE | ✅ allowed |
| Any other authenticated role | ❌ 403 |

**COMMITTEE seat behavior + a known interaction (edge case).** Committee seats are only ever held by **MENTOR-role** professors — `CommitteeServiceImpl.proposeCommittee` requires each proposed member to be `Role.MENTOR` and auto-seats the mentor. The dedicated `COMMITTEE`-role user (`committee@test.com`, "for defense grading only") therefore **never** satisfies `existsByThesisAndProfessor` and, under this policy, has **no read access to any thesis's detail/defense/result** unless it is genuinely seated. This is intended per the task's matrix and is identical to how the pre-existing version-read and record-PDF policies already treated that user. The consequence: the `COMMITTEE`-role user can still *grade* via `recordResult` (which is un-scoped — the separate **write-side grading IDOR / BUG-13**, left OPEN) but can no longer read the thesis it is grading through these endpoints. Resolving that inconsistency is the **CURRENT NEXT STEP** (scope grading to the thesis's committee); doing so is the coherent place to decide whether COMMITTEE-role users should be seated at all.

**Frontend — 401 vs 403 (this is the only behavioral frontend change).** The global axios response interceptor (`src/api/client.ts`) previously logged the user out on **both** 401 and 403. That is wrong for a resource-level authorization denial: a valid session must survive a 403. Now:
- **401 = unauthenticated** (missing/invalid/expired JWT) → clear the session, toast "session expired", redirect to `/login` (unchanged behavior).
- **403 = forbidden** (valid session, not allowed to read THIS resource) → **do NOT log out or redirect**; surface the backend's access-denied message as a toast and let the calling component render an appropriate state.

Supporting page changes (kept minimal, directly caused by the fix):
- `src/pages/ThesisDetailPage.tsx` — the initial `Promise.all([getById, getHistory])` now has a `.catch` that records the HTTP status and renders a new **`ThesisAccessDenied`** panel ("Access denied" on 403) instead of a blank page; the session is preserved.
- `src/pages/DefensesPage.tsx` — the per-thesis `getActive`/`getResult` eager-fetch is wrapped in try/catch so a single forbidden thesis degrades that one card to "no info" instead of failing the whole list (relevant only to the unseated COMMITTEE-role user described above).
- No other frontend changes; no new frontend test framework introduced (none exists in this project).

**Files changed.**
- *Backend (production):* `security/ThesisReadAccessPolicy.java` (**new**); `service/impl/ThesisServiceImpl.java` (+dep, guard in `getThesisById` & `getStatusHistory`); `service/impl/CommitteeServiceImpl.java` (+dep, guard in `getCommittee`); `service/impl/DefenseServiceImpl.java` (+dep, guard in `getActiveDefense` & `getAllDefenses`); `service/impl/DefenseResultServiceImpl.java` (+dep, guard in `getResult`).
- *Backend (tests):* `src/test/java/com/praksa/security/ThesisReadAccessPolicyTest.java` (**new**); `src/test/java/com/praksa/service/ThesisReadIdorGuardTest.java` (**new**).
- *Frontend:* `src/api/client.ts` (401/403 split); `src/pages/ThesisDetailPage.tsx` (access-denied state); `src/pages/DefensesPage.tsx` (resilient per-thesis fetch).

**Tests.**
- **`ThesisReadAccessPolicyTest`** (pure Mockito over the real `ThesisReadAccessPolicy` with a mocked `CommitteeMemberRepository`) — the authoritative role matrix: owner STUDENT allowed, assigned MENTOR allowed, seated MENTOR-formal-member allowed, seated COMMITTEE allowed, STUDENT_SERVICE allowed, ARCHIVE allowed; unrelated STUDENT / unassigned MENTOR / unseated COMMITTEE rejected; plus an explicit "membership is checked against the **requested** thesis" case, and assertions that the privileged roles return **without** even querying the committee repo.
- **`ThesisReadIdorGuardTest`** (Mockito, real service impls with a mocked policy) — proves each of the six methods invokes `requireReadAccess` **before** touching sensitive data: a "denied" test per method (policy throws → method throws 403, and the downstream repo — history/committee/defense/result — is `never()` queried) and an "allowed" test per method (policy no-op → method proceeds past the guard). Confirms knowing the UUID does not bypass authorization and that `getResult` authorizes the thesis reached via the defense.

**Build / test status (2026-08-17) — honest.**
- Backend: **NOT compiled or run** — this machine has no JDK/Maven (`java`/`mvn` not on PATH, `JAVA_HOME` empty; only `mvnw.cmd`, which needs a JDK). The production + test code was written and verified by inspection against the actual entity/repository/`SecurityUtils`/DTO signatures and the existing Mockito conventions (the new tests mirror `DefenseRecordPdfAccessTest`). **No claim is made that the backend compiles or that the tests pass** — run `mvnw.cmd test` on a machine with a JDK.
- Frontend: `npx vite build` **passes** (EXIT 0, 1849 modules). `npx tsc -p tsconfig.app.json --noEmit` reports **zero** errors in the changed files (only the pre-existing `tsconfig.app.json` TS5101 `baseUrl` deprecation, unrelated). Not exercised against a live backend (backend cannot run here).

**Explicitly left OPEN (out of scope for this task):** BUG-13 / write-side grading IDOR (`recordResult` un-scoped — now the CURRENT NEXT STEP), BUG-6 (SMTP), BUG-19 (`/notifications/unsent` no role check), credits-cannot-be-set-from-UI, `ELIGIBILITY_REJECTED` dead-end, BUG-15 (secrets in source), duplicate defense reminder, user-enumeration via `GET /api/users?role=`, and the intentionally over-broad `downloadApplicationPdf` (any COMMITTEE user) / `findByRegistrationNumber` (archive search) — none were changed.

---

**Roadmap Item #11 — Defense record PDF ("записник за одбрана"): COMPLETE / VERIFIED (2026-08-17).**

A printable official defense record is generated on demand and downloaded as a PDF. Verified against the actual source; reuses the project's existing PDF engine (OpenHTMLtoPDF) — no second framework was added.

**Endpoint.** `GET /api/theses/{thesisId}/defenses/{defenseId}/record-pdf` (added to `DefenseController`, which now also injects `DefenseResultService`). Follows the project's nested defense-URL convention (chosen over the task's illustrative `/api/defenses/{id}/record-pdf`). Returns `Content-Type: application/pdf`, `Content-Disposition: attachment; filename="zapisnik-odbrana-{defenseId}.pdf"`, body = raw PDF bytes (`ResponseEntity<byte[]>`).

**PDF generation.** New `com.praksa.service.DefenseRecordPdfService` — mirrors `ApplicationPdfService`: builds a self-contained inline-CSS A4 HTML document and renders it with OpenHTMLtoPDF (`PdfRendererBuilder`). Unlike the application form it is **not persisted to disk** — it is rendered in-memory (`ByteArrayOutputStream`) and streamed back, since a fresh render always reproduces it and the archive number already lives on the thesis. User-controlled text is HTML-escaped (`escape()` helper, same as `ApplicationPdfService`).

**Cyrillic / Unicode.** The document is in **Macedonian Cyrillic**. Base-14 PDF fonts (Helvetica) have no Cyrillic glyphs, so the PDF is typeset in **Noto Sans** (SIL Open Font License — redistributable), bundled at `src/main/resources/fonts/NotoSans-Regular.ttf` + `NotoSans-Bold.ttf` and registered via `builder.useFont(FSSupplier, "Noto Sans", weight, BaseRendererBuilder.FontStyle.NORMAL, true)` in both 400/700 weights (real bold, not faux). Glyph coverage verified with fontTools incl. Macedonian-specific letters (ј, ѕ, ќ, ѓ). Missing font → loud `IllegalStateException`, never silent boxes.

**PDF contents.** Header "ЗАПИСНИК ЗА ОДБРАНА НА ДИПЛОМСКА РАБОТА"; sections: **Студент** (име и презиме, индекс, е-пошта), **Дипломска работа** (наслов, ментор), **Комисија за одбрана** (numbered table of every committee member + role label: `MENTOR_MEMBER`→"Ментор", `FORMAL_MEMBER`→"Член на комисија"), **Одбрана** (датум, време, просторија, статус), **Резултат** (оценка, забелешки, архивски број, записничар), **Потписи** (one signature line per committee member with name+role, plus the student). No president seat is invented (the model has none). Unavailable fields render as "—"; nothing is fabricated.

**Availability / state rule.** The record is available **only after a result has been recorded** (defense graded). No result → `400 BadRequest` ("available only after the defense has been graded"). Recording a result is also what archives the thesis, so a present result guarantees the archive registration number is populated. A merely scheduled (ungraded) defense never yields a record with a fake/blank grade.

**Authorization (server-side — the security boundary; the frontend button is NOT authorization).** `DefenseResultServiceImpl.generateRecordPdf` → `requireRecordAccess(thesis)` allows: the **student owner**, the **assigned mentor**, a **professor seated on THIS thesis's committee** (`committeeRepository.existsByThesisAndProfessor`), **STUDENT_SERVICE**, and **ARCHIVE**. A `COMMITTEE`-role user who is not on this committee is rejected (`403`) — deliberately **stricter** than the application-PDF endpoint (which allows any COMMITTEE user; that over-broad rule is tracked separately and left unchanged). The endpoint also rejects a defense whose `thesis` doesn't match the path `thesisId` (`400`), so mixing a known defense UUID with an unrelated thesis fails.

**Frontend.** `api/defenseApi.ts` → `downloadRecordPdf(thesisId, defenseId)` (axios `responseType: 'blob'`). `features/defense/DefenseSection.tsx` → **"Симни записник"** button (icon `FileDown`) in the result action row, shown only when a `result` exists; downloads via the standard Blob→object-URL→anchor pattern used for version/application PDFs. `DefensesPage.tsx` was intentionally **not** changed — its cards are `Link`s to the detail page and, like "Record Grade", the record download lives on the detail page (consistent UX; avoids nested click-target/stopPropagation complexity).

**Files changed / added.** *Backend:* `service/DefenseRecordPdfService.java` (new), `service/DefenseResultService.java` (+`generateRecordPdf`), `service/impl/DefenseResultServiceImpl.java` (+method, +`requireRecordAccess`, +`CommitteeMemberRepository`/`DefenseRecordPdfService` deps), `controller/DefenseController.java` (+endpoint, +`DefenseResultService` dep), `src/main/resources/fonts/NotoSans-Regular.ttf` + `NotoSans-Bold.ttf` (new). *Frontend:* `api/defenseApi.ts`, `features/defense/DefenseSection.tsx`.

**Tests (new, `src/test/java/com/praksa/service/`).**
- `DefenseRecordPdfAccessTest` (Mockito, PDF renderer mocked) — student owner allowed (renderer invoked with the loaded thesis/defense/result/committee); seated committee member allowed; ARCHIVE allowed; unrelated COMMITTEE user → 403 with no render and no result lookup; ungraded defense (no result) → 400 with no render; defense not belonging to path thesis → 400.
- `DefenseRecordPdfServiceTest` (real OpenHTMLtoPDF render, no mocks) — renders a valid non-trivial PDF from Cyrillic input (asserts `%PDF-` header + size), exercising the bundled-font path so a missing/broken font would fail the test; also tolerates an empty committee list. Existing `DefenseResultServiceNotificationTest` / `DefenseResultGradeValidationTest` / `ArchiveMetadataTest` remain valid (the two new constructor deps are null-injected by Mockito in those recordResult-only tests).

**Build/test status (2026-08-17).**
- Frontend: `vite build` **passes** (EXIT 0, 1848 modules — my changes bundled). `tsc` clean for my two files; the only errors are the PRE-EXISTING `tsconfig.app.json` `baseUrl` TS5101 deprecation and the PRE-EXISTING unused-`FileText` TS6133 in `features/versions/VersionUploader.tsx` — both documented under earlier items, unrelated to Item #11.
- Backend: **NOT compiled or run** — no Java/Maven on this machine (`java`/`mvn` not on PATH, `JAVA_HOME` empty; only `mvnw.cmd`, which itself needs a JDK). The Java changes and both new test classes were written and verified by inspection against the actual entity/DTO/repository/service signatures and the OpenHTMLtoPDF 1.0.10 API (`FSSupplier`, `BaseRendererBuilder.FontStyle` confirmed present in the jar). **I make no claim that the backend compiles or that the tests pass.**

**Limitations / notes.**
- `DefenseRecordPdfServiceTest` renders a real PDF but does not parse glyphs back out — it guards against render failure / missing font, not against a hypothetical wrong-glyph mapping (glyph coverage was instead verified separately with fontTools).
- The record is not cached/persisted; every download re-renders (fine at school scale).
- Pre-existing unrelated bugs remain open (BUG-2 register role escalation, BUG-6 SMTP placeholders, BUG-13 `recordResult` allows STUDENT_SERVICE, BUG-19 `/notifications/unsent`, and the over-broad application-PDF COMMITTEE access).

---

**Roadmap Item #10 — Archive metadata: COMPLETE / VERIFIED (2026-08-17).**

Verified against the actual source (not the previous summary). **All four archive-metadata fields were already fully implemented end-to-end** — entity, response DTO, mapping, population at the ARCHIVED transition, authorization, and both frontend pages. This pass added the focused tests Item #10 requires and documented the design. **No production code was changed** (backend or frontend) — nothing was genuinely missing.

**The four fields (all present, verified).**
| Field (frontend/DTO) | Entity source | Java type | Notes |
|---|---|---|---|
| `archiveRegistrationNumber` | `Thesis.archiveRegistrationNumber` | `String` | `@Column(unique = true, length = 50)` — DB-level uniqueness |
| `archiveDate` | `Thesis.archiveDate` | `OffsetDateTime` | project-standard date/time type |
| `archivedByName` | `Thesis.archivedBy` (`@ManyToOne User`) → `getFullName()` | `String` (flattened in DTO) | DTO also exposes `archivedById` (UUID) |
| `archiveNotes` | `Thesis.archiveNotes` | `String` (`columnDefinition = "text"`) | reused existing field — no duplicate data |

**Schema.** No migration system in the project (no Flyway/Liquibase; `pom.xml` has none). Schema is JPA-generated via Hibernate `ddl-auto=update` — the four columns already exist on the `theses` table from their `@Column` annotations. Nothing to add.

**Response DTO + mapping.** `ThesisResponse` declares `archiveRegistrationNumber`, `archiveDate`, `archivedById`, `archivedByName`, `archiveNotes` and populates every one inside `ThesisResponse.from(thesis)` (built inside the `@Transactional` boundary; `archivedBy` flattened to id + name). `ThesisResponse` is the return type of every thesis endpoint used by `ThesisDetailPage` (`GET /api/theses/{id}`, `GET /api/theses/my`) and `ArchivePage` (`GET /api/theses/committee`/`defenses` scoping and `GET /api/theses/by-registration-number/{n}`), so the metadata is returned everywhere it is needed.

**Exact population workflow.** The ONLY place a thesis transitions to `ARCHIVED` is `DefenseResultServiceImpl.recordResult` (grep of `ThesisStatus.ARCHIVED` confirms every other reference is a read-only filter for the active-thesis limit / list scoping — there is no second archive workflow). Immediately BEFORE the `transitionStatus(thesis, ARCHIVED, recorder)` call, in one place:
- `archiveRegistrationNumber = generateRegistrationNumber(now.getYear())`,
- `archiveDate = now` (`OffsetDateTime.now()`),
- `archivedBy = recorder` (the authenticated user recording the grade → surfaces as `archivedByName`),
- `archiveNotes` is **left untouched** — preserved if already set, never fabricated. Per the Item #10 spec ("preserve/use the existing archive notes if they already exist") and the "no duplicate data" rule, the grade notes stay on `DefenseResult.notes` and are NOT copied onto the thesis. (Editing `archiveNotes` after archiving is a separate future item — P2.2 — and was intentionally NOT added here.)

**Registration-number strategy.** `DT-YYYY-NNNN`, sequence reset per calendar year. `generateRegistrationNumber(year)` = `"DT-" + year + "-"` prefix, then `countByArchiveRegistrationNumberStartingWith(prefix) + 1`, zero-padded to 4 digits (`%04d`). Because it always uses `count + 1`, a new number never reuses an existing sequence value; the `unique` constraint on `archive_registration_number` is the final guarantee — if two archivings race for the same number, the loser's transaction rolls back.

**Authorization.** Metadata is only ever written inside `recordResult`, which requires the caller to be `COMMITTEE` or `STUDENT_SERVICE` (else `UnauthorizedException` → 403) and the thesis to be `DEFENSE_SCHEDULED`. There is NO public/manual metadata-update endpoint, so a random user cannot set archive metadata directly. (BUG-13 note still stands: `recordResult` also permits `STUDENT_SERVICE` in addition to `COMMITTEE` — an orthogonal role-scope question, not part of Item #10.)

**Frontend (verified, no changes needed).** `types/api.ts` `Thesis` already declares all five archive fields. `ThesisDetailPage.tsx` renders an "Archive Record" card (registration number, "Archived on", "Archived by", and conditional archive notes) when `status === 'ARCHIVED'`. `ArchivePage.tsx` shows the registration-number pill, archive date, archived-by name, conditional notes, and supports search by registration number. No redesign, no edits.

**Tests added (`src/test/java/com/praksa/service/ArchiveMetadataTest.java`, pure Mockito, matching the existing style).**
- archiving populates all metadata (reg number `DT-YYYY-0001`, `archiveDate` non-null & recent, `archivedBy` == recorder, status `ARCHIVED`);
- `STUDENT_SERVICE` archiver also becomes `archivedBy`;
- registration number is the next in sequence (5 existing → `DT-YYYY-0006`, never a reused value);
- existing `archiveNotes` preserved (not overwritten);
- `archiveNotes` stays null when absent (never fabricated from grade notes);
- a failed archive (cancelled defense) sets NO metadata and leaves status `DEFENSE_SCHEDULED`.
The pre-existing `DefenseResultServiceNotificationTest` / `DefenseResultGradeValidationTest` remain valid and untouched.

**Build/test status (2026-08-17).**
- Backend: **NOT compiled or run** — no Java/Maven on this machine (`java`/`mvn` not on PATH, `JAVA_HOME` empty, only `mvnw.cmd`, which itself needs a JDK). The new test class was written and verified by inspection against the actual entity/DTO/service/repository signatures and the existing test style; **I make no claim that it compiles or passes.**
- Frontend: **no changes made**, so nothing to build for this item (type + both pages already complete).

**Limitations / notes.**
- `archiveNotes` has no populating or editing path today (preserve-only by design); adding an ARCHIVE-role notes editor is future P2.2, deliberately out of Item #10 scope.
- Registration-number uniqueness ultimately relies on the DB `unique` constraint under concurrency (correct, but not exercisable without a DB in this environment).
- Pre-existing unrelated bugs remain open (BUG-2, BUG-6, BUG-13, BUG-19, etc.).

---

**Roadmap Item #9 — Automatic committee review acceptance after 5 working days: COMPLETE / VERIFIED (2026-08-17).**

Verified the pre-existing `ScheduledTasksService.autoAdvanceStaleCommitteeReviews` job against the exact Item #9 specification (trusting the source, not the previous summary). The core mechanism was already correct; two small, spec-driven correctness fixes were applied and the working-day math was made unit-testable. **No redesign, no new scheduled job, no new DB column, no new notification type.**

**What the existing job already did (verified correct).**
- Runs on `@Scheduled(fixedDelay = 30min, initialDelay = 60s)` — the existing scheduler was reused; frequency unchanged.
- Searches for theses in `ThesisStatus.COMMITTEE_REVIEW` via `ThesisRepository.findStaleCommitteeReviews(cutoff)` (JPQL: `status = 'COMMITTEE_REVIEW' AND committeeReviewStartedAt IS NOT NULL AND committeeReviewStartedAt <= :cutoff`).
- **Timer start** = `Thesis.committeeReviewStartedAt`, stamped in `CommitteeServiceImpl.approveCommittee` at the exact moment the thesis transitions into `COMMITTEE_REVIEW`. This is a dedicated, already-populated column that genuinely records when the committee-review waiting state begins — so NO new column and NO reliance on unrelated timestamps (creation/application dates). (History rows also record the transition, but the column is the authoritative, index-free start used by the query.)
- **Working days** counted by `minusBusinessDays(now, 5)`: a simple `minusDays(1)` loop that decrements the counter only on Mon–Fri, so Saturday/Sunday never count. Verified against the spec's Friday example: review starts Fri → Mon=1, Tue=2, Wed=3, Thu=4, Fri=5 (the 5th working day is the following Friday).
- **Transition** = the same logical acceptance as manual `acceptCommitteeReview`: `COMMITTEE_REVIEW → COMMITTEE_ACCEPTED → PENDING_DEFENSE_CHECK`, via the local `recordTransition` helper (its own copy, `changedBy = null` for system actions — per the architecture rule that `ScheduledTasksService` keeps a private transition helper to avoid a circular dependency on `ThesisServiceImpl`). Each transition writes a `thesis_status_history` row.
- **Notification** = the distinct `NotificationType.COMMITTEE_REVIEW_AUTO_ADVANCED` (NOT `COMMITTEE_REVIEW_ACCEPTED`), so recipients know the acceptance was automatic after 5 business days of silence. Only this one type is sent for the automatic event (never both).
- **Idempotency** = after advancing, the thesis is no longer in `COMMITTEE_REVIEW`, so the query excludes it on subsequent runs; there is also a defensive in-loop `if (status != COMMITTEE_REVIEW) continue`.

**Two production fixes applied this pass (minimal, spec-driven).**
1. **Inclusive 5-working-day boundary.** `ThesisRepository.findStaleCommitteeReviews` used strict `<`, so a review at *exactly* 5 working days would not be accepted until the next 30-min tick. Changed `<` → `<=` so "exactly 5 working days elapsed → automatically accepted" (spec §7 / test #2) holds at the boundary. Zero risk to idempotency.
2. **Removed a duplicate mentor notification.** The auto-advance path notified the mentor explicitly *and* again through the committee loop (the mentor holds a `MENTOR_MEMBER` seat, always present because a thesis only reaches `COMMITTEE_REVIEW` via `approveCommittee`, which requires an approved 3-member committee). The manual `acceptCommitteeReview` flow already avoids this. Removed the explicit `thesis.getMentor()` notify so each recipient (student + every committee professor, mentor once) gets exactly one `COMMITTEE_REVIEW_AUTO_ADVANCED` (spec §6: verify recipients vs. manual flow, avoid duplicates).

**Testability change (no behavior change).** `ScheduledTasksService.minusBusinessDays` was made package-private (was `private`) so the 5-working-day math can be unit-tested directly.

**Recipients (final).** Student + every committee member (mentor included exactly once via the committee seat). No mentor duplicate. Matches the manual acceptance recipient set.

**Files changed (backend).**
- `repository/ThesisRepository.java` — `findStaleCommitteeReviews` comparison `<` → `<=` (+ doc).
- `service/ScheduledTasksService.java` — removed duplicate mentor notify in `autoAdvanceStaleCommitteeReviews`; `minusBusinessDays` made package-private (+ comments).

**Files added (backend tests, `src/test/java/com/praksa/service/`, pure JUnit 5 + Mockito, matching the existing Item #4–#8 style).**
- `ScheduledTasksBusinessDaysTest` — the CORE 5-working-day rule via `minusBusinessDays` + a helper reproducing the job's exact `startedAt <= now-5bd` decision: Friday example (5th working day = next Friday); weekend does not count (Monday −1 working day = previous Friday); never lands on a weekend for offsets 1–10; fewer than 5 working days → not stale; **exactly 5 → stale (inclusive)**; more than 5 → stale; a weekend in the middle does not shorten the wait; a start one minute after the cutoff is not yet stale.
- `AutoAdvanceCommitteeReviewTest` — orchestration (Mockito): stale review advances `COMMITTEE_REVIEW → COMMITTEE_ACCEPTED → PENDING_DEFENSE_CHECK` with two `changedBy = null` history rows in order; notifies student + each committee member exactly once (mentor NOT double-notified); no stale reviews → no-op; a returned thesis already past review is skipped (defensive re-check); idempotent — a second run after acceptance does nothing more (2 history rows, 2 notifications total).

**Build/test status (2026-08-17).**
- Backend: **NOT compiled or run** — no Java/Maven on this machine (`java`/`mvn` not on PATH, `JAVA_HOME` empty, only `mvnw.cmd`; `mvnw.cmd` still needs a JDK). The two production edits and the two new test classes were written and verified by inspection against the actual entity/repository/service signatures and the existing test style; **I make no claim that they compile or pass.** The `<` → `<=` JPQL change in particular cannot be exercised here (no H2 in `pom.xml`, and the DB is PostgreSQL runtime-scope), so it is verified by inspection only; the working-day math it depends on IS unit-tested via `minusBusinessDays`.
- Frontend: **no changes** — Item #9 is a backend-only scheduled job with no UI surface (the in-app notification list already renders `COMMITTEE_REVIEW_AUTO_ADVANCED`).

**Limitations / notes.**
- The exact-boundary correctness (`<=`) and the JPQL query itself are not covered by an executable test in this environment (no H2/DB). If a DB-backed test harness is added later, a `@DataJpaTest` for `findStaleCommitteeReviews` would close that gap end-to-end.
- Because the job runs every 30 minutes, in production a review crossing the 5-working-day mark is auto-accepted at the first tick at/after the boundary (worst case ~30 min later) — expected for a periodic job.
- Weekend-only working-day model (Mon–Fri); no public-holiday calendar (intentionally out of scope per the spec).
- Pre-existing unrelated bugs remain open (BUG-2, BUG-6, BUG-13, BUG-19, etc.).

---

**Roadmap Item #8 — Explicit defense-condition verification: COMPLETE (2026-08-17).**

Verified against the actual source. Added an explicit STUDENT_SERVICE action that confirms a student has met the defense conditions BEFORE a defense can be requested/scheduled. Simple MANUAL verification (two booleans) — deliberately NO integration with any external examination/student-information system. Items #1–#7 untouched. Item #9's scheduled auto-accept logic was NOT modified.

**Existing flow found (before this item).**
- `CommitteeServiceImpl.acceptCommitteeReview` and the scheduled `autoAdvanceStaleCommitteeReviews` job both fast-forward `COMMITTEE_REVIEW → COMMITTEE_ACCEPTED → PENDING_DEFENSE_CHECK` (unchanged — old BUG-7 left alone; it is Item #9-adjacent).
- Item #6 had the STUDENT request a defense straight from `PENDING_DEFENSE_CHECK` (`DefenseServiceImpl.requestDefense`: `PENDING_DEFENSE_CHECK → PENDING_DEFENSE_SCHEDULING`), then STUDENT_SERVICE scheduled (`PENDING_DEFENSE_SCHEDULING → DEFENSE_SCHEDULED`). There was NO explicit eligibility check — the student could request without any Student Service confirmation.

**New eligibility workflow (exact).**
```
COMMITTEE_ACCEPTED → PENDING_DEFENSE_CHECK        (existing; accept-review or auto-advance — UNCHANGED)
  [STUDENT_SERVICE]  PATCH /api/theses/{id}/defense-eligibility  { examsCompleted, documentationComplete }
PENDING_DEFENSE_CHECK → PENDING_DEFENSE_SCHEDULING  (ONLY when BOTH booleans are true; notify student DEFENSE_ELIGIBILITY_VERIFIED)
  [STUDENT]          POST /api/theses/{id}/defenses/request
PENDING_DEFENSE_SCHEDULING (unchanged status)       (no status change; notify STUDENT_SERVICE DEFENSE_REQUESTED)
  [STUDENT_SERVICE]  POST /api/theses/{id}/defenses  { room, scheduledAt }
PENDING_DEFENSE_SCHEDULING → DEFENSE_SCHEDULED       (Defense row created; notify student + committee — UNCHANGED)
```

**Design decision (why `requestDefense` changed).** Item #8 requires the verification itself to perform `PENDING_DEFENSE_CHECK → PENDING_DEFENSE_SCHEDULING` via `transitionStatus()`. That transition was previously owned by the Item #6 student request. Since both cannot own the same transition, the student request was moved to operate from the **post-verification** state `PENDING_DEFENSE_SCHEDULING` and is now **notification-only** (it fires `DEFENSE_REQUESTED` but does not change status). Net effect: the student can no longer request a defense until Student Service has explicitly verified eligibility. The Item #6 request→schedule workflow still works — it simply starts one step later.

**Endpoint + request DTO.**
- `PATCH /api/theses/{id}/defense-eligibility` → `ThesisController.verifyDefenseEligibility` → `ThesisService.verifyDefenseEligibility`. Returns `ApiResponse<ThesisResponse>` (updated thesis, now `PENDING_DEFENSE_SCHEDULING`).
- Body `DefenseEligibilityRequest { @NotNull Boolean examsCompleted; @NotNull Boolean documentationComplete; }` (new DTO in `dto/thesis/`). `@NotNull` guarantees explicit booleans; the "both must be true" rule is enforced in the service layer.

**Business rule (server-side, authoritative).** In `ThesisServiceImpl.verifyDefenseEligibility`: `requireRole(STUDENT_SERVICE)` → `requireStatus(PENDING_DEFENSE_CHECK)` → then `if (!examsCompleted || !documentationComplete) throw BadRequestException` (a null boolean counts as NOT confirmed). Only when BOTH are true does it call `transitionStatus(thesis, PENDING_DEFENSE_SCHEDULING, serviceUser)` (writes a `thesis_status_history` row) and notify the student. A failed verification changes nothing: no transition, no history row, no success notification (all guards run before any state change).

**Authorization (backend-enforced, not just UI).** STUDENT_SERVICE → can verify. STUDENT / MENTOR / COMMITTEE / ARCHIVE → 403 (`requireRole(STUDENT_SERVICE)` throws `UnauthorizedException`). Wrong status → 400 (`requireStatus`). `requestDefense` remains STUDENT-owner-only and now additionally requires `PENDING_DEFENSE_SCHEDULING` (i.e. post-verification), so a student cannot bypass the check.

**Status transitions.** `PENDING_DEFENSE_CHECK → PENDING_DEFENSE_SCHEDULING` (STUDENT_SERVICE, on successful verification). No new `ThesisStatus` value was added — the existing `PENDING_DEFENSE_SCHEDULING` (Item #6) is reused; its meaning is now "eligibility verified; student may request; awaiting scheduling".

**Notifications.** One new `NotificationType.DEFENSE_ELIGIBILITY_VERIFIED` (student ← Student Service, on success). A new type was justified because no existing type represented this event, mirroring how Item #6 added `DEFENSE_REQUESTED`; it follows the same transaction→row→async-email architecture (no entity crosses the `@Async` boundary). Failed verification sends NOTHING. `DEFENSE_REQUESTED` (student request → STUDENT_SERVICE) is unchanged and still fires from `requestDefense`.

**Frontend changes.**
- `api/thesisApi.ts` — added `verifyDefenseEligibility(id, examsCompleted, documentationComplete)` (PATCH `/theses/{id}/defense-eligibility`).
- `features/defense/DefenseSection.tsx` — in `PENDING_DEFENSE_CHECK`: STUDENT_SERVICE sees a **Defense Eligibility** widget (two checkboxes "All required exams completed" / "Documentation complete" + **Confirm Eligibility** button, disabled unless BOTH are checked); STUDENT sees an "awaiting eligibility check" notice (no Request button). In `PENDING_DEFENSE_SCHEDULING`: STUDENT sees **Request Defense** (moved here from `PENDING_DEFENSE_CHECK`), STUDENT_SERVICE sees **Schedule Defense** (unchanged). After confirming eligibility, thesis data refreshes and the new status/actions appear.
- `pages/DefensesPage.tsx` — relabeled the `PENDING_DEFENSE_SCHEDULING` pill to "Eligibility verified — awaiting scheduling" and the date to "Eligibility verified …"; added an "Awaiting eligibility check" pill for `PENDING_DEFENSE_CHECK`.
- `pages/NotificationsPage.tsx` — added the `DEFENSE_ELIGIBILITY_VERIFIED` friendly label.
- No `types/api.ts` / `StatusBadge` change needed (no new status; `PENDING_DEFENSE_SCHEDULING` already present). Frontend checks are UX only — the backend enforces role + status + both-booleans.

**Tests.**
- **NEW** `service/DefenseEligibilityVerificationTest` (pure Mockito on `ThesisServiceImpl`): (1) STUDENT_SERVICE + both true → `PENDING_DEFENSE_SCHEDULING` + history row + `DEFENSE_ELIGIBILITY_VERIFIED` to student; (2) exams=false → 400, unchanged, no history, no notification; (3) docs=false → 400, unchanged, no history; (4) both false → 400, unchanged; (5) STUDENT → 403; (6) MENTOR → 403; (7) wrong status (`COMMITTEE_ACCEPTED`) → 400, unchanged.
- **UPDATED** `service/DefenseRequestSchedulingTest`: test 1 now drives `requestDefense` from `PENDING_DEFENSE_SCHEDULING` (notify STUDENT_SERVICE, **no** status change, **no** new history row, no Defense row); added test 1b (student CANNOT request while still `PENDING_DEFENSE_CHECK` → 400); added test 8 (post-verification the full Item #6 chain still works: student requests → STUDENT_SERVICE schedules → `DEFENSE_SCHEDULED`). The mentor-cannot-schedule / wrong-status / cancel tests are unchanged.

**Build/test status (2026-08-17).**
- Frontend: `npx tsc -p tsconfig.app.json --noEmit` clean for my files (only the PRE-EXISTING `tsconfig.app.json` `baseUrl` TS5101 deprecation remains). `npx vite build` **passes** (EXIT 0, 1848 modules, changes bundled).
- Backend: **NOT compiled or run** — no Java/Maven on this machine (`java`/`mvn` not on PATH, `JAVA_HOME` empty, only `mvnw.cmd`). The Java production changes and the new/updated tests were written and verified by inspection against the actual DTO/service/controller/entity/repository signatures and the existing test style; **I make no claim that they compile or pass.** The new tests are pure Mockito unit tests consistent with the Item #4/#5/#6/#7 style.

**Files changed / added.**
- Backend NEW: `dto/thesis/DefenseEligibilityRequest.java`, `src/test/java/com/praksa/service/DefenseEligibilityVerificationTest.java`.
- Backend CHANGED: `model/enums/NotificationType.java` (+`DEFENSE_ELIGIBILITY_VERIFIED`), `service/ThesisService.java` (+`verifyDefenseEligibility`), `service/impl/ThesisServiceImpl.java` (+`verifyDefenseEligibility`), `controller/ThesisController.java` (+PATCH `/{id}/defense-eligibility`), `service/DefenseService.java` (comment), `service/impl/DefenseServiceImpl.java` (`requestDefense` re-gated to `PENDING_DEFENSE_SCHEDULING`, notification-only), `src/test/java/com/praksa/service/DefenseRequestSchedulingTest.java` (updated).
- Frontend CHANGED: `api/thesisApi.ts`, `features/defense/DefenseSection.tsx`, `pages/DefensesPage.tsx`, `pages/NotificationsPage.tsx`.

**Limitations / notes.**
- The student defense **request** no longer changes status (it is a signal only), so it is not recorded as its own `thesis_status_history` row — request/verification metadata for `PENDING_DEFENSE_SCHEDULING` is the verification transition (changedBy = the STUDENT_SERVICE verifier). A student could technically click "Request Defense" more than once (each just re-sends `DEFENSE_REQUESTED`); the UI hides the button after the first click within a session. STUDENT_SERVICE can also schedule without waiting for the request. These are acceptable for the manual workflow and are documented rather than gated with extra columns (task said do not add unnecessary DB columns).
- BUG-7 (accept-review + auto-advance fast-forward into `PENDING_DEFENSE_CHECK`) is intentionally left as-is — the explicit check is inserted at the `PENDING_DEFENSE_CHECK → PENDING_DEFENSE_SCHEDULING` boundary per the Item #8 spec, not at `COMMITTEE_ACCEPTED`. The alternative P1.4 design (verify at `COMMITTEE_ACCEPTED`) was NOT taken because the Item #8 spec explicitly requires the verification to operate on `PENDING_DEFENSE_CHECK` and produce `PENDING_DEFENSE_SCHEDULING`.
- Pre-existing bugs unrelated to Item #8 remain open (BUG-2 register role escalation, BUG-6 SMTP placeholders, BUG-13 `recordResult` allows STUDENT_SERVICE, BUG-19 `/notifications/unsent`, etc.).

---

**Roadmap Item #7 — Validate defense grade 5–10: COMPLETE (2026-08-17).**

Verified against the actual source. The requirement: a defense grade recorded via `recordResult` must be **strictly 5 through 10 inclusive** (4 → reject, 5–10 → accept, 11 → reject, null → reject). Most of the enforcement was already in place; this pass verified it end-to-end and closed the one real gap (service-level defense-in-depth). Items #1–#6 untouched. **Item #8 was deliberately NOT started.**

**Where the range is enforced (three layers, backend is authoritative).**
1. **DTO bean validation (already present, verified).** `dto/defense/RecordResultRequest.java` declares `@NotNull(message="Grade is required")`, `@Min(value=5)`, `@Max(value=10)` on `Integer grade`. This is the primary enforcement at the controller boundary.
2. **Controller `@Valid` (already present, verified).** `DefenseResultController.recordResult` annotates the body `@Valid @RequestBody RecordResultRequest`. So a bad grade is rejected with a 400 (bean-validation error) before the service is ever called. This is the only controller/DTO pair that records a result — grep of `recordResult` / `RecordResultRequest` / `DefenseResult` across `src/main/java` confirms no second path.
3. **Service-level guard (NEW this pass — defense-in-depth).** `DefenseResultServiceImpl.recordResult` now calls `validateGrade(request.getGrade())` as its **first statement**, before `getCurrentUser()`, before any repository read, before the `DefenseResult` is built/saved, before the status transition, and before any notification. Constants `MIN_DEFENSE_GRADE = 5` / `MAX_DEFENSE_GRADE = 10`; `validateGrade` throws `BadRequestException` when `grade == null || grade < 5 || grade > 10`. Rationale: `recordResult` is public on the `DefenseResultService` interface, so a future internal caller could bypass the controller's `@Valid`; this guard guarantees the 5–10 rule regardless of entry point.

**Accepted range.** `grade ∈ [5, 10]` inclusive. 4 → reject, 5/6/7/8/9/10 → accept, 11 → reject, null → reject.

**Invalid grade has no side effects.** Because the guard runs before anything else, an invalid grade: creates **no** `DefenseResult` row, performs **no** status transition, does **not** archive the thesis (no `ARCHIVED`, no registration number / archiveDate / archivedBy), and sends **no** `THESIS_GRADED` or `THESIS_ARCHIVED` notification. A valid grade behaves exactly as before (Item #4 grading/archive workflow unchanged).

**Grade storage semantics unchanged.** `DefenseResult.grade` stays `Integer`; registration-number generation, archive metadata, and the two notifications are all untouched.

**Frontend.** No change required. `features/defense/RecordGradeModal.tsx` already collects the grade with a range slider `min={5} max={10} step={1}` (default 8) — invalid values are physically unselectable, so obviously-invalid submission is already prevented at the UI. Backend remains authoritative. No unrelated UI changes made.

**Files changed / added.**
- **CHANGED (backend):** `service/impl/DefenseResultServiceImpl.java` — added `MIN_DEFENSE_GRADE`/`MAX_DEFENSE_GRADE` constants, a `validateGrade(Integer)` private helper, and the guard call at the top of `recordResult`. No other logic touched.
- **NEW (backend tests):** `src/test/java/com/praksa/service/DefenseResultGradeValidationTest.java` (service-level, pure Mockito) and `src/test/java/com/praksa/dto/defense/RecordResultRequestValidationTest.java` (DTO bean-validation, JUnit 5 parameterized, real `Validator`).
- **DTO / controller / frontend:** already correct — verified, not modified.

**Tests (cover the required matrix).**
- `DefenseResultGradeValidationTest`: grade 4 → rejected, grade 11 → rejected, null → rejected (each also asserts no `DefenseResult` saved, no status-history row, no notification); grade 5 → accepted and grade 10 → accepted (each asserts result saved, thesis `ARCHIVED` with a registration number, and both `THESIS_GRADED` + `THESIS_ARCHIVED` notifications sent — i.e. the existing workflow still runs).
- `RecordResultRequestValidationTest`: parameterized — 5..10 produce zero violations; {4, 11, 0, -1, 100} produce a violation; null produces a `@NotNull` violation.
- Existing `DefenseResultServiceNotificationTest` untouched and still valid (its happy-path grade is 9, its failure case is a cancelled defense).

**Build/test status (2026-08-17).**
- Backend: **NOT compiled or run** — no Java/Maven on this machine (`java`/`mvn` not on PATH, `JAVA_HOME` empty, only `mvnw`/`mvnw.cmd` present). The one production edit and the two new test classes were written and verified by inspection against the actual DTO/service/entity/repository signatures and the existing test style; **I make no claim that they compile or pass.**
- Frontend: **no changes made**, so nothing to build for this item; the slider already enforces 5–10.

**Limitations / notes.**
- **Item #8 NOT implemented** (explicit defense-condition verification). Out of scope for this task; `CURRENT NEXT STEP` set to Item #8 above but not started.
- BUG-13 (§8) still stands and is orthogonal to Item #7: `recordResult` still authorizes `STUDENT_SERVICE` in addition to `COMMITTEE`. That is a role-authorization question, not the grade-range rule, so it was left alone.
- Pre-existing bugs unrelated to Item #7 remain open (BUG-2 register role escalation, BUG-6 SMTP placeholders, BUG-19 `/notifications/unsent`, etc.).

---

**Roadmap Item #6 — Student initiates defense request: COMPLETE (2026-08-16).**

Verified against the actual source. The workflow was changed from **mentor schedules directly** to **student requests → Student Service schedules/validates**. Item #8 (explicit STUDENT_SERVICE defense-condition verification) is deliberately **NOT** implemented here (see Limitations).

**New status.** `ThesisStatus.PENDING_DEFENSE_SCHEDULING` — inserted between `PENDING_DEFENSE_CHECK` and `DEFENSE_SCHEDULED`. Means "student has requested a defense; waiting for STUDENT_SERVICE to schedule (room + date/time)". `DEFENSE_SCHEDULED` is untouched and still reached only when a real `Defense` row is created.

**New notification type.** `NotificationType.DEFENSE_REQUESTED` (student request → STUDENT_SERVICE). Emitted via `notifyRole(STUDENT_SERVICE, …)` — same transaction→row→async-email architecture as Item #4; no email sent from a domain service, no entity crosses the `@Async` boundary. The existing `DEFENSE_SCHEDULED` notification (student + every committee member incl. mentor, with room/time) is unchanged and still fires from `scheduleDefense`.

**Status workflow (exact).**
```
COMMITTEE_ACCEPTED → PENDING_DEFENSE_CHECK   (existing; via accept-review or the auto-advance job — UNCHANGED)
  [STUDENT]          POST /api/theses/{id}/defenses/request
PENDING_DEFENSE_CHECK → PENDING_DEFENSE_SCHEDULING   (no Defense row yet; notify STUDENT_SERVICE)
  [STUDENT_SERVICE]  POST /api/theses/{id}/defenses  { room, scheduledAt }
PENDING_DEFENSE_SCHEDULING → DEFENSE_SCHEDULED       (Defense row created; notify student + committee)
  … existing reminder → grade (recordResult) → ARCHIVED, all UNCHANGED
```

**New/changed endpoints.**
- **NEW** `POST /api/theses/{thesisId}/defenses/request` → `DefenseController.request` → `DefenseService.requestDefense`. Returns `ApiResponse<ThesisResponse>` (the updated thesis, now `PENDING_DEFENSE_SCHEDULING`). Requires STUDENT + ownership + status `PENDING_DEFENSE_CHECK`.
- **CHANGED** `POST /api/theses/{thesisId}/defenses` (schedule). Same signature/DTO (`ScheduleDefenseRequest { @NotBlank room, @NotNull @Future scheduledAt }`), but now requires **STUDENT_SERVICE** and status `PENDING_DEFENSE_SCHEDULING` (first) or `DEFENSE_SCHEDULED` (reschedule). The old MENTOR + `PENDING_DEFENSE_CHECK` path is removed.
- `PATCH /api/theses/{id}/defenses/cancel` — UNCHANGED (student-owner or assigned mentor).

**Authorization (backend-enforced in `DefenseServiceImpl`, not just UI).**
- `requestDefense`: `requireRole(STUDENT)` → 403 for anyone else (incl. MENTOR); ownership check → 403; status must be `PENDING_DEFENSE_CHECK` → 400 otherwise.
- `scheduleDefense`: `requireRole(STUDENT_SERVICE)` → 403 for MENTOR/STUDENT/etc.; status must be `PENDING_DEFENSE_SCHEDULING` or `DEFENSE_SCHEDULED` → 400 otherwise (so STUDENT_SERVICE cannot schedule an arbitrary thesis with no pending request, and cannot create the first defense from `PENDING_DEFENSE_CHECK`). Existing "active defense already exists → cancel first" guard retained.
- **Reschedule cannot be abused:** the reschedule path is the same `scheduleDefense` method, so it is STUDENT_SERVICE-only too. A MENTOR is blocked on the role check before any Defense is created — the old bypass (mentor creating the first defense) is closed. Cancellation remains a legitimate MENTOR action.

**Defense-request data model.** No new entity, no new columns. A request is represented purely by the thesis status (`PENDING_DEFENSE_SCHEDULING`) plus the `ThesisStatusHistory` row for that transition (`changedBy` = the requesting student, `changedAt` = request time). The `Defense` entity requires non-null `room`/`scheduledAt`, so it is created **only** at scheduling time — reinforcing "a request exists ≠ a defense is scheduled". The frontend Student Service list shows the request date from `thesis.updatedAt` (which, for a thesis sitting in `PENDING_DEFENSE_SCHEDULING`, is exactly the request time — nothing else updates the thesis in that state).

**Frontend changes.**
- `types/api.ts` — added `'PENDING_DEFENSE_SCHEDULING'` to the `ThesisStatus` union.
- `components/ui/StatusBadge.tsx` — added the badge ("Awaiting Scheduling", amber).
- `api/defenseApi.ts` — added `request(thesisId)` (POST …/defenses/request); `schedule(...)` comment updated (STUDENT_SERVICE).
- `features/defense/DefenseSection.tsx` — STUDENT sees **Request Defense** in `PENDING_DEFENSE_CHECK`; after requesting, a "pending — Student Service must schedule" notice shows and the button is gone; STUDENT_SERVICE sees **Schedule Defense** (reuses `ScheduleDefenseModal`) in `PENDING_DEFENSE_SCHEDULING`; **the mentor's Schedule button was removed** (role no longer computes `canSchedule`). Grading (`canGrade` = COMMITTEE or STUDENT_SERVICE) unchanged.
- `pages/DefensesPage.tsx` — STUDENT_SERVICE gets a **Schedule Defense** action directly on `PENDING_DEFENSE_SCHEDULING` cards (opens the shared `ScheduleDefenseModal`); cards show student, title, status, request date (`updatedAt`), mentor, and an "awaiting scheduling" badge.
- `pages/ThesisDetailPage.tsx` — added `PENDING_DEFENSE_SCHEDULING` to the versions/committee/defense section-visibility conditions.
- `pages/NotificationsPage.tsx` — added the `DEFENSE_REQUESTED` friendly label.

**Backend files changed/added.** `model/enums/ThesisStatus.java` (+PENDING_DEFENSE_SCHEDULING), `model/enums/NotificationType.java` (+DEFENSE_REQUESTED), `service/DefenseService.java` (+requestDefense), `service/impl/DefenseServiceImpl.java` (requestDefense + scheduleDefense re-authorized to STUDENT_SERVICE + stale comment fix), `controller/DefenseController.java` (+POST /request), `service/impl/ThesisServiceImpl.java` (added PENDING_DEFENSE_SCHEDULING to the committee + defense list status sets).

**Tests (new + updated, `src/test/java/com/praksa/service/`, pure Mockito unit tests).**
- **NEW** `DefenseRequestSchedulingTest` — covers all required rules: (1) student requests own eligible thesis → PENDING_DEFENSE_SCHEDULING + notify STUDENT_SERVICE + no Defense row; (2) student cannot request another student's thesis (403); (3) student cannot request from an invalid status (400) + a MENTOR cannot call request at all (403); (4) MENTOR cannot schedule (403, no Defense); (5/8/9/10) STUDENT_SERVICE schedules a pending request → Defense created with the exact room/time, status → DEFENSE_SCHEDULED, DEFENSE_SCHEDULED notifications to student + each committee member; (6) STUDENT_SERVICE cannot schedule a thesis still in PENDING_DEFENSE_CHECK (no request → 400); (7) STUDENT_SERVICE cannot schedule an unrelated thesis (MENTOR_APPROVED → 400); (11) cancellation/reschedule authorization — student owner & mentor can cancel, an unrelated COMMITTEE user cannot (403), reschedule requires STUDENT_SERVICE (MENTOR blocked before any Defense is created).
- **UPDATED** `DefenseServiceNotificationTest` — the two scheduling tests now drive `scheduleDefense` as **STUDENT_SERVICE** from `PENDING_DEFENSE_SCHEDULING`/`DEFENSE_SCHEDULED` (the Item #4 notification assertions are unchanged; only the caller role + source status were corrected to match the new authorization). The cancel test still uses a MENTOR caller (still valid).

**Build/test status (2026-08-16).**
- Frontend: `tsc -p tsconfig.app.json` clean for my files (only the PRE-EXISTING `tsconfig.app.json` `baseUrl` TS5101 deprecation remains). `vite build` **passes** (EXIT 0, 1848 modules, changes bundled).
- Backend: **NOT compiled or run** — no Java/Maven on this machine (`java`/`mvn` not on PATH, `JAVA_HOME` empty). The Java changes and the new/updated tests were written and verified by inspection against the actual entity/DTO/repository/service signatures; **I make no claim that they compile or pass.** The new test is a pure Mockito unit test consistent with the existing Item #4/#5 test style.
- Items #1–#5 re-verified intact by inspection: no `ADMIN` role; two-stage archive/service validation, mentor request-changes/revise loop, the 7 Item-#4 notifications, and the Item-#5 credit gate/deadline all untouched. `decideEligibility`/`PENDING_ELIGIBILITY_CHECK` and the committee accept-review/auto-advance paths (which still land on `PENDING_DEFENSE_CHECK`) are unchanged.

**Limitations / notes.**
- **Item #8 NOT implemented.** There is still no explicit STUDENT_SERVICE "verify defense conditions" step. `PENDING_DEFENSE_CHECK` remains the state the student requests from; the intended future flow is `PENDING_DEFENSE_CHECK → (Item #8 explicit check) → student eligible → PENDING_DEFENSE_SCHEDULING`. The committee accept-review and auto-advance jobs still fast-forward into `PENDING_DEFENSE_CHECK` (old BUG-7) — left alone per the task.
- Request metadata is intentionally kept in status history (no `requestedAt`/`requestedBy` columns) to keep the schema simple; the UI uses `updatedAt` for the request date, which is accurate for any thesis currently in `PENDING_DEFENSE_SCHEDULING`.
- Pre-existing bugs unrelated to Item #6 remain open (BUG-2 register role escalation, BUG-6 SMTP placeholders, BUG-13 recordResult allows STUDENT_SERVICE, BUG-19 `/notifications/unsent`, etc.).

---

**Roadmap Item #5 — 200-credit gate + 1-month submission deadline: COMPLETE (2026-08-16).**

Verified against the actual source. `Thesis.submissionDeadline` (previously dead code — old BUG-11) is now populated and enforced; `User.credits` (previously missing — old BUG-10) is now a real field with a role-gated update path. The existing eligibility workflow (`decideEligibility`, `PENDING_ELIGIBILITY_CHECK`) is UNCHANGED — the 200-credit check is a **creation-time prerequisite**, not a replacement for the STUDENT_SERVICE eligibility decision.

**Credits field.**
- `User.credits` — `Integer`, `@Column(name = "credits")`, **nullable** (Hibernate `ddl-auto=update` adds a nullable column; existing rows and non-student roles get `null`). No new academic system / external integration — a single scalar column, per the task.
- Null is treated as **NOT eligible** everywhere (never as "0-and-fine" and never as "skip the check").

**Who can modify credits (authorization).**
- New endpoints on `UserController`:
  - `GET /api/users/me` → `UserDetailResponse` (id, email, fullName, role, indexNumber, credits) for the authenticated caller.
  - `PATCH /api/users/{id}/credits` → body `UpdateCreditsRequest { @NotNull @Min(0) Integer credits }`.
- Logic lives in the new `UserService` / `UserServiceImpl` (follows the existing controller→service→repository pattern; no business logic in the controller).
- **Server-side role gate**: `updateCredits` calls `SecurityUtils.getCurrentUser()` and throws `UnauthorizedException` (→ 403) unless the caller is `STUDENT_SERVICE`. Target must be a `STUDENT` else `BadRequestException` (→ 400). **A STUDENT can never self-award credits** — there is no self-service credit endpoint, `RegisterRequest`/`CreateThesisRequest` carry no credits field, and the only `setCredits(request…)` call site is this role-gated method (the other `setCredits` is `DataInitializer` at startup). Frontend restrictions are not relied on for security.

**200-credit gate (`ThesisServiceImpl.createThesis`).**
- Constant `REQUIRED_CREDITS_FOR_THESIS = 200`. After the existing role + "one active thesis" checks: `if (credits == null || credits < 200) throw new BadRequestException("At least 200 credits are required to submit a thesis application.")`.
- `199` → rejected; `200` (exactly) → allowed; `>200` → allowed; `null` → rejected. **No** Thesis row, status-history row, notification, or workflow is created on rejection (the guard runs before any `save`).

**Deadline calculation (`createThesis`).**
- `createdAt` and `submissionDeadline` are anchored to the same `OffsetDateTime now`; the entity is built with `.createdAt(now).submissionDeadline(now.plusMonths(1))`. `@PrePersist` keeps an explicitly-set `createdAt`. Uses `java.time` `plusMonths(1)` (real calendar month, 28–31 days) — **not** a fixed `+30 days`.
- No semester/enrollment concept exists in the schema, so the task's fallback (creation time + 1 month) is used, as specified.

**Deadline enforcement (`ThesisServiceImpl.submitApplication`).**
- After the status guard and **before** PDF generation / any transition: `if (deadline != null && OffsetDateTime.now().isAfter(deadline)) throw new BadRequestException("The submission deadline has passed; …")`.
- Consequence: expired submission generates **no** application PDF, performs **no** status transition, and sends **no** ARCHIVE notification.
- `submitMentorRequest` is **intentionally NOT** deadline-blocked — the deadline governs the *formal application* (`submitApplication`), and broadening it to the mentor-request step is out of scope per the task ("only add if consistent … do not unnecessarily broaden").

**Legacy / null-deadline behavior.**
- Thesis rows created before this change have `submissionDeadline = null`. `submitApplication` treats a null deadline as "no deadline" → **not blocked, no crash**. New theses always receive a deadline. **No destructive migration** — `ddl-auto=update` only adds the nullable `credits` column; the `submission_deadline` column already existed. Policy documented here rather than back-filled.

**Scheduled handling (optional, `ScheduledTasksService.reportExpiredPendingApplications`).**
- New `@Scheduled(fixedDelay = 30min)` **read-only** job. It only **logs** theses whose deadline passed while still in a pre-application status (`PRE_APPLICATION_STATUSES` set; backed by `ThesisRepository.findExpiredPendingApplications`). It does **NOT** delete theses and does **NOT** change status — no "expired" terminal status exists in `ThesisStatus` and inventing one is out of scope. Authoritative enforcement stays at submission time; this job is reporting only.

**Frontend changes.**
- `types/api.ts` — added `UserDetail` (incl. `credits`) and `UpdateCreditsRequest`.
- `api/userApi.ts` — added `getMe()` and `updateCredits(id, credits)`.
- `pages/CreateThesisPage.tsx` — fetches `GET /users/me`, shows the student's credit balance, and shows a clear amber warning + **disables** the Create button when `< 200` (usability only; backend still enforces).
- `pages/ThesisDetailPage.tsx` — shows the **submission deadline** in the header, plus a red **"Deadline expired"** badge and "(expired)" marker when `now > submissionDeadline` (`submissionDeadline` was already on `ThesisResponse`/the TS `Thesis` type).
- No dedicated STUDENT_SERVICE credit-editor UI was added (there is no existing user-management page); credits are set via `PATCH /api/users/{id}/credits` (Swagger/API). The seeded demo student (`student@test.com`) starts at **240 credits** so the create-thesis path works out of the box; lower it to demo the gate.

**Files changed / added (backend):** `model/User.java` (+credits), `service/impl/ThesisServiceImpl.java` (gate + deadline calc + deadline enforcement), `controller/UserController.java` (+2 endpoints), `service/UserService.java` (new), `service/impl/UserServiceImpl.java` (new), `dto/user/UserDetailResponse.java` (new), `dto/user/UpdateCreditsRequest.java` (new), `repository/ThesisRepository.java` (+expired query), `service/ScheduledTasksService.java` (+reporting job), `config/DataInitializer.java` (seed credits). **(frontend):** `types/api.ts`, `api/userApi.ts`, `pages/CreateThesisPage.tsx`, `pages/ThesisDetailPage.tsx`.

**Tests (new, `src/test/java/com/praksa/service/`, pure Mockito unit tests):**
- `ThesisCreditsDeadlineTest` — 199 rejected; 200 allowed; >200 allowed; null rejected; deadline == createdAt+1 month; deadline is a calendar month (28–31 days, not +30); `submitApplication` succeeds before deadline (PDF generated); rejected after deadline (no PDF, no transition, no notification); legacy null-deadline still submits.
- `UserCreditsTest` — STUDENT cannot self-award (403, no save); COMMITTEE cannot modify (403); STUDENT_SERVICE can set a student's credits; non-student target rejected (400).

**Build/test status (2026-08-16):**
- Frontend: `vite build` **passes** (EXIT 0, 1848 modules, my changes bundled). `tsc -p tsconfig.app.json` clean for my four files; the only errors are the PRE-EXISTING `tsconfig.app.json` `baseUrl` TS5101 deprecation and the PRE-EXISTING unused-`FileText` TS6133 in `features/versions/VersionUploader.tsx` — both documented under Items #2/#3, unrelated to Item #5.
- Backend: **NOT compiled or run** — no Java/Maven on this machine (`java`/`mvn` not on PATH, `JAVA_HOME` empty). The Java changes and the two new test classes were written and verified by inspection against the actual entity/DTO/repository/service signatures; **I make no claim that they compile or pass.** The tests are written to work as pure Mockito unit tests (they don't rely on `@PrePersist`, which is why `createThesis` sets `createdAt` explicitly).
- Items #1–#4 re-verified intact by inspection: no `ADMIN` role; two-stage archive/service validation untouched; mentor request-changes/revise loop untouched; the 7 Item-#4 notifications untouched. `decideEligibility` / `PENDING_ELIGIBILITY_CHECK` unchanged.

**Remaining limitations / notes:**
- Newly **registered** students (via `POST /api/auth/register`) get `credits = null` and cannot create a thesis until STUDENT_SERVICE records their credits — this is the intended secure default, but means the demo relies on the seeded student (or an API credit update).
- No STUDENT_SERVICE credit-editing UI (API/Swagger only) — deliberate, to avoid inventing a user-management page.
- Legacy null-deadline theses are never blocked at submission (documented policy, no back-fill).
- Pre-existing bugs unrelated to Item #5 remain open (BUG-2 register role escalation, BUG-6 SMTP, BUG-19 `/notifications/unsent`, etc.).
- BUG-10 and BUG-11 in §8 are now effectively resolved by this item (kept in the list for history).

---

**Roadmap Item #4 — Missing notifications across Committee / Defense / DefenseResult / ThesisVersion services: COMPLETE (2026-08-16).**

Verified against the actual source. The 7 defined-but-never-sent `NotificationType` values are now emitted. The notification infrastructure (`NotificationService` / `NotificationServiceImpl` / async `EmailService`) was already correct and was reused unchanged — domain services only inject `NotificationService` and call `notify(...)`; no domain service sends email directly, and no JPA entity crosses the `@Async` boundary (primitives are extracted inside `NotificationServiceImpl.notify` before `emailService.sendAsync`).

**Services changed (4 backend impls only):**
- `CommitteeServiceImpl` — injected `NotificationService`.
  - `approveCommittee` (the point where the committee is officially formed → `COMMITTEE_REVIEW`): `COMMITTEE_FORMED` to **every seated professor** (iterating the freshly-built `members` list — the mentor holds a `MENTOR_MEMBER` seat so is covered exactly once, no duplicate) + `COMMITTEE_FORMED` to the **student** via the 4-arg overload with a student-appropriate **custom message**.
  - `acceptCommitteeReview`: `COMMITTEE_REVIEW_ACCEPTED` to the **student** + **every committee member** (`findByThesis`, mentor included once).
- `DefenseServiceImpl` — injected `NotificationService` + `CommitteeMemberRepository`.
  - `scheduleDefense`: `DEFENSE_SCHEDULED` to the **student** + **every committee member (incl. mentor, once)**, **custom message** carrying room + scheduledAt. Also fires on reschedule (new time → participants re-notified).
  - `cancelDefense`: `DEFENSE_CANCELLED` to the **student** + **every committee member (incl. mentor, once)**, **custom message** stating who cancelled and the old room/time.
- `DefenseResultServiceImpl` — injected `NotificationService`.
  - `recordResult` (after grade saved + archive metadata set + transition to `ARCHIVED`): `THESIS_GRADED` to the **student** with the grade in a **custom message**; `THESIS_ARCHIVED` to the **student** with the registration number in a **custom message**. Grade validation unchanged (still 5–10, enforced in `RecordResultRequest` `@Min(5)`/`@Max(10)`). Archive metadata generation (`DT-YYYY-NNNN`, `archiveDate`, `archivedBy`) unchanged.
- `ThesisVersionServiceImpl` — injected `NotificationService`.
  - `markAsFinal` (after version flag flip + transition to `FINAL_SUBMITTED`): `FINAL_VERSION_SUBMITTED` to the **assigned mentor only**. Deliberately NOT to committee members: `markAsFinal` runs while the thesis is `IN_PROGRESS`, long before any committee exists. Version authorization rules (Item BUG-4) untouched.

**Event → recipient → type → custom message:**

| Event (method) | Recipients | NotificationType | Custom msg? |
|---|---|---|---|
| Committee approved/formed (`approveCommittee`) | each seated professor (incl. mentor) | `COMMITTEE_FORMED` | no |
| Committee approved/formed (`approveCommittee`) | student | `COMMITTEE_FORMED` | yes |
| Committee review accepted (`acceptCommitteeReview`) | student + each committee member (incl. mentor) | `COMMITTEE_REVIEW_ACCEPTED` | no |
| Defense scheduled/rescheduled (`scheduleDefense`) | student + each committee member (incl. mentor) | `DEFENSE_SCHEDULED` | yes (room + time) |
| Defense cancelled (`cancelDefense`) | student + each committee member (incl. mentor) | `DEFENSE_CANCELLED` | yes (who + old room/time) |
| Grade recorded (`recordResult`) | student | `THESIS_GRADED` | yes (grade) |
| Thesis archived (`recordResult`) | student | `THESIS_ARCHIVED` | yes (registration number) |
| Final version submitted (`markAsFinal`) | assigned mentor | `FINAL_VERSION_SUBMITTED` | no |

**Scheduled committee auto-accept:** `ScheduledTasksService.autoAdvanceStaleCommitteeReviews` already notified student + mentor + all committee members, using the distinct `COMMITTEE_REVIEW_AUTO_ADVANCED` type (audience needs to know acceptance was automatic after 5 business days). Left as-is intentionally — this is the "different notification to a different audience for the same state change" case, NOT a duplicate of the manual `COMMITTEE_REVIEW_ACCEPTED`.

**Duplicate-notification checks:** grep of every `notify(` call before the change confirmed none of the 7 types were emitted anywhere — no pre-existing duplicates. Each event now produces exactly one notification per recipient: the mentor is reached through the committee iteration (never separately) so it is never double-notified; the student is always a separate `STUDENT`-role user and never overlaps committee professors.

**Transaction/order:** every `notify(...)` runs only AFTER the business op has succeeded within the same `@Transactional` method (records/transitions saved first). `notify` persists the `Notification` row synchronously in that transaction, so if the op rolls back the notification rolls back too — no notifications for failed operations.

**Frontend:** NO changes needed. `NotificationsPage.tsx` already maps all 7 types to friendly labels and has a `?? notification.type` fallback. Custom message bodies enrich the email only (consistent with Items #2/#3) — the in-app list shows the type label + thesis title + timestamp, as before.

**Tests (new, `src/test/java/com/praksa/service/`, pure Mockito unit tests):**
- `CommitteeServiceNotificationTest` — approve→COMMITTEE_FORMED (all professors + student custom), accept→COMMITTEE_REVIEW_ACCEPTED, and no-notification-on-failure (wrong member count).
- `DefenseServiceNotificationTest` — schedule→DEFENSE_SCHEDULED, cancel→DEFENSE_CANCELLED, and no-notification-on-failure (active defense exists).
- `DefenseResultServiceNotificationTest` — record→THESIS_GRADED + THESIS_ARCHIVED, and no-notification-on-failure (cancelled defense).
- `ThesisVersionServiceNotificationTest` — markAsFinal→FINAL_VERSION_SUBMITTED (mentor only), and no-notification-on-failure (wrong status).

**Build/test status (2026-08-16):** NOT executed — no Java/Maven runtime on this machine (`java`/`mvn` not found, `JAVA_HOME` empty). Backend changes and the new tests were written and verified by inspection against the actual entity/DTO/repository signatures; **I did not compile or run them and make no claim that they pass.** Items #1–#3 re-verified intact (untouched by this pass; the notification infra and version-authorization rules were reused, not modified).

**Remaining notification-related notes:** BUG-6 unchanged — SMTP creds are placeholders, so every `EmailService.sendAsync` still fails and `Notification` rows persist with `isSent=false`; the in-app notification list works regardless (it reads the persisted rows). BUG-19 (unsecured `/api/notifications/unsent`) and BUG-18 (`Notification.type` is a raw String) are out of scope for Item #4 and remain open.

---

**Roadmap Item #3 — Mentor can request changes to the proposed thesis/topic: COMPLETE (2026-08-16).**

Verified against the actual source (not old docs). The backend was already fully implemented and correct end-to-end; this pass confirmed it and closed the two remaining gaps (one backend notification-body gap, one frontend `window.prompt` gap).

**Mentor decision flow (backend — `ThesisServiceImpl.decideMentorRequest`, `PATCH /api/theses/{id}/mentor-decision`):**
Body is `MentorDecisionRequest { MentorDecision decision (@NotNull), String mentorComment (@Size max 2000) }`. Guards: caller must be `MENTOR` **and** the assigned mentor of THIS thesis (else 403); status must be `PENDING_MENTOR_APPROVAL` (else 400). Branches:
- `ACCEPT`          → `APPLICATION_SUBMITTED`; notify student `MENTOR_ACCEPTED_TOPIC`.
- `REJECT`          → `MENTOR_REJECTED_TOPIC`; mentor is cleared; notify student `MENTOR_REJECTED_TOPIC`. (comment optional)
- `REQUEST_CHANGES` → `MENTOR_REQUESTED_CHANGES`; `revisionCount++`; mentor stays assigned; **comment REQUIRED** (blank → 400); notify student `MENTOR_REQUESTED_CHANGES`.

**`MENTOR_REQUESTED_CHANGES` status** exists exactly once in `ThesisStatus` (between `PENDING_MENTOR_APPROVAL` and `MENTOR_REJECTED_TOPIC`). Verified as the only mentor-revision status; no duplicates anywhere in the project.

**Revise-proposal endpoint (`ThesisServiceImpl.reviseProposal`, `PATCH /api/theses/{id}/revise-proposal`):**
Body is `ReviseProposalRequest { title (@NotBlank 5–255), studentComment (@Size max 2000) }`. Guards: caller must be `STUDENT` **and** the thesis owner (else 403); status must be `MENTOR_REQUESTED_CHANGES` (else 400). Reuses the SAME thesis row (no duplicate entity) — updates `title`/`studentComment`, mentor unchanged, then `MENTOR_REQUESTED_CHANGES → PENDING_MENTOR_APPROVAL`. Does NOT auto-accept and does NOT touch `revisionCount`. Notifies the mentor `STUDENT_RESUBMITTED_PROPOSAL`. Loop can repeat any number of times.

**`revisionCount` behavior:** `int` on `Thesis`, `@Builder.Default = 0`, `nullable=false`. Incremented by exactly 1 only in the `REQUEST_CHANGES` branch (`setRevisionCount` appears exactly once in the whole backend). Never reset, never decremented; student resubmission leaves it unchanged. Exposed on `ThesisResponse.revisionCount`. Example: RC 1st→1, resubmit→1, 2nd→2, resubmit→2.

**Mentor feedback:** reuses the existing `Thesis.mentorComment` field (no new field). Preserved (not cleared) when the student resubmits, so the latest feedback stays visible as history. Exposed on `ThesisResponse.mentorComment`.

**Notifications (reused existing types — none added):** `MENTOR_REQUESTED_CHANGES` → student, `STUDENT_RESUBMITTED_PROPOSAL` → mentor. Both already existed in `NotificationType`. **CHANGED THIS PASS (backend):** the `MENTOR_REQUESTED_CHANGES` notification now uses the 4-arg `notify(..., customMessage)` overload to append the mentor's comment to the body ("Mentor feedback: …") — a primitive String, so no JPA entity crosses the `@Async` boundary (same pattern as Item #2's rejection reasons).

**Authorization (backend-enforced, not just UI):** MENTOR: `REQUEST_CHANGES`/`ACCEPT`/`REJECT` only for their assigned thesis and only in `PENDING_MENTOR_APPROVAL`. STUDENT: revise only own thesis, only in `MENTOR_REQUESTED_CHANGES`. STUDENT_SERVICE / ARCHIVE / COMMITTEE: `requireRole(MENTOR/STUDENT)` blocks them from both actions (403). Empty mentor comment on `REQUEST_CHANGES` → 400.

**Status + history:** both `PENDING_MENTOR_APPROVAL → MENTOR_REQUESTED_CHANGES` (changedBy = mentor) and `MENTOR_REQUESTED_CHANGES → PENDING_MENTOR_APPROVAL` (changedBy = student) go through the single `transitionStatus()` helper → each writes a `ThesisStatusHistory` row.

**Frontend changes this pass:**
- **NEW** `src/features/mentor-decision/MentorDecisionModal.tsx` — proper Modal (no `window.prompt`) with a comment textarea, handling `REQUEST_CHANGES` (comment required + validation) and `REJECT` (comment optional). Cancel + typed action button.
- **`ThesisDetailPage.tsx`** — mentor tri-decision row (`Accept` / `Request Changes` / `Reject`, shown only for the assigned MENTOR in `PENDING_MENTOR_APPROVAL`) now opens `MentorDecisionModal` instead of `window.prompt()`. Student `MENTOR_REQUESTED_CHANGES` section now shows the mentor's feedback inline + a "Revision N" indicator alongside the `Revise & Resubmit` button.
- Already present and reused (not duplicated): `ReviseProposalModal.tsx` (shows current title/description + mentor feedback, validates, PATCHes revise-proposal, refreshes on success), the `🔄 N revisions` header indicator on `revisionCount > 0`, `StatusBadge` "Revision Requested", `types/api.ts` (`MentorDecision`, `MENTOR_REQUESTED_CHANGES`, `revisionCount`), and `thesisApi.decideMentorRequest` / `thesisApi.reviseProposal`.

**Build/test status (2026-08-16):**
- Frontend: `vite build` passes (1848 modules, new modal bundled). `tsc -p tsconfig.app.json` clean except the PRE-EXISTING `tsconfig.app.json` TS5101 `baseUrl` deprecation (unrelated to Item #3).
- Backend: NOT built/tested — no Java/Maven on this machine (`JAVA_HOME` empty, no JDK). The single backend edit (4-arg `notify` in the `REQUEST_CHANGES` branch) uses an overload already called twice in the same file; verified by inspection only.
- Items #1 (no `ADMIN` role anywhere) and #2 (two-stage archive/service validation) re-verified intact — untouched by this pass.

**Next up — Roadmap Item #4 — Missing notifications across Committee, Defense, DefenseResult, and ThesisVersion services** (the 7 defined-but-never-sent `NotificationType` values: `COMMITTEE_FORMED`, `COMMITTEE_REVIEW_ACCEPTED`, `DEFENSE_SCHEDULED`, `DEFENSE_CANCELLED`, `THESIS_GRADED`, `THESIS_ARCHIVED`, `FINAL_VERSION_SUBMITTED` — see §3, §8 BUG-5, §10 P0.3). Do not start until confirmed against the master roadmap.

---

### Superseded note — Roadmap Item #2 (COMPLETE 2026-08-16)

Two-stage administrative validation (ARCHIVE → STUDENT_SERVICE). The backend was already implemented and correct; that pass confirmed it end-to-end and closed two spec gaps:

- **Status enum** (`ThesisStatus`): `PENDING_ARCHIVE_VALIDATION`, `APPLICATION_REJECTED_BY_ARCHIVE`, `PENDING_SERVICE_VALIDATION`, `APPLICATION_REJECTED_BY_SERVICE`, `IN_PROGRESS` all present. No obsolete single-step administrative-validation status; no `ADMIN` anywhere in backend source (Item #1 intact).
- **Endpoints**: `PATCH /api/theses/{id}/archive-validate` (ARCHIVE only) and `PATCH /api/theses/{id}/service-validate` (STUDENT_SERVICE only), both taking `{ approved, comment }` (`ValidationDecisionRequest`). Status guards enforced (`PENDING_ARCHIVE_VALIDATION` / `PENDING_SERVICE_VALIDATION`); wrong status → `BadRequestException` (400); wrong role → `UnauthorizedException` (403).
- **Transitions**: archive approve → `PENDING_SERVICE_VALIDATION`; archive reject (comment required) → `APPLICATION_REJECTED_BY_ARCHIVE`; service approve → `IN_PROGRESS`; service reject (comment required) → `APPLICATION_REJECTED_BY_SERVICE`. Every transition goes through `transitionStatus()` → status-history row.
- **Resubmission**: `submitApplication` from either rejection status always returns to `PENDING_ARCHIVE_VALIDATION` (never skips Archive), and regenerates the application PDF.
- **Comments**: reuses existing `archiveComment` / `serviceComment` fields, exposed via `ThesisResponse`.
- **CHANGED THIS PASS (backend)**: rejection notifications now include the rejection reason in the message body (via the existing 4-arg `NotificationService.notify(..., customMessage)` overload — primitive String, no entity crosses the `@Async` boundary). Previously the reason was stored but not surfaced in the notification.
- **CHANGED THIS PASS (frontend)**: replaced the `window.prompt()` rejection-comment flow with a proper modal — new `src/features/validation/RejectValidationModal.tsx`, wired into `ThesisDetailPage.tsx`. Approve runs immediately; Reject opens the modal (comment required). `thesisApi.archiveValidate/serviceValidate`, `StatusBadge`, and `types/api.ts` already covered Item #2. Role+status gating verified: ARCHIVE sees Archive controls only in `PENDING_ARCHIVE_VALIDATION`; STUDENT_SERVICE sees Service controls only in `PENDING_SERVICE_VALIDATION`; STUDENT sees rejection reason + Resubmit on both rejection statuses.

**Build/test status (2026-08-16):**
- Frontend: `vite build` passes (my changes bundle cleanly); one-off `tsc` type-check clean for the changed files. Full `npm run build` is blocked by two PRE-EXISTING issues unrelated to Item #2: (a) `tsconfig.app.json` `baseUrl` deprecation → TS5101 under TypeScript ~6.0; (b) unused `FileText` import in `src/features/versions/VersionUploader.tsx` → TS6133.
- Backend: NOT built/tested — no Java/Maven runtime installed on this machine (`JAVA_HOME` empty, no JDK found). Java changes were re-read and verified by inspection only.

---

## P2 — User enumeration / PII leak via `GET /api/users?role=`: FIXED (2026-08-17)

Closes the last security-authz P2: any authenticated user could enumerate the system-wide user list of any role via `GET /api/users?role=` and read every user's `email` plus (since Item #13) a student's `indexNumber` + `credits`. The endpoint has two legitimate consumers that had to keep working — the **mentor picker** and the **STUDENT_SERVICE credit list** — so it was scoped per-role rather than blanket-restricted.

**Real source discovered (verified, not assumed).**
- `UserController.getUsersByRole` talked **directly to `UserRepository.findByRole`** (no service, no authorization — only Spring Security's "must be authenticated" applied) and mapped every row via `UserSummaryResponse.from`, which carried `id, email, fullName, role, indexNumber, credits`.
- Frontend consumers of the endpoint (`userApi.getByRole`): **mentor lookup** — `features/mentor-picker/MentorPickerModal.tsx` (a STUDENT choosing a mentor) and `features/committee/ProposeCommitteeModal.tsx` (a MENTOR proposing a committee) — both requested `role=MENTOR` and displayed `fullName` **and `email`**; **credit list** — `pages/ManageCreditsPage.tsx` (+ `features/credits/EditCreditsModal.tsx`), STUDENT_SERVICE-only, requested `role=STUDENT` and used `id, fullName, email, indexNumber, credits`. `CreateThesisPage` uses `getMe`, **not** this endpoint. No backend caller other than the controller used `UserSummaryResponse`.

**Exact authorization policy implemented (service layer = security boundary, BEFORE any repo query).**
Logic moved from the controller into `UserServiceImpl.getUsersByRole(role)`; the controller now just delegates. A `switch (role)` decides both authorization and response shape:
- `role=STUDENT` → `requireRole(caller, STUDENT_SERVICE)`, then `findByRole(STUDENT)` mapped via `UserSummaryResponse.studentDetail` (full student fields). Anyone else → 403.
- `role=MENTOR` → `requireAnyRole(caller, STUDENT, MENTOR, STUDENT_SERVICE)`, then `findByRole(MENTOR)` mapped via `UserSummaryResponse.mentorPicker` (identity only). COMMITTEE/ARCHIVE → 403.
- **any other requested role** (`STUDENT_SERVICE`, `COMMITTEE`, `ARCHIVE`) → `UnauthorizedException` → **403 for everyone**, so swapping `role=` to a different value cannot be used to enumerate users, even by STUDENT_SERVICE.
- `requireRole`/`requireAnyRole` are private helpers throwing `UnauthorizedException` (→ 403 via `GlobalExceptionHandler`), mirroring the existing idiom in `ThesisServiceImpl`/`NotificationServiceImpl`. There is no ADMIN role in this project. Unauthenticated callers are still rejected upstream by Spring Security (401) before the service runs.

**Role / use-case matrix (after the fix).**

| Requested `role=` | STUDENT | MENTOR | STUDENT_SERVICE | COMMITTEE | ARCHIVE |
|---|---|---|---|---|---|
| `STUDENT` | 403 | 403 | ✅ full student fields | 403 | 403 |
| `MENTOR` | ✅ identity only | ✅ identity only | ✅ identity only | 403 | 403 |
| `STUDENT_SERVICE` / `COMMITTEE` / `ARCHIVE` | 403 | 403 | 403 | 403 | 403 |

**Exact fields exposed per legitimate use case.**
- Mentor picker (`role=MENTOR`): `id`, `fullName`, `role`. **No** `email`, `indexNumber`, or `credits`.
- Credit list (`role=STUDENT`, STUDENT_SERVICE only): `id`, `fullName`, `role`, `email`, `indexNumber`, `credits`.
- `UserSummaryResponse` is now `@JsonInclude(NON_NULL)` with two factories — `mentorPicker(user)` (PII fields left null → omitted from JSON entirely) and `studentDetail(user)`. The old all-fields `from(user)` factory was removed.

**PII no longer exposed.** Mentors' `email` is no longer sent to (or displayed by) the mentor picker; students' `email`/`indexNumber`/`credits` are no longer reachable by STUDENT/MENTOR/COMMITTEE/ARCHIVE at all. Only STUDENT_SERVICE sees student PII, and only via the STUDENT list.

**Backend files changed.**
- `dto/user/UserSummaryResponse.java` — purpose-specific factories (`mentorPicker`, `studentDetail`) + `@JsonInclude(NON_NULL)`; removed `from`.
- `service/UserService.java` — added `getUsersByRole(Role)` to the interface (with policy doc).
- `service/impl/UserServiceImpl.java` — implemented `getUsersByRole` with per-role auth before the repo query; added `requireRole`/`requireAnyRole` helpers.
- `controller/UserController.java` — `GET` delegates to `userService.getUsersByRole`; removed the direct `UserRepository` dependency; Swagger doc updated.
- `src/test/java/com/praksa/service/UserEnumerationAuthorizationTest.java` — **new**, 13 Mockito tests.

**Frontend files changed.**
- `types/api.ts` — replaced `UserSummary` with purpose-specific `MentorSummary` (`id, fullName, role`) and `StudentSummary` (adds `email, indexNumber, credits`).
- `api/userApi.ts` — replaced generic `getByRole(role)` with `getMentors()` and `getStudents()` (intent-specific; also removes the ability to casually request an arbitrary role client-side).
- `features/mentor-picker/MentorPickerModal.tsx`, `features/committee/ProposeCommitteeModal.tsx` — use `getMentors()`/`MentorSummary`; **removed the email line** from each mentor row (email no longer sent).
- `pages/ManageCreditsPage.tsx`, `features/credits/EditCreditsModal.tsx` — use `getStudents()`/`StudentSummary`.

**Tests (real results, JDK 25 / JBR via `./mvnw`).**
- `UserEnumerationAuthorizationTest` → **13/13 pass**: STUDENT_SERVICE lists STUDENTs with full credit fields + repo queried once; mentor picker allowed for STUDENT/MENTOR/STUDENT_SERVICE with identity-only (email/index/credits asserted null); STUDENT/MENTOR/COMMITTEE/ARCHIVE denied 403 with `findByRole` **never** called; STUDENT_SERVICE requesting COMMITTEE/ARCHIVE denied 403 (role-swap enumeration blocked).
- Full suite `./mvnw test` → **166 tests, 1 failure**. The single failure is the documented **pre-existing** `AuthIntegrationTest.login_wrongPassword_returns403` (expects 4xx, gets 500 — DB-backed login path, unrelated to this change; it does not touch `/api/users`). The other 165 pass, including all 13 new tests. Count rose from 153 → 166 (+13).

**Frontend build.** `npm run build` is still blocked by the same two PRE-EXISTING issues documented above (Item #2): `tsconfig.app.json` `baseUrl` TS5101 and the unused `FileText` import in `VersionUploader.tsx` TS6133 — neither introduced here. A one-off `tsc -p tsconfig.app.json --noEmit --ignoreDeprecations 6.0` type-checks **all six changed frontend files cleanly** (the only reported error is the pre-existing `VersionUploader` unused import).

**Limitations.** (1) The full `npm run build` cannot be run green until the two pre-existing TS issues are addressed (out of scope). (2) No live end-to-end HTTP verification (backend can't be served here); the 403/field behavior is covered by the service-layer Mockito tests. (3) `AuthIntegrationTest.login_wrongPassword_returns403` remains a pre-existing, unrelated failure.

**Explicitly left OPEN (unchanged):** duplicate `DEFENSE_REMINDER` to mentor (now CURRENT NEXT STEP), over-broad `downloadApplicationPdf` for any COMMITTEE user, stale `DefenseSection.tsx` reschedule text, BUG-15 remainder (inline DB-pw/JWT-secret dev defaults + split local/external JWT key), the pre-existing `AuthIntegrationTest` login failure, and the pre-existing frontend build blockers.

_(Item #3 followed and is now COMPLETE — see the CURRENT NEXT STEP section above for the authoritative status and the pointer to Item #4.)_

---

## P2 — Duplicate `DEFENSE_REMINDER` to the mentor: FIXED (2026-08-17)

**Verdict.** The 24h-before defense reminder job notified the mentor twice; now it notifies the mentor exactly once. Backend-only, notification-recipient de-duplication. No frontend change; no change to notification type, timing, window, `reminderSentAt` semantics, scheduling/cancellation, committee model, or email behavior.

**Root cause (verified against source, not the prior handoff).** `ScheduledTasksService.sendDefenseReminders` did, per upcoming defense: (1) notify the student, (2) notify `thesis.getMentor()` explicitly, (3) loop `committeeRepository.findByThesis(thesis)` and notify each `professor`. Because a defense can only exist after the committee is formed and reviewed, and `CommitteeServiceImpl.approveCommittee` auto-adds the thesis mentor as a `MENTOR_MEMBER` seat, the mentor is always in that committee loop — so steps (2) and (3) both hit the mentor → a duplicate `DEFENSE_REMINDER`. The sibling flows `DefenseServiceImpl.scheduleDefense` and `cancelDefense` already notify the mentor **only** through the committee loop (with an explicit comment), so the reminder job was the lone inconsistency.

**Before vs after (per upcoming defense).**
- Before: student ×1, mentor ×2 (explicit + committee seat), each other committee member ×1.
- After: student ×1, mentor ×1 (committee seat only), each other committee member ×1. Total = 1 (student) + N (seated professors, mentor included once).

**Exact fix (smallest correct change).** `service/ScheduledTasksService.java` — deleted the explicit mentor block:
```java
// removed:
if (thesis.getMentor() != null) {
    notificationService.notify(thesis.getMentor(), thesis, NotificationType.DEFENSE_REMINDER);
}
```
The student notify and the committee-member loop are unchanged; a comment now documents that the `MENTOR_MEMBER` seat covers the mentor exactly once (mirroring `scheduleDefense`/`cancelDefense`). `reminderSentAt` stamping and `defenseRepository.save` are untouched.

**Recipient logic (final).** Student (separate `STUDENT`-role user, never on the committee) + every seated committee professor once each. The mentor is reached solely via their `MENTOR_MEMBER` committee seat.

**Tests (new — real results, JDK 25 / JBR via `./mvnw`).** `DefenseReminderNotificationTest` (pure Mockito, mirrors `AutoAdvanceCommitteeReviewTest`), **4/4 pass**:
1. Student + mentor (once, via `MENTOR_MEMBER` seat) + each other committee member once; total notifications = 4 for a 3-seat committee (mentor + 2 formal) + student; `reminderSentAt` stamped and defense saved once.
2. Mentor notified only through the committee loop — minimal committee (mentor seat only) yields exactly 2 notifications (student + mentor); a lingering explicit notify would have made it 3.
3. Reminder window unchanged — the job queries `findUpcomingForReminder` with `[now+23h, now+25h]` (captured bounds, exactly a 2h span).
4. Empty window → no-op (nothing saved; `verifyNoInteractions` on committee repo + notification service).

**Build / full suite.** `./mvnw -Dtest=DefenseReminderNotificationTest test` → **4/4, BUILD SUCCESS**. Full `./mvnw test` → **170 tests, 1 failure** = the documented **pre-existing** `AuthIntegrationTest.login_wrongPassword_returns403` (expects 4xx, gets 500 — DB-backed login path, unrelated to this change). The other 169 pass, including the 4 new tests. Count rose 166 → 170 (+4).

**Frontend.** No change required or made (inspection confirmed the frontend renders `DEFENSE_REMINDER` notifications identically regardless of count); no frontend build run.

**Explicitly left OPEN (unchanged):** over-broad `downloadApplicationPdf` for any COMMITTEE user (now CURRENT NEXT STEP), stale `DefenseSection.tsx:120` reschedule text, BUG-15 remainder (inline DB-pw/JWT-secret dev defaults + split local/external JWT key), the pre-existing `AuthIntegrationTest` login failure, and the pre-existing frontend build blockers.
