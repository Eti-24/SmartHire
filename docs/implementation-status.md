# Implementation status

Implementation started on 2026-11-05. Slices 01, 02, 02a, 03, 04, 05, 05b and 06 are implemented. Interactive Swing acceptance remains pending.

## Slice 01: identity and navigation

Delivered Java 21/Maven/Swing startup, local MySQL identity tables, student/company registration, salted PBKDF2 passwords, explicit fictional accounts, sessions, service role checks, disabled-account checks, persistent profiles, logout, and background operations with startup retry. Existing identity tests remain intact and pass.

## Slice 02: publication

Delivered company verification; persisted categories and technical/soft skills with aliases; structured job requirements; admin approval/rejection; company editing/resubmission and closing; student title/category search; and role-specific Swing screens. Publication and access checks enforce active accounts, ownership, verified companies, approval, and closure. Editing returns a posting to pending approval. Canonical skill IDs unify aliases, job/project skills, and experience tags. Duplicate requirements, invalid salary ranges, unscored jobs, and invalid experience/degree combinations are rejected.

The explicit seed adds four fictional matching-title jobs across two categories, including pending and closed postings. Existing jobs and decisions are preserved on repeat seeding. The shared skill fixture remains available for CV parsing in slice 03.

Slice 02 was tested and packaged before slice 02a implementation. Its initial full suite passed 12 unit tests and 4 database tests. A subsequent refinement accepts skill names/aliases and normalizes certification/field whitespace before duplicate checks.

## Slice 02a: shared document adapter and store

Delivered PDFBox/POI parsing, typed failures, 5 MB streaming limits, textless/encrypted/malformed input rejection, immutable UUID-addressed managed copies with SHA-256 checks, database references, rollback cleanup, and startup reconciliation. A store lock prevents another process from recovering active uploads. Recovery preserves all referenced files, retains history for missing/changed bytes, and does no cleanup when reference lookup fails.

Real fictional PDF/DOCX fixtures and their Java generator are included. The DOCX fixture includes table text. Document lifecycle integration is documented in `docs/document-storage.md`; CV confirmation and circular attachment insert document references with their owning records in the same transaction.

## Slice 03: CV upload and editable review

Delivered the student My CV workspace with background PDF/DOCX upload, extracted-text inspection, conservative qualification suggestions, separate technical/soft skill selection, editable education, project evidence, dated skill-tagged experience, certifications, confirmation, version history, and original-byte exports. Existing confirmed qualifications are not reused silently for replacement uploads. Unknown/ambiguous information stays available for manual review.

Confirmation locks the active student, rejects stale drafts, canonicalizes qualifications, validates dates, freezes ongoing roles at the confirmation month, and saves document metadata, qualification children, immutable review version, and current pointer atomically. Editing a confirmed review creates a new version using the same original document. Cancelling or rolling back a replacement leaves the previous review usable. Role, ownership, disabled-session, and managed-byte integrity checks protect access. History loads summaries and retrieves full review/text only when needed.

See `docs/acceptance-slice-03.md` for the concrete walkthrough, extraction conventions, and failure behavior.

## Slice 04: explained job matching

Delivered the pure matching engine and immutable versioned `default-v1` policy, plus student Job matches navigation, ranked recommendations and current-data explanations. Category requirements determine active weight redistribution; explanations retain unrounded fractions, weights and contributions, matched/missing evidence, technical/soft skill types, relevant distinct months/tags, mandatory results, and rule-based suggestions. Eligibility is independent of the score. Required skill priority doubles scoring weight without automatically making it mandatory.

Experience merges inclusive tagged month ranges, avoids overlap inflation and caps its fraction; frozen ongoing review dates remain unchanged. Education uses the documented demo degree ordering and exact normalized fields without inferred aliases. Project skills require project evidence; certifications use exact normalized names. The pure engine can later assess immutable application qualifications under a common policy without changing original results.

Recommendations use the current confirmed CV, visible approved/open jobs and curated skills on every request; no cache is stored. Entry/refresh recalculates ordering, and opening a result rechecks current CV and job access. Eligible results come first, then descending unrounded score, then job ID. Missing reviews prompt review instead of fabricated scores. Role/active-account and existing job visibility rules apply through application services. SwingWorker keeps matching/database work off the event-dispatch thread; abandoned screen results are ignored.

See `docs/acceptance-slice-04.md` for conventions and the manual journey. Applications, policy administration and text similarity are delivered in the following slices.

The requested matching UI refinement adds **Open selected job**, reusing the full job-detail screen with **Back to matches** navigation and live job-access checks. **Why this match?** now leads with score, eligibility and qualification gaps; policy/review identifiers, weights, contribution points and full mandatory checks are available through **Show score details**. Scoring and eligibility rules are unchanged.

