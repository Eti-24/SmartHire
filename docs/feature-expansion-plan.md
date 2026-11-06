# SmartHire feature expansion plan

Status: implemented. The proposed design below records the sequence and rationale. See [the implementation status](implementation-status.md) for verification and remaining acceptance checks.

## 1. Establish the system rules first

| Existing rule | Implication for the new features |
| --- | --- |
| Students, companies, and admins have distinct roles. Company access is limited to its own jobs and applications. A disabled account loses access even through an existing session. | Authorize reads and writes in services. Derive the actor and ownership from the session and stored records. Pass IDs and expected versions from Swing, then reread them under the appropriate transaction before acting. |
| Discovery requires an approved, open posting from an active, verified company. A single hire currently fills a posting. | Use one availability rule across search, matching, comparison, student detail and circular access, dashboard counts, saved-job actions, and submission. With multiple vacancies, capacity becomes another part of that rule. |
| One application per student per job. Applying requires an approved current CV, readable original bytes, and eligibility. Submission freezes requirements and preserves the CV and assessment. | Saving a job, booking an interview, and recording feedback grant no extra application privileges. Interview and feedback records attach to an application ID. New matching guidance uses the current reviewed CV, while company review uses submitted evidence. |
| Application states are Applied, Shortlisted, Hired, Rejected, and Withdrawn. The last three are terminal. Manual closure stops intake and preserves existing application review. | Keep interviews and feedback separate from application status. Preserve recruiter decisions on existing applications after manual closure, subject to capacity and the existing transition rules. |
| Admin can independently export or erase students, companies, jobs, and exact CV versions. Erasure can remove applications and close surviving jobs. | Include new dependent records in impact preview, export, and erasure. Keep structured links for new notifications so erasure can remove the right notices. Preserve shared CV originals while any valid reference remains. |

Use existing service and JDBC transaction boundaries in `ApplicationService`, `JobService`, `MatchService`, and `WorkspaceService`. Where a workflow spans more than one repository, use the same connection under the existing catalog-lock order. Write durable notifications in the transaction that changes the record. Use Swing workers for database and file operations, reload on entry or refresh, and discard results from a screen the user has already left.

### Delivery order

| Slice | Feature | Dependency |
| --- | --- | --- |
| 08 | Saved jobs | Current job access and admin erasure |
| 09 | Matching improvement checklist | Existing match explanations and CV review. Independent of slice 08. |
| 10 | Interview scheduling | Existing application transitions and inbox. Can ship with one vacancy if capacity is supplied by the shared rule. |
| 11 | Recruiter notes and interview feedback | Interview scheduling for the combined applicant-detail experience. Notes alone can ship independently. |
| 12 | Admin activity log | Current admin actions. Can ship earlier once audit retention and export-result semantics are agreed. |
| 13 | Multiple vacancies per posting | Update the shared availability rule and check its callers, including interviews and saved jobs if already shipped. |

Slices 08 and 09 provide two independent student features. If interviews are the immediate priority, start with slice 10 and keep the remaining order flexible.

## 08. Saved jobs

### Rules and implementation

- An active student can save a currently discoverable posting from Find jobs, Job matches, or the posting detail. Saving needs no CV or application. Unsave is available on any bookmark the student owns, even after the posting becomes unavailable. Repeat saves and removals are idempotent.
- Create `saved_jobs` with `student_id`, `job_id`, `saved_at`, and the last title visible at save time. Use `(student_id, job_id)` as the primary key and foreign keys to the student and job. Add a job index for deletion and admin scope queries. Use the existing catalog lock to serialize saving with moderation, closing, filling, or erasure. Keep the original saved time on duplicate requests.
- In the **Saved jobs** sidebar page, list newest first with a preview and Remove action. Derive status at read time. Available rows use live public details and the ordinary Open action. Closed or filled rows from active verified companies may show the title captured while public, plus their current status, with Open and Apply disabled. Pending, rejected, or inaccessible-company rows use an **Unavailable posting** placeholder without live posting data. Erased postings disappear with their bookmarks.
- Send users to the existing posting and application flows. Recheck visibility when opening from a stale row. A saved job can be visible while the student has already applied, so reuse the existing application check and show **View application** when an owned submission is available.
- Include bookmarks in student and posting erasure and in company erasure through owned postings. Include bookmark details in student ZIPs and scoped bookmark counts in job/company erase previews. Company/job ZIPs should omit other students' bookmark identities and saved titles. CV erasure leaves bookmarks intact.

