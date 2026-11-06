# SmartHire local feature implementation plan

The local and Aiven builds include slices 14–17 and 19–20. See [implementation status](implementation-status.md) for test results and remaining desktop checks.

## Scope and delivery order

| Slice | Deliverable | Dependency and observable result |
| --- | --- | --- |
| 14 | Downloadable CV and job circular Word templates | Uses the existing document reader. Each role saves a blank, editable DOCX and can upload a completed copy through its current workflow. |
| 15 | Application timeline | Uses the existing application transitions and interview events. Participants can see dated status changes and interview activity for an application they own or recruit for. |
| 16 | Job deadlines and discovery filters | Centralizes intake availability. Search, matching, the dashboard, and submission agree about whether a posting is open. |
| 17 | Saved searches and in-app alerts | Reuses slice 16's filters. An approved matching posting creates one notice per eligible recipient. |
| 18 | Interview calendar export (removed) | Participants manage interview dates and times in SmartHire. |
| 19 | Recruitment reports | Uses application facts and slice 15's first-shortlist event. Companies and admins see counts and clearly defined rates. |
| 20 | Password change and local account recovery | Extends the current identity service. An account owner can change a password and recover with a previously saved single-use code. |

### Rules shared by the slices

- Swing sends validated inputs, record IDs, and expected revisions to the application services. Services derive the actor from the session and check active status, role, and ownership. JDBC remains in repositories. Use background workers for database and file operations, with Swing component updates on the event-dispatch thread.
- Retain the existing catalog, posting, and application lock ordering. For each write, identify the rows already locked by the service, recheck the decision against committed data, and commit dependent records and notices in the same transaction.
- Matching and eligibility remain separate. A new application still requires an approved current CV, a readable original, satisfied mandatory requirements, remaining vacancies, and the existing one-application rule. Submitted CV versions and assessments stay immutable.
- Store UTC instants for ordered events and deadlines. Convert at the presentation boundary and label the display zone. Use an injectable clock for deterministic tests. Capture a single instant per relevant decision and use it consistently.
- Add each required schema change as a numbered migration with existing-schema, fresh-schema, and interrupted-upgrade coverage. Use nullable fields or safe defaults for old data. Keep foreign keys, admin export/erasure scope, and the ER diagram current. Account recovery secrets and password hashes are excluded from exports.

## 14. Downloadable DOCX templates

**Feasible with the current code.** `DocumentReader` already handles text-based DOCX via Apache POI. A bundled Word file can be copied directly to a user-chosen path. Saving a template requires neither a database row nor a managed document reference.

| Template | Entry point and contents |
| --- | --- |
| `SmartHire-CV-Template.docx` | **My CV**, beside Upload CV or Replace CV, including the empty state. Plain headings for contact details, summary, education, technical and soft skills, experience with dates, projects with technologies, and certifications. |
| `SmartHire-Job-Circular-Template.docx` | Company job editor, before a posting exists, and the company circular attachment controls for its postings. Headings for title, company, location, summary, responsibilities, required and optional qualifications, salary, vacancies, and closing date. The closing-date section can exist before slice 16 adds a structured field. |

1. Author two editable resources under `src/main/resources/templates`. Use normal paragraphs and standard headings, with short bracketed prompts under each heading. Keep content fictional or blank, avoid macros and pre-filled qualifications, and use layout and section labels that survive extraction. Keep any filled parser examples in test fixtures, separate from the downloaded templates.
2. Add **Download CV template (.docx)** and **Download circular template (.docx)**. A shared save action opens a DOCX chooser, suggests the filename, normalizes the extension, and confirms overwrite explicitly. Copy packaged bytes on a worker. Stage the file in the destination folder before committing the save so a failed write preserves an existing file. Cancel leaves the filesystem untouched.
3. User opens the saved file in a Word-compatible editor, replaces the prompts, then returns to SmartHire to upload it. The company can download a circular template during drafting and attach the edited copy after saving a posting through the existing editor workflow.
4. CV upload continues through `CvService.upload` → `ManagedDocuments.prepare` → editable suggestions → confirmation → a new Pending version → admin review. Circular attachment continues through `JobService.attachCircular`, including ownership, the existing freeze on attachments after applications or closure, and reapproval when required. An edited circular provides suggestions where the existing extractor supports them. Recruiter-confirmed structured fields remain the authority for requirements, capacity, and deadlines.
5. Keep existing English text-based PDF and DOCX uploads, the current size limit, and ordinary validation. A template supplies a consistent starting layout. Users can still upload documents in another layout and must review extracted information before relying on it. Show a short instruction to remove the prompts before uploading.

**Acceptance.** Open both packaged files in a Word-compatible editor and review page layout. Edit and upload each one through its actual Swing chooser. Verify expected extracted fields and suggestions, no placeholder-based match credit in the blank fixtures, CV Pending/approval and circular moderation, cancellation, extension handling, overwrite refusal, and a failed destination write. Compare the saved template bytes to the packaged resource and verify both resources are included in the Windows ZIP's JAR.

