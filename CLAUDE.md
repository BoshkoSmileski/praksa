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
| `Defense` | A single defense event. `room`, `scheduledAt`, `isCancelled`, `cancelledBy/At`, `reminderSentAt`. |
| `DefenseResult` | One-to-one with Defense. `grade` (Integer 5-10), `notes`, `recordedBy`. |
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
| `/api/theses` | `ThesisController` | `POST` create; `GET /my`; `GET /{id}`; `GET /by-registration-number/{n}`; `GET /{id}/application-pdf`; `GET /{id}/history`; `PATCH /{id}/eligibility`; `PATCH /{id}/mentor-request`; `PATCH /{id}/mentor-decision`; `PATCH /{id}/revise-proposal`; `PATCH /{id}/submit-application`; `PATCH /{id}/archive-validate`; `PATCH /{id}/service-validate`; `PATCH /{id}/approve-final`; `PATCH /{id}/defense-eligibility` (Item #8) |
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

[STUDENT]  POST /api/theses/{id}/defenses/request
           Requires PENDING_DEFENSE_SCHEDULING (post-verification) + thesis owner (else 400/403).
           Does NOT change status and does NOT create a Defense row — signal only.
           Notifies role STUDENT_SERVICE (DEFENSE_REQUESTED) — fan-out.
           ⚠️ Item #8: a student can no longer request from PENDING_DEFENSE_CHECK (must be verified first).

[STUDENT_SERVICE]  POST /api/theses/{id}/defenses  { room, scheduledAt }
           PENDING_DEFENSE_SCHEDULING (first schedule) or DEFENSE_SCHEDULED (reschedule).
           Refuses if an active (non-cancelled) defense exists. Refuses any thesis not
           awaiting scheduling — this closes the old mentor bypass from PENDING_DEFENSE_CHECK.
           First scheduling: PENDING_DEFENSE_SCHEDULING → DEFENSE_SCHEDULED.
           Rescheduling: no status change.
           Notifies student + every committee member (incl. mentor, once) DEFENSE_SCHEDULED
           with room + time in the message body.
           ⚠️ MENTOR can no longer schedule (Item #6) — 403 on the role check.

[STUDENT or MENTOR]  PATCH /api/theses/{id}/defenses/cancel
           Cancels the active defense. Thesis status unchanged.
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
- Can: create thesis, submit mentor request (from TOPIC_SELECTION or MENTOR_REJECTED_TOPIC), revise proposal (from MENTOR_REQUESTED_CHANGES), submit application (fresh or after either rejection), upload versions (IN_PROGRESS or FINAL_SUBMITTED), mark final (IN_PROGRESS), add comments (on own thesis versions), **request a defense (only from PENDING_DEFENSE_SCHEDULING, i.e. after STUDENT_SERVICE has verified eligibility — Item #8; signal-only, no status change)**, cancel own defense, download own application PDF.
- Rules: only one active (non-ARCHIVED) thesis at a time.

**MENTOR**
- Sees: theses assigned to them (`findByMentor`).
- Can: decide mentor request (only for their assigned thesis), approve final thesis, propose committee (from MENTOR_APPROVED), cancel defense on their thesis, add comments on assigned thesis versions.
- **CANNOT schedule a defense** (changed in Item #6 — scheduling is STUDENT_SERVICE-only, enforced backend-side).
- Auto-included on the committee they propose (as MENTOR_MEMBER).
- Enforced business rule: max 10 active theses at once (`countActiveMentorTheses`).

**STUDENT_SERVICE**
- Sees: ALL theses (`findAll` fallthrough in `getMyTheses`).
- Can: decide eligibility, service-validate applications, approve committee, accept committee review, **verify defense eligibility (PENDING_DEFENSE_CHECK → PENDING_DEFENSE_SCHEDULING — Item #8)**, **schedule/reschedule defenses (from PENDING_DEFENSE_SCHEDULING or DEFENSE_SCHEDULED — Item #6)**. **CANNOT record a defense grade** (BUG-13 fixed 2026-08-17 — grading is committee-seat-scoped; STUDENT_SERVICE retains full read/oversight but not the grade write).
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
- `features/defense/ScheduleDefenseModal.tsx`, `RecordGradeModal.tsx`, `DefenseSection.tsx`

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
| `theses` | `Thesis` | pk=`id`; FK `student_id` (nn); FK `mentor_id` (nullable); `title`; `student_comment` text; `mentor_comment` text; **`archive_comment`** text; **`service_comment`** text; `status` (varchar); **`revision_count`** int nn default 0; **`committee_review_started_at`**; **`application_pdf_path`** varchar(1024); `submission_deadline` (unused — see bugs); `created_at`; `updated_at`; **`archive_registration_number`** varchar(50) UNIQUE; **`archive_date`**; FK **`archived_by`**; **`archive_notes`** text |
| `thesis_versions` | `ThesisVersion` | pk=`id`; FK `thesis_id`; `version_number` int; UNIQUE (`thesis_id`, `version_number`); `pdf_url`; `is_final` bool; `uploaded_at` |
| `thesis_comments` | `ThesisComment` | pk=`id`; FK `version_id`; FK `author_id`; `content` text; `created_at` |
| `committee_members` | `CommitteeMember` | pk=`id`; FK `thesis_id`; FK `professor_id`; UNIQUE (`thesis_id`, `professor_id`); `member_role` (varchar enum); FK `proposed_by`; FK `approved_by`; `approved_at`; `notes` text |
| `defenses` | `Defense` | pk=`id`; FK `thesis_id`; `room`; `scheduled_at`; `is_cancelled` bool; FK `cancelled_by`; `cancelled_at`; `created_at`; **`reminder_sent_at`** |
| `defense_results` | `DefenseResult` | pk=`id`; FK `defense_id` UNIQUE (one-to-one); `grade` int; `notes` text; FK `recorded_by`; `recorded_at` |
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
    │   └── defense/        ScheduleDefenseModal, RecordGradeModal, DefenseSection
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

**CURRENT NEXT STEP = FINAL READINESS AUDIT is DONE (2026-08-18) — VERDICT: PROJECT READY FOR PROFESSOR REVIEW. See the dated "Final Readiness Audit" entry directly below.** A full pre-demo audit (auth, authorization/IDOR, thesis/committee/defense workflow, notifications, file access, error handling, config/secrets, frontend/backend contracts, UX) was executed against the running app. It found and fixed exactly **one** genuine defect — a read-side IDOR: `GET /api/theses/by-registration-number/{n}` returned the full `ThesisResponse` (incl. internal student/mentor/archive/service comments) to ANY authenticated user with no read-access check, and registration numbers are sequential/enumerable (`DT-YYYY-NNNN`). It now enforces the same `ThesisReadAccessPolicy.requireReadAccess` as every other thesis read. Full backend suite is **256 tests, 0 failures, 0 errors** (was 254; +2 regression tests). Frontend `npm run build` EXIT 0. Browser smoke test passed (login → dashboard → notifications → theses → logout). **STOP DEVELOPMENT and send the project to the professor for feedback.** Everything below the audit entry is prior history and remains accurate.

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