### Acceptance checks

Check saving without a CV, simultaneous duplicate saves, ownership, persistence after restart, status changes on refresh, stale Open after fill, disabled or unpublished company/job detail protection, and cascade behavior for student/job/company erasure. Verify student export contains only that student's bookmarks.

## 09. Matching improvement checklist

### Rules and implementation

- Add **What to improve** to the selected match detail. Group actions under **Required to apply** for failed mandatory checks and **Could improve your match** for other gaps. Show CV approval separately. An eligible student with a pending CV sees the approval blocker without a fabricated qualification gap.
- Build checklist items from the same assessment used by `MatchService` and `MatchEngine`. Start with `MatchResult` mandatory checks, category contributions, and relevant-month evidence. If a category needs more context, return typed gap data from the same calculation using requirement kind and canonical ID. Use display text only for labels. Keep the existing stored explanation format and original assessments readable.
- Give evidence-specific instructions. A missing project skill points to adding genuine project evidence in My CV. An experience gap shows the counted months, required months, and accepted relevance tags. Degree level, field, and named certification gaps use the job's stated requirement. Show optional items as compatibility improvements. Deduplicate a requirement that appears in both groups and handle requirements with zero scoring weight that still block eligibility.
- Use a single current assessment for the panel. Keep the list derived and recompute on open/refresh after CV, job, or policy changes. Link to My CV and back to the match. Saving a CV edit follows the existing confirmation and approval process. If the posting became unavailable, use the existing access error. If no current reviewed CV exists, prompt for CV review.

### Acceptance checks

Cover a high score with a mandatory failure, optional-only gaps, project evidence distinct from general skill, relevant months with overlapping date ranges, a pending CV with all mandatory checks met, zero-weight mandatory gaps, and a complete match. Compare score, rank, and stored submission assessment before and after adding the feature. Verify refresh reflects CV/policy changes and cannot open a filled or inaccessible posting.

## 10. Interview scheduling

### State and permissions

| Action | Who can act | Result |
| --- | --- | --- |
| Propose a time | Active owning company, for a shortlisted applicant with an active account and capacity left | **Proposed**. Student receives the proposed time. |
| Accept or decline | Active applicant on the current future proposal | **Confirmed** or **Declined**. Company receives the response. |
| Request another time | Active applicant on a current future proposed or confirmed time | **Reschedule requested**. The prior time is released. Company can propose a replacement. |
| Replace or cancel | Active owning company on the current open interview | A replacement returns to **Proposed** and needs fresh acceptance. Cancellation records **Cancelled**. |
| Cancel automatically | Application decision, withdrawal, final fill, or account disablement | End affected proposals and confirmations for future slots, and close outstanding reschedule requests in the same transaction. Preserve interview history and notify active participants. |

- Start with one interview record per application, with one current proposal and an ordered event history. A company may propose again after a decline or cancellation while the application stays eligible. Interview scheduling is optional. A proposal or confirmation changes neither application status nor vacancy count. On Hire, Reject, or Withdraw, apply automatic cancellation to that application. On final fill, apply it to the remaining applicants as well, without changing their application statuses. Manual closure alone allows existing shortlisted applicants to continue while capacity remains. Apply that same closure rule to a job closed by erasure, and remove interviews tied to any erased application.
- Persist `interviews` with application FK, current status, revision, start/end UTC, display zone, bounded location or meeting link, and timestamps. Persist `interview_events` for proposal, response, replacement, and cancellation, with the actor role or a system reason. Use revision checks for every mutation so a stale acceptance cannot confirm a replaced proposal. Store changed proposal details in history only where needed to show the prior time and response. Foreign keys follow the owning application. Use the existing application transaction with the shared catalog/job lock for scheduling, terminal decisions, and final-fill cleanup. Account disablement must run cancellation on the same connection as the access change. Re-enabling an account does not restore cancelled appointments.
- Validate that the slot starts in the future and ends after it starts. Store instants in UTC and render the chosen zone with the time, using Asia/Dhaka as the proposed demo default. Reject ambiguous or skipped local times when an explicit zone conversion needs resolution. Define conflicts as overlapping confirmed intervals for the same company account or student, allowing adjacent slots. Check at acceptance under the transaction. Pending invitations do not reserve time. A proposal whose start has passed cannot be accepted. Show elapsed proposals and confirmed sessions in history based on time, with no background scheduler required. An elapsed confirmation alone does not assert attendance.
- Add interview actions to **Applicants** and **My applications**, plus an **Interviews** page for each participant with upcoming and past/cancelled sections. Display zone, time, place or link, and state. Keep the existing in-app inbox. Use a structured application link for interview notices, with a generic inbox message and participant-only interview details after service authorization. Extend erase preview and ZIP scope to include schedules, events, and linked notices. Admin access remains the existing scoped export and erase access.