## 15. Application timeline

1. Add `application_status_events` with application ID, per-application sequence, previous and new status, actor role, and UTC event time. Reference the application with delete cascade. Keep the current `applications.status` column for existing queries and decisions. A status change and its event either both commit or both roll back.
2. In `ApplicationService` and `JdbcApplications`, append an event inside the existing locked status transition, using the same instant for the status event and `hired_at` on hire. Keep the initial submission timestamp in `applications.submitted_at` as the single source for the Submitted entry. Keep interview events in `interview_events`; merge the two sources on read with a deterministic tie-break.
3. Add a Timeline tab to existing student and recruiter application details. Use the same authorization as application details. Show safe event labels and times for submitted, shortlisted, rejected, withdrawn, and hired, along with interview revisions. Exclude scorecards, private notes, and personal actor details. Show an old application's known submission or hire timestamp and its current status even when intervening dates are unavailable. Avoid duplicate Hired entries when the transition event exists.
4. Include the timeline in admin archives when the related application is in scope. Erasing the application removes its events, including erasure triggered by deleting its exact CV version, job, student, or company.

**Acceptance.** Exercise each legal transition and verify its timestamp, order, and participant visibility. Race competing decisions and force a rollback to verify that failed transitions create no event. Verify old applications, interview cancellation alongside a terminal decision, and export and erasure coverage.

## 16. Job deadlines and discovery filters

1. Add an optional `application_deadline_at` UTC instant and companion zone ID to job details and persistence. Existing jobs have no deadline. The company selects a local date and time and an explicit zone. Reject ambiguous or nonexistent local times. Show the deadline with its zone in posting details and the editor.
2. Treat a deadline as an exclusive cutoff. At `now >= deadline`, the posting stops accepting applications even with vacancies remaining. Derive expiry rather than relying on a scheduled task or updating the publication status. Companies retain access to applicants and allowed interview and hiring actions. An expired posting with unfilled vacancies stays out of student Hiring history.
3. Extend the shared student-availability rule in `JobService` and reuse it under the posting lock in `ApplicationService.apply`. Evaluate at the locked admission check. Update `JdbcJobs` query paths and workspace counts to use the same predicate and a bound cutoff instant. Cover Find jobs, matches and comparison, detail and circular access, bookmarks, the student dashboard, and direct submission. Saved jobs may show **Deadline passed** for a still-public posting with its existing safe title.
4. Validate that a newly set deadline is in the future and prevent approving a posting whose deadline has already passed. Before the first application, edits use the existing revision and reapproval flow. After an application, freeze the deadline with requirements and capacity. Before any application, an expired posting can return to approval with a future deadline. After the freeze, a recruiter can create a new posting and leave prior applications on the original.
5. Introduce shared filter criteria for title, category, location, salary, and a closing date range. Keep the current title/category defaults. A salary query matches a listed range that overlaps the selected range. An explicit closing-date filter excludes jobs with no deadline. Use the selected display zone to compute date boundaries, validate minimum/maximum values, and use bound SQL parameters. Reuse the same filter semantics for saved searches.

**Acceptance.** Test immediately before, exactly at, and after a deadline with a fixed clock. Exercise an application screen opened before expiry, an edit or final hire racing a submission, and old postings with null deadlines. Verify that existing applications remain accessible, hiring history still depends on filled capacity, and filters agree across ordinary and saved searches.

## 17. Saved searches and in-app alerts

1. Add `saved_job_searches` with a student owner, name, validated slice 16 criteria, active flag, and timestamps. Add a **Saved searches** view with run, edit, pause, and delete actions, and a **Save this search** action in Find jobs. Existing bookmarks continue to represent individual postings.
2. On successful approval through `JobService.moderate`, evaluate matching active searches for active students against the newly approved posting and its current availability. Use the approval transaction for the local build. Match search criteria only. The alert does not imply eligibility or CV approval. Search creation, edits, resume, and company reverification do not replay past approvals. A manual saved search always runs against current availability.
3. Add a structured link between a notice, student, and job with a unique `(student_id, job_id)` key. Insert at most one notice even when several saved searches match or a job is approved again. Use the normal inbox read state. Display neutral text in the inbox and resolve the job title and Open action only after the ordinary student-access check.
4. Deleting a saved search stops future matches and retains already delivered alerts. Erasing a job or company removes its linked alerts. Erasing a student removes their searches and alerts. Add searches to that student's admin archive and keep individual search criteria out of company and job exports.

**Acceptance.** Cover multiple matching searches, disabled or paused students, reapproval, approval rollback, matching dates and salary boundaries, and links that become unavailable after expiry, closure, filling, or moderation. Check the scoped export and erasure behavior for both notices and searches.