## Slice 05: applications and recruiter review

Delivered transactional eligible submissions, database-enforced one-per-student/job uniqueness, immutable submitted CV references and contact/assessment snapshots, student tracking/withdrawal, owning-company ranking/filtering, submitted CV text/qualification inspection and original-byte download, shortlisting/rejection/hiring, terminal-state enforcement, and job requirement freezing after any committed application (including withdrawal).

Submission and edit/closure transactions share the catalog mutex and job row lock. Submission rechecks job/company/student state, current confirmed CV, file integrity and eligibility before writing. The full package suite passed 55 tests (35 unit, 20 MySQL integration), including seven new application checks and both race commit orderings on separate connections. Diff whitespace checks passed. See `docs/acceptance-slice-05.md`; visual acceptance remains unverified. Slice 05b started only after this checkpoint passed.

## Slice 05b: comparison, circulars and scoring-policy changes

Delivered two-job comparison using one reviewed CV and one current policy, side-by-side category/gap results, and supplementary TF-IDF/cosine text similarity clearly separated from score and eligibility. Empty text is defined as zero similarity. The simple default match explanation remains intact.

Delivered optional PDF/DOCX circular attachment, extracted-text viewing/original-byte export, role/ownership/current-visibility checks, immutable retained circular versions, atomic replacement/pending approval, failure cleanup and application-based freezing. Recruiters preview suggestions from pasted description or attached text and edit the resulting structured rows. Unknown material remains manual; suggestions never silently create mandatory requirements or overwrite existing canonical rows.

Circular upload is directly available as **Attach circular** on the company job list after selecting a posting. Job details group attach/view/save controls in a titled **Job circular** section above the requirements, so they are not buried in the general action row.

Delivered persisted immutable scoring-policy versions and admin weight activation. Approval, activation and submission lock the same current-policy record after the existing catalog mutex. Activation rejects negative/all-zero weights and lists incompatible approved open jobs or jobs with applications, including closed postings; failure leaves the prior policy unchanged. Current recommendations and recruiter ranking use the active policy; all applicants to a job are recalculated from their own immutable submitted qualifications against frozen requirements. Original assessment policy/score/explanation remain unchanged across later policy and CV edits.

See `docs/acceptance-slice-05b.md` for the complete journey, extraction and similarity conventions, and remaining visual checks. Notifications and broader admin oversight are delivered in slice 06 below.

## Slice 06: notifications and oversight

Slice 06 adds transaction-bound persisted verification/job moderation and application submission/status alerts for participants, owned/idempotent read state, admin account enable/disable, read-only filtered application monitoring, and scoped role dashboards with JFreeChart application-status charts. Account disablement preserves history and blocks existing sessions on their next action. Admin disablement is protected. Existing technical/soft skill maintenance remains available. See `docs/acceptance-slice-06.md`.

Its first full package checkpoint passed 73 tests (39 unit, 34 MySQL integration), with no failures or skips. Five new workspace integration tests cover notifications/rollback, read ownership, monitor filters/permissions, disablement and preserved downloads, and dashboard scope. A follow-up boundary check covers maximum-length rejection reasons without notification truncation breaking moderation.

## Slice 07: reproducibility and presentation

Delivered the complete offline ER export (`docs/database/er-diagram.html`, SVG and editable Mermaid), generated from the implemented 22-table/23-foreign-key schema; three rendered/visually checked fictional CV PDFs with parser-tested 90.48% ineligible, 42.86% skill-gap and 100% eligible outcomes; a precise three-role demo script; updated launch/NetBeans/Workbench instructions, database/managed-file portability guidance, and explicit additive seed/repeat/reset behavior. Optional generators are supplied; Python is unnecessary for app/runtime tests.

`scripts/verify.ps1 -Offline -CleanDatabaseSmoke` runs packaging, classpath generation, the full suite and an opt-in fresh-database journey. The smoke creates and drops only its own random disposable schema using private administrative configuration. It checks repeated initialization/seeding, real PDF review and mandatory blocking, submission, immutable original bytes after replacement, shortlist/hire, requirement freeze, saved alerts/read state and reopening the managed store. Existing demo/test databases are retained. MySQL server reported 8.4.13. Reproduction instructions record actual tool-version checks and their limits.

The real dashboard component was rendered offscreen to inspect chart labels and colors. ER SVG and all three CV pages were rendered and visually inspected. These are artifact/component checks, not desktop screenshots. Final interactive Swing acceptance is still pending because the Windows helper failed to initialize twice with `windows sandbox failed: helper_unknown_error: setup refresh had errors`. Slice 07's source/artifacts/automated work is delivered; the full visual acceptance criterion is not marked complete.

## Latest validation