### Acceptance checks

Walk through shortlist, propose, accept, request a different time, accept the replacement, and hire. Test stale revision and elapsed time using a controlled clock, overlapping acceptance from two connections, valid adjacent times, terminal transitions and final fill, manual closure, disablement, foreign access, rollback with no orphan notices, and scoped CV/application erasure. Verify a student can still be hired without an interview.

## 11. Recruiter notes and interview feedback

### Rules and implementation

- Only the active owning company can read or edit notes through **Applicants**. Keep student and admin monitoring screens free of notes and feedback. Admin data export remains explicitly scoped to the application records it already exports. Label notes as private to the company in ordinary workflows, with their inclusion in authorized admin exports disclosed in the UI.
- Add one editable `application_notes` row per application with a bounded text field, revision and updated time. Add one `interview_feedback` row per application for the first release, with bounded comments, a short fixed rubric such as technical evidence, communication, and role fit, and a rubric version. Use nullable ratings while a scorecard is incomplete and validate entered ratings on a 1–5 scale. Allow notes on any surviving owned application. Enable a scorecard only after a confirmed interview's end time, retaining eligibility to finish feedback if a decision follows. Bind it to the exact confirmed interview occurrence, since an application can have a rescheduled proposal. Use revision checks to prevent stale overwrites.
- Keep the scorecard separate from eligibility, score, ranking, and application status. Add a **Feedback** tab on applicant detail with Save and clear-state behavior. Compare feedback for two applicants to the same owned job with each rubric rating and **Not rated** visible. Preserve the existing current-policy ranking and original submission assessment. Keep student notices free of private content.
- Include records in the existing affected-application admin ZIP scopes and erase preview. Delete both with an erased application, including removal caused by erasing its submitted CV version. Keep company notes on applications whose CV was later replaced if the original submitted version still exists.

### Acceptance checks

Test owner and foreign sessions, disabled company, student and monitor visibility, incomplete ratings, invalid ratings, stale edits, confirmed versus cancelled or elapsed-unconfirmed interview, preservation through CV replacement, and exact-scope exports and erasure. Verify saving feedback leaves ranking, eligibility, and application transitions unchanged.

## 12. Admin activity log

### Rules and implementation

- Add admin-only **Activity log** with newest-first pagination, date/action filters and target ID search. Start with CV decisions, company verification, job moderation, account enable/disable, exports, and permanent erasures. An event contains a timestamp, actor admin ID, action, target kind/ID, outcome, and minimal counts or safe status values. Omit names of erased users, CV contents, notes, reasons that may contain personal data, credentials, and filesystem paths. Display retained target identifiers without resolving erased records back into people.
- Use an append-only `admin_activity` table with stable event ID, indexes on time/action and target, and an operation ID for export lifecycle events. Store target identifiers as scalar values so erasure can remove their source rows. Set the retention policy before release, including whether retained target IDs are acceptable. For the local demo, propose indefinite retention of these minimal events with no ordinary edit or delete action. Describe the log as application-level history in the single-machine deployment.
- Record successful DB changes in the same transaction as the admin operation, after validation and only when a change commits. Erase records DB success and separately records whether post-commit original cleanup is pending. Failed or cancelled confirmation creates no successful erase event. Keep existing independent export and erase actions.
- A ZIP move and a MySQL commit cannot share one transaction. After authorizing the admin and validating the target, commit a durable **Export started** event before writing. Recheck authorization and scope when building the archive. Use the existing staged ZIP and no-overwrite move. Record **Saved** only after the move succeeds, and **Failed** for a known failure. If the process stops or outcome recording fails, show an unresolved attempt and report the known file outcome to the caller when possible. Never log the destination path. Retain the durable attempt even if the export outcome could not be written.
- Keep audit rows outside per-record ZIPs so those exports remain data snapshots. The log shows which admin action ran and its result without preserving a copy of the exported or erased data. Historical actions predating the log have no fabricated timestamps or actors.

### Acceptance checks

Test active-admin-only access, stable pagination, no-op actions, mutation rollback, erase with and without export, file cleanup pending, preexisting ZIP destination, failed write, unavailable original with successful partial ZIP, and an injected interruption between move and audit completion. Verify the log survives target erasure and excludes content, reasons, and paths.