## 18. Interview calendar export (removed)

Use the [existing interview scheduling workflow](feature-expansion-plan.md#10-interview-scheduling) to propose, accept, decline, reschedule, or cancel an interview.

## 19. Recruitment reports

1. Add a Reports screen under the company and admin workspaces, with a date range and optional posting/category filter. Companies get only their own jobs. Admin reports are aggregate and omit CV text, contact details, and recruiter notes. Keep existing dashboard counts and give the report queries their own read model.
2. Define an application cohort by submission timestamp within the selected date range in the chosen zone. Include retained withdrawn and rejected applications in the denominator. The conversion rate uses the cohort's current hired count divided by submissions. Label the cohort range and that outcomes are current as of the report run. Show count and denominator together.
3. Time to first shortlist uses the first recorded Shortlisted event from slice 15 minus submission time. Exclude rows whose shortlist time is unknown and show how many records contributed. For time to hire, use recorded `hired_at` only. Calculate vacancy fill as all current hires divided by advertised vacancies for postings in the selected scope. Label this metric **Current capacity**, independent of the application date range. Render empty denominators as **No data**.
4. Query the current database with scoped SQL aggregates. Avoid persisting report snapshots. Use the same authorized read model for an optional CSV download with safe quoting and spreadsheet-formula escaping. Erased records drop out of subsequent reports.

**Acceptance.** Check formulas against a fixed fixture with multiple vacancies, withdrawals, hires, and legacy timestamps. Verify boundaries, empty cohorts, erasure, role isolation, and that dashboard counts and report values agree when they use the same population.

## 20. Password change and local recovery

1. Add **Change password** to profile for all roles. Require the current password, a new password, and confirmation. Reuse `Passwords` and a shared registration/password-change validation rule. Verify the current password, then compare the stored credential version under a row lock so concurrent changes cannot overwrite each other.
2. Add `users.credential_version` with an initial value for existing accounts. Store the credential version in in-memory sessions and recheck it when authenticating a session. Commit the new hash and bumped version atomically, then return the UI to sign-in. Apply the same invalidation to recovery. Disabled accounts stay disabled. Keep current seed behavior so reseeding preserves user-changed passwords.
3. Add a local **Recovery codes** action that requires the current password. Generate cryptographically random one-time codes, display them once for safe offline storage, and store only hashes linked to the account. A new set replaces the old set. The sign-in screen accepts email, a previously issued code, and the new password twice. Validate the code and consume the set in the same transaction as the credential change. Use generic errors and bounded attempts, with a dummy verification path for nonexistent accounts. A user who has never saved a recovery code needs a separate verified recovery channel before self-service recovery is possible. Email delivery and identity verification for recovery belong to the later cloud phase. [OWASP password recovery guidance](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html)
4. Keep hashes, codes, and entered passwords out of logs, audits, and data exports. Account erasure cascades to recovery records. On a successful change, an in-app notification may record the time and that a credential changed, without storing any credential value.

**Acceptance.** Verify wrong current password, successful change, old and concurrent session invalidation, code replay, rotation, rollback, disabled users, throttled and unknown-account attempts, and erasure. Confirm local demo sign-in after reseeding with a changed password and that exported archives contain no secrets.

## Local release gate and later cloud work

- Before editing any slice, pin the current local baseline and run the relevant tests. During development, use `smarthire_test` and the disposable schema for upgrade tests. Keep the current cloud ZIP and Aiven database on their existing release. Before running a local feature build, check that it targets local MySQL. If the development build can invoke the cloud launcher, add a guard before schema initialization so the new build cannot apply local-phase migrations to Aiven.
- After each slice, run its focused tests, the applicable Swing flow, and `git diff --check`. For schema or transaction changes, run `scripts/verify.ps1 -Offline -CleanDatabaseSmoke`. Exercise fresh installation, migration from the current local schema with historical data, and restart after partial migration. Review SQL queries, export/erasure, and the full diff before moving on.
- Before calling the local build ready, package the Windows ZIP. Run the demo end to end through the native desktop on local MySQL, including template Save As, edited DOCX uploads, approval, application, interview, deadline expiry, reporting, and password change. Test an existing local installation and a fresh demo seed. Confirm the installed build can still open the exact historical CV bytes for an application.
- After local acceptance, plan cloud migrations and a rollout using an isolated Aiven schema with fictional data. Decide whether to ship only a cloud demo refresh or move to multi-computer use. For multi-computer use, place a backend API between Swing and MySQL and move original files to shared private storage. Retain immutable document IDs, byte hashes, and reference-aware erasure. Validate the cross-computer path from student upload to company download before any cutover.

Project references include the [implementation status](implementation-status.md), [recruitment rules](spec-draft.md), [document lifecycle](document-storage.md), and [current Windows cloud setup](windows-release.md).