The user subsequently requested admin CV approval before applying. Added a separate admin **CV review** screen; original-file/confirmed-qualification inspection; Pending/Approved/Rejected version decisions with reason/reviewer/time and atomic student notification; student status/history/refresh; an admin pending-CV count; and submission-time approval enforcement. Confirmed uploads and qualification-only edits start Pending. Draft cancellation preserves the current approved version. Legacy missing decisions fail closed. Decided/replaced versions cannot be re-decided, and existing applications retain their original evidence. See `docs/acceptance-cv-approval.md` and the updated specification. Current ER exports contain 23 tables and 25 foreign keys.

- Combined suite after the approval feature: **84 passed, zero failures/errors/skips**: 40 unit, 43 MySQL integration and one isolated clean-database smoke.
- Command: `scripts/verify.ps1 -Offline -CleanDatabaseSmoke`. Packaging and runtime classpath generation succeeded. Slice 06 passed its 73-test checkpoint before slice 07 began.
- Ordinary unit-only command (`mvn -o '-Dsurefire.reportNameSuffix=unit' test`) passed 40 with 44 database/smoke checks explicitly disabled. Separate report suffixes retain the full-run evidence. The final approval-focused package run also passed all nine checks, including distinct admin accounts and dashboard counts.
- After the final chart palette refinement, unit tests and packaging were rerun successfully and the component was rendered again. Service/schema behavior was unchanged after the 75-check run. Tracked and new text-file whitespace checks passed.
- Unit coverage includes 11 matching tests, 9 document lifecycle tests, 7 identity tests, 5 vocabulary tests, 3 CV extraction tests, 2 requirement-extraction tests, 2 text-similarity tests and one test of the three real demo PDFs and exact expected outcomes.
- Database checks additionally cover confirmed CV persistence, immutable historical reviews/originals, replacement/cancellation, stale drafts, ownership/roles/disablement, missing bytes, and replacement rollback after writes in `smarthire_test`.
- Three matching database checks additionally cover parsed/confirmed CVs, no-review state, canonical aliases, eligibility/score/ID ordering, CV and reapproved requirement refresh, independent students, restart, closed/unverified/disabled-company access, and disabled student sessions.
- Eight application checks cover saved evidence/contact details/original bytes, replacement/restart/closure, uniqueness including concurrent duplicates, withdrawal and terminal decisions, permissions, missing files, rollback, filtering and both application/edit race orderings.
- Seven policy/circular/comparison checks cover incompatible open/closed-applicant policy rejection, common-policy ranking with cross-policy originals preserved, perfect-score mandatory failure, circular ownership/reapproval/freeze/rollback/exports/missing bytes, comparison access and both approval/activation race orderings.
- An additional seeder regression check verifies that additive demo publication cannot bypass the active scoring policy and rolls back incompatible fixtures.
- Nine CV approval checks cover Pending/legacy refusal despite a 100% eligible match, saved reviewer/time and originals, reasons/resubmission, draft cancellation, replacement blocking with preserved applications, role/ownership/disablement/stale/missing-byte access, notification rollback, conflicting admin decisions, and both commit orders for approval/replacement and application/replacement races. The fresh-database smoke explicitly obtains approval through the admin service.
- Full test/package/classpath command without the optional clean smoke: `mvn -o '-Dsmarthire.integration=true' package dependency:build-classpath '-Dmdep.outputFile=target/runtime-classpath.txt' '-DincludeScope=runtime'` after loading `scripts/dev-env.ps1`. The clean smoke is separately gated by `smarthire.cleanSmoke=true`; the verify script sets it with the switch above.
- Packaging, runtime classpath generation, and whitespace checks are verified at the slice handoff. Existing tests are preserved.
- The computer-use runtime again failed to initialize (`windows sandbox failed: helper_unknown_error: setup refresh had errors`). No screenshots, interactive upload/navigation walkthrough, or laptop screen-fit verification is claimed. Acceptance guides record those remaining checks.

## Remaining acceptance

The requested Bangladeshi fictional names are now used in the three demo CVs (Tanvir Ahmed, Nusrat Jahan, Arafat Hasan) and future seeded profiles (Arafat Hasan, Dhaka Software Demo, Farhana Rahman). Two unchanged local default demo profiles were renamed; custom profile names and saved application snapshots were retained. Regenerated PDFs were visually checked, and the real-sample score test plus isolated clean-database workflow passed again with packaging.

Run the laptop journey in `docs/demo-guide.md` and the slice acceptance guides: all three roles, keyboard/tab navigation, display scaling/screen fit, upload responsiveness, comparison, file chooser/download/overwrite behavior, notification dialogs, admin filters and database retry. No requested feature slice remains undeveloped; these live checks remain unverified. Do not describe them as passed merely because service tests pass.

