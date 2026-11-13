# Implementation status

## Version 0.1.4 release verification, 2026-11-13

Prepared the local and cloud Windows ZIPs from the current client-setup and interface changes. The root README and bundled release guide now explain that an application properties file requires a real MySQL account, how to match the server port, where private settings belong and which setup commands are available in the source download.

- `scripts/verify.ps1 -Offline -CleanDatabaseSmoke` passed **120 tests, zero failures/errors/skips**, including real MySQL, fresh-schema and compact Swing saved-item checks (`target/publish-verification.log`).
- `scripts/test-client-setup.ps1 -Port 3317` passed the disposable-server setup/reset/restart and recovery rehearsal, including missing database/configuration, damaged app account/grants, retained data/passwords, refusal cases and cloud retention (`target/publish-client-setup.log`). The rehearsal server was shut down; retained copies under ignored target contain private settings and must not be distributed.
- `scripts/test-netbeans-launch.ps1`, PowerShell syntax parsing and `git diff --check` passed. Both release builds passed their default unit suite. Extracted ZIPs contained the launchers, libraries, demo files and current setup guide, with no bundled private configuration. Local and cloud packaged launchers passed read-only `--check-database` checks using temporary private configuration, which was removed afterward (`target/publish-bundle-check.log`). No cloud schema or business data was changed.
- Client Windows 10/XAMPP coexistence and a cold NetBeans launch remain unverified on that PC. Earlier entries describe the individual development checks and limitations; their references to unpublished source and older ZIPs describe the state at that time.

## NetBeans CMD launch correction, 2026-11-13

Reproduced `Start-SmartHire-NetBeans.cmd` completing readiness and returning zero without selecting the visible IDE: it forced `.tools/client-netbeans-user`, a separate profile already held by an earlier background process. The visible installed IDE used a different profile. Removed the forced user/cache directories so the installation's normal profile and existing instance receive the launch. No settings, locks or existing processes were deleted.