## 13. Multiple vacancies per posting

### Capacity and history rules

- Add a positive `vacancy_count` to jobs, defaulting existing and newly created postings to **1**. Use the existing application index to derive `hired_count` from committed `HIRED` applications. Define **remaining = vacancy_count − hired_count**, with **filled** when no vacancies remain. Keep job moderation and closure status separate. Opening a posting means it is approved, its company is active and verified, and it has remaining capacity.
- Add vacancy count to the company job editor and admin review. Allow edits only through the ordinary job resubmission path before the first application, with reapproval. Freeze it with requirements on the first application, including a later withdrawal. A company can publish a separate posting for more openings after the freeze. Expose remaining and total vacancies in job details and company lists.
- Replace the one-hire `JdbcJobs.FILLED_JOB` / `hasHire` rule with a shared capacity query and in-transaction check. Update `JobService` list/open/circular access, `MatchService` callers through `JobService`, `ApplicationService` apply and hire, the student dashboard count in `JdbcWorkspace`, and Saved jobs and interview checks if present. Reuse the existing catalog and job locks. Read remaining capacity again inside submission and transition transactions. Existing company decisions after manual closure can proceed on their applications while capacity remains. Reject the hire that would exceed the vacancy count. A final fill preserves other applicants' status and submitted evidence and cancels their future open interviews if scheduling is installed.
- Keep **Student Hiring history** as a posting-level list of fully filled postings, with counts and no applicant identities. A manually closed partially filled posting leaves discovery but does not enter that public filled list. Keep **Company Hiring history** scoped to the owning company and list each retained hire, including hires on partially filled postings, with links to its existing application details. Store `hired_at` for new transitions. Legacy hires have unknown hire time and sort using a stable fallback, with no timestamp inferred from submission. Erased hire rows leave company history with their applications. Surviving closed jobs remain accessible through permitted history and review paths, independently of discovery.
- Update `JdbcAdminData` to compute pre- and post-erasure capacity under the existing lock. Close a surviving posting if erasure would create an opening on a previously filled posting, or if it removes the last application and would otherwise unfreeze the posting. A hire erased from a still-open, already partially filled posting may increase its remaining count. Show that capacity change in the erase preview and recheck it at confirmation. On a closed posting, erasure never reopens public intake. Jobs already closed need no extra closure write. Keep job/circular deletion and exact CV scope unchanged.

### Acceptance checks

Upgrade old schemas with both unfilled and filled jobs. Verify the first hire of a two-vacancy posting leaves it in discovery and the second removes it from every student surface. Exercise simultaneous final-slot hires and submission racing final fill using separate connections. Verify only one hire takes the last slot, a submission that commits first retains its snapshot, and a later submission is refused. Cover freeze and reapproval, close with unused capacity, both history scopes, legacy hires without timestamps, and erasure from a filled versus partially filled posting. Check that history and dashboard totals agree with their stated meanings.

## Integration, migration, and release checks

1. Use the current working tree and completed admin-data handoff as the baseline. Before each slice, record its acceptance criteria and inspect affected write paths. Confirm the proposed closed-job interview rule, feedback export scope, audit retention, and partially filled erasure policy before implementing the corresponding slices.
2. Add new tables and indexes to the packaged `schema.sql`, and update affected erase/export scopes and test-fixture cleanup in the same slice. Existing tables need an explicit, repeatable upgrade for new columns. The vacancy migration records its version and checks for each column before applying DDL, so an interrupted startup can resume. The clean-database smoke exercises a fresh install and the pre-vacancy column layout with seeded applications, including an interrupted upgrade and repeated initialization. Keep old application rows and approved CV evidence intact. Update ER exports, reproduction instructions, and demo flow alongside schema changes.
3. For every slice run `mvn test`, package, and review `git diff --check` plus new files. For persistence changes run `scripts/verify.ps1 -Offline -CleanDatabaseSmoke`. Put race, permissions, scope, and rollback checks at the service boundary using `smarthire_test`. Use focused unit tests for checklist ordering, scheduling/time rules, and capacity rules where the behavior is independent of storage.
4. Walk the actual Swing flows as student, company, and admin using fictional records. Verify small-window layouts, keyboard focus, empty and stale states, status wording, and file chooser behavior where relevant. Record database verification and physical Windows acceptance separately. Use the existing verification and demo scripts to keep the baseline single-machine and offline.