## Swing redesign checkpoint

The signed-in workspace now keeps role navigation visible. Job matches, Applicants and CV review use a selectable list, an adjacent preview and a separate detail view. Job matches gives eligibility and mandatory gaps priority over score and displays CV approval separately. Applicants separates current-policy match from the original assessment. CV review keeps original-file access and decisions on the exact confirmed version. See [the redesign brief](ui-redesign-brief.md) for the wider rollout.

- Offline packaging passed with 40 unit tests. The integration package run passed 43 MySQL tests with the optional clean-database smoke skipped.
- A Swing smoke harness used fictional records in `smarthire_test` to drive the three screens at default sizing and at a compact window with Java UI scaling set to 125%. It checked persistent navigation, a visible 85.71% mandatory failure with separate pending CV approval, preview selection, keyboard traversal to the match table, enabled actions, loading and empty match states, invalid filter feedback, applicant assessment labels, and reachability of CV decision controls.
- The checkpoint was followed by the remaining Swing layout work below.

## Remaining Swing screens

The dashboard now presents role-specific counts and an application-status chart. Job lists, CV history, notifications, account access, and application monitoring use a list and preview. Posting details group requirements and circular access in tabs. The job and CV editors keep submission controls in view while users switch sections. Account forms and the administration tools use the shared styles, with company verification, categories, skills, and matching weights grouped under **Jobs and administration**.

- Packaging passed with 40 unit tests and 43 MySQL integration tests. The optional clean-database smoke was skipped. Packaging and unit tests passed again after the final layout adjustments.
- The Swing smoke harness used fictional MySQL records at default sizing and in a compact window with Java UI scaling set to 125%. It checked startup retry, account forms, job search and empty results, editor actions, CV draft cancellation, notification read state, protected admin accounts, read-only monitoring, catalog form reachability, and the three original checkpoint screens.
- Physical-laptop acceptance and the file chooser upload, download, and overwrite interactions remain pending. The smoke harness checked the placement of submit and decision controls. It used test sessions and did not execute the full demo sign-in and decision sequence through Windows input.

## Swing workflow follow-up

- `scripts/verify.ps1 -Offline -CleanDatabaseSmoke` passed all 84 checks with zero failures, errors or skips. That includes 40 unit tests, 43 MySQL integration tests, and the isolated fresh-database smoke. Packaging and runtime classpath generation succeeded.
- A separate Swing harness at 125% Java UI scaling in a 1090 × 600 window used the normal sign-in form for all three roles with fictional records in `smarthire_test`. It exercised the actual Swing file chooser with an eligible PDF and a textless PDF, confirmed a new CV version, and verified that declining overwrite preserves the destination bytes and accepting it saves the exact original PDF. It cancelled and confirmed the approval prompt for the same CV version, opened the job comparison, submitted the approved CV, and verified that shortlist and hire persisted and removed the recruiter decision controls.
- The harness invoked Swing controls programmatically. The Windows desktop helper could inspect an existing older SmartHire window but could not see the isolated build. Physical 1366 × 768 acceptance with Windows input, including keyboard focus, chooser interaction and startup through the normal launcher, remains open.

The user accepted the redesign after reviewing these results.

## Feature expansion, slices 08–13

Delivered Saved jobs, the matching improvement checklist, interview scheduling, recruiter notes and scorecards, the admin Activity log, and multiple vacancies. The [expansion plan](feature-expansion-plan.md) describes the service rules and sequence. The [demo guide](demo-guide.md) gives the three-role walkthrough.

- `scripts/verify.ps1 -Offline -CleanDatabaseSmoke` passed 100 checks with no failures, errors, or skips. This includes 41 unit tests, 58 MySQL integration tests, and one isolated smoke covering fresh installation, the pre-vacancy column layout, interrupted upgrade, and repeated startup. Packaging and runtime classpath generation succeeded.
- Existing postings default to one vacancy. The migration keeps legacy hires and application evidence and leaves their unknown hire time empty. Current-posting capacity derives from committed hired applications. The final hire removes the posting from discovery and cancels other outstanding interview actions without changing those application statuses.
- Admin erase/export scopes include the new dependent records. The Activity log retains minimal identifiers after target erasure and reports unresolved export outcomes when the final audit write fails after the file is saved. No live demonstration records were used in the verification.
- A Swing harness at 125% scaling in a 1090 × 600 window signed in through the normal form as all three roles. It saved a job and opened the owned application, inspected the checklist, proposed and accepted an interview, saved a recruiter note, followed company hiring history to an application, and opened the admin Activity log. It also checked that the two-vacancy posting stayed visible after the first hire and entered student Hiring history after the second. Physical Windows input and laptop acceptance remain pending.