Added CMD progress text and a two-second immediate-exit check. A real retest exposed NetBeans' native -252 CONNECTED handoff status; this is now accepted alongside zero, while other immediate exits produce a specific error. [Apache's CLIHandler source](https://github.com/apache/netbeans/blob/master/platform/o.n.bootstrap/src/org/netbeans/CLIHandler.java) defines CONNECTED. The launcher does not claim a newly spawned process always means a visible window.

`scripts/test-netbeans-launch.ps1` failed against the forced-profile implementation (`target/netbeans-launch-red.log`) and passes after correction (`target/netbeans-launch-green.log`). It covers the real script with a process stub, readiness ordering, spaces, saved path, normal profile, running/handoff/failure paths and CheckOnly. The actual root CMD completed successfully after correction and the visible NetBeans window with SmartHire loaded was inspected through Computer Use. This was an existing-instance handoff; a cold launch on the client's Windows 10 installation is not claimed. Script syntax and diff checks passed. Java/services/database logic did not change in this correction, so the prior passing 120-test result remains the application checkpoint.

## Local readiness and safe repair, 2026-11-13

The missing main database found in the desktop run is now covered by a shared source-installation readiness workflow. `Client-Repair.cmd` runs it directly; setup, source run and the NetBeans launcher invoke the same script before continuing. The launcher opens NetBeans only after readiness succeeds.

- `start-mysql.ps1` verifies the server's real data directory against this project, and reports an occupied port/private-admin failure rather than starting another server against it. It never guesses the PC's installed MySQL password or stops XAMPP.
- `repair-local.ps1` restores generated app settings from the surviving private app INI, regenerates properties/client configuration with the verified server port, creates missing local schemas, restores the dedicated generated app account/scoped privileges when needed, initializes schema through `Main --prepare-local`, seeds only an empty main database (or an explicit seed request), and verifies the final app connection. Existing populated business records and application login passwords are retained. It can build a missing/older runtime that lacks the preparation entry point.
- Missing/empty SQL data with old managed CV/circular bytes stops recovery before allowing normal orphan-file cleanup. An unfamiliar partial schema without a users table, both app credential copies missing, missing private admin credentials and foreign endpoints fail with recovery instructions. No destructive reset or cloud repair happens automatically. `Client-Setup.cmd --reset` remains the separate confirmed destructive path.
- Local connection failures now point source-installation users to Client-Repair.cmd; application-only ZIP users retain their separate setup guide. A credential-free `.local/readiness-status.txt` records CHECKING/READY and time. See [the recovery table](client-local-setup.md).
- Verification: `target/readiness-full.log` records the full **120-test** unit/database/fresh-schema run passing with no failures/errors/skips and successful packaging. `target/client-readiness-final.log` records the expanded disposable-server rehearsal, including missing schema auto-recovery via the NetBeans launcher, retained-CV refusal, private config recovery, deliberately broken app account/grants, application password/custom-data preservation, missing-credential/foreign-server/occupied-port refusal, reset and restart. The disposable server was shut down; ignored copies contain private credentials and must not be distributed. An earlier rehearsal's cleanup error masked its original reset failure; cleanup now preserves the original outcome, and the expanded final rehearsal passed.
- The readiness script also ran successfully on the working PC installation, comparing account count/password hashes before and after without printing them; they were unchanged. The existing app remained open. Script syntax and diff checks passed. Client Windows 10/XAMPP acceptance and new release ZIPs/GitHub publication remain outstanding; these source changes do not alter cloud setup.

## Saved items, colours and client setup, 2026-11-13

Subsequent desktop verification on the development PC opened the installed NetBeans, loaded the Maven project and ran F6 through Windows input. The first real run found the main `smarthire` schema missing while `smarthire_test` existed. Created only the missing main schema using the private local admin connection, then ran the additive demo seed; the connection check passed and clicking Retry reached the actual SmartHire sign-in screen. No existing schema was dropped or reset. The launcher server/config/path check also passed. The execution-sandbox launcher created a NetBeans process whose window was not targetable, so the visible IDE was launched separately through Computer Use; this does not establish end-to-end CMD desktop launch on the client's Windows 10 PC. The running application was left at sign-in. No physical login or saved-item clicks were performed; those remain covered by the automated/service and programmatic Swing checks below.

The client did not specify a saved-item failure. Reproduction found compact-screen toolbar clipping: FlowLayout wrapped controls without reserving their row height. Shared action bars now measure wrapped rows, including the matching footer, while authentication forms retain compact action spacing. No saved-item persistence or permission defect was established; service rules and automatic alerts remain enabled.

- Added real-database/UI checks for discovery saving, match bookmarking, bookmark opening/removal, saved-search creation, running, edit/delete dialogs, pause/resume, complete filter persistence across service recreation, private ownership, invalid input and future-approval alerts. Existing duplicate, filled/closed/expired/unavailable posting, application-link, export/erase and deduplicated-alert checks remain intact.
- Added blue, violet and amber section/navigation accents, tinted dashboard cards and blue table/tab surfaces alongside the supplied logo and teal identity. Status semantics and matching/hiring/CV-approval rules are unchanged.
- `Client-Setup.cmd` creates and seeds a dedicated generated-password server without the PC's existing MySQL password. Ordinary reruns preserve data and credentials. `--reset` requires typing RESET, verifies ownership/server identity and bounded unlinked targets, refuses an open document store, stops the dedicated server and restores initial local demo data. Cloud configuration and cloud documents are outside the reset targets. An unfinished initial seed is retried additively on setup retry.
- `Start-SmartHire-NetBeans.cmd` finds or asks for the existing IDE executable, remembers it privately, starts MySQL, writes local app configuration and opens NetBeans with project Java 21. First launch requires opening the project and selecting `smarthire.Main` if prompted. See [the client guide](client-local-setup.md), including prerequisites and custom ports.
- Final `scripts/verify.ps1 -Offline -CleanDatabaseSmoke` passed **120 tests, zero failures/errors/skips**, with Swing checks at 125% scaling; packaging/classpath generation succeeded (`target/client-followup-final.log`). The initial compact control test failed before the layout fix (`target/saved-ui-red.log`). All ten focused saved/deadline checks passed (`target/saved-complete.log`). Authentication alignment, registration, recovery and sign-in passed at normal/125% scaling, including 920 x 460 (`target/client-auth-100.log`, `target/client-auth-125.log`).
- `scripts/test-client-setup.ps1` passed an isolated real-server rehearsal in a path with spaces: fresh demo, preserved rerun/port/credentials, cancelled reset, wrong-owner/open-store refusal, confirmed reset, demo restoration, cloud sentinel retention and stop/restart with the existing-NetBeans path check (`target/client-setup-final.log`). An existing unmarked working installation was separately confirmed to refuse reset. Rehearsal servers were shut down; private disposable copies remain ignored under target.
- Compact three-role Swing walkthrough passed and the generated role/toolbar/dashboard screenshots were visually inspected (`target/client-colours-walkthrough.log`). Script syntax and `git diff --check` passed. Actual client Windows 10/XAMPP coexistence, NetBeans window launch/F6 and physical input remain unverified; the launcher rehearsal uses CheckOnly. No live cloud database changes, GitHub publication or release-ZIP rebuild were performed. Existing ZIPs predate this follow-up; use the updated source project.

## Client setup and UI follow-up, 2026-11-13

Saved searches, their approval-triggered alerts, normal search filters and Saved jobs remain enabled. Their removal is on hold at the user's request.

- Local connections accept an optional `port` in `application.properties`. Older files still use 3306. New portable setups use 3307 and accept `-Port`; initialized installations retain their data, credentials and existing port. The setup probes availability using .NET sockets, avoiding reliance on Windows CIM queries. Source launchers, dependency checks and fresh-schema verification propagate the configured port. The Windows release guide explains running a separate SmartHire MySQL server alongside XAMPP. Changing app properties alone does not change a running server's port.
- Connection failures identify the endpoint and distinguish authentication, permissions, missing schemas and connectivity, without exposing credentials or raw SQL errors. Cloud certificate verification and the cloud schema version remain unchanged.
- A failed sign-in opens one error dialog, preserves the email, clears the password and requests password-field focus after dismissal. Existing background execution and duplicate-submit prevention remain in place.
- All roles share refreshed teal controls with hover, disabled and keyboard-focus states, section headers, form labels, table headers and alternating rows. Dashboard counts and navigation have stronger visual hierarchy. The login card uses compact branding and keeps its three actions aligned.
- Verification: `target/client-retouch-final-verification.log` records 115 passing checks, including 49 unit checks and 66 database checks (one isolated fresh-schema smoke), with no failures or skips. The subsequent package run repeats all 49 unit checks after the final presentation changes. The isolated coexistence rehearsal uses the actual setup script, initializes a separate server on 3307, seeds eight demo accounts, reads them back, verifies the existing 3306 server remains reachable and checks that rerunning setup preserves the configured port. An occupied-port probe confirms refusal before creating credentials.
- Swing verification: `target/login-popup-verification.log` records failed login and successful retry; `target/auth-alignment-100.log` and `target/auth-alignment-125.log` cover aligned actions, registration, recovery and sign-in at normal and 125% scaling, including 920 x 460. `target/client-retouch-swing-walkthrough.log` covers student jobs, matches, CVs and profile; recruiter posting editing, applicants and notifications; admin dashboard, monitoring, catalogs and CV review at compact sizing and 125% scaling. Rendered sign-in, student, company and admin screens were visually inspected.
- These are programmatic Swing checks on the available development machine. Physical Windows 10 input, installer behavior and the client's actual XAMPP installation still require acceptance on that PC. The two-port rehearsal uses two independent MySQL servers; it does not claim a live XAMPP/MariaDB compatibility test.

Version 0.1.4 packages the changes. No database migration is needed. No live cloud database changes or GitHub publication were performed in this follow-up.

## Local roadmap slices 14–20

The local Swing build includes slices 14–17 and 19–20 of the [local roadmap](local-feature-roadmap.md).

- **14. Word templates.** My CV and the company job editor and circular controls offer editable DOCX downloads. Save As copies the bundled file. Uploading an edited copy follows the existing CV or circular review flow.
- **15. Application timeline.** Students and recruiters see submission, recorded status changes and interview revisions. Historical status stays visible when its transition date is unknown. The admin archive includes scoped status events.
- **16. Deadlines and search.** Companies can set an optional closing time and time zone. The cutoff excludes expired postings from discovery and new applications while recruiters retain access to existing applications. Find jobs supports location, salary overlap and closing-date filters.
- **17. Saved searches.** Students can run, edit, pause or remove saved criteria. A matching approval can create one neutral inbox alert per student and posting. Opening the posting rechecks student access.
- **18. Calendar export removed.** Participants schedule interviews and review their history in SmartHire.
- **19. Reports.** Companies and admins can filter an application cohort and see conversion, average times based on recorded events, and current capacity. The first local release displays the report in Swing.
- **20. Password and recovery.** Password changes and recovery invalidate older sessions. Issuing codes replaces the previous set. A successful reset consumes the full set. Recovery attempts are limited per sign-in service process, so restarting the local app restarts the limit. The database stores only code hashes, which are excluded from admin exports.

`scripts/package-release.ps1` builds the local ZIP with `SmartHire.cmd`. The `-Cloud` switch builds a separate Aiven ZIP with `SmartHire-Cloud.cmd`. The cloud launcher checks the schema on normal startup and applies migrations only when run with `--upgrade-database`.

The Aiven demo schema was upgraded from version 1 to 5 after a backup and an isolated Aiven rehearsal. The live migration preserved the counts of eight accounts, eight jobs, two applications and one managed document reference. Existing demo sign-ins, job browsing and recruiter and admin reports passed against the upgraded schema. The rehearsal verified saved-search writes and password recovery. Original document files remain in the launching computer's private cloud document folder.

After calendar export was removed, all 44 unit checks and 66 local MySQL checks passed. The suite exercised fresh installation, upgrade from the previous schema, migration retries and preservation of a changed demo password during reseeding. The automated Swing walkthrough at 125% scaling used the original date and time form to propose an interview as the company and accept it as the student. It checked that the company Interview tab and the student Interviews page omit the export button. Earlier Swing checks covered both DOCX downloads, save cancellation, overwrite refusal, edited reuploads, the saved-search alert, timeline, reports and password change. Physical Windows input and visual rendering in Word still need a manual pass. See the [local roadmap walkthrough](demo-guide.md#local-roadmap-walkthrough).

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
