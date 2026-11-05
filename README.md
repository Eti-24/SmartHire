# SmartHire

SmartHire is a desktop recruitment application built with Java 21, Swing, and MySQL. Students upload and review their CVs, compare job matches, and track applications. Companies publish vacancies and review applicants. Administrators approve companies, job postings, and CV versions.

Matching scores explain how each qualification contributes to the result. Mandatory requirements determine eligibility independently of the score. Applications retain the CV version, contact details, and assessment used at submission.

Start with the [demo guide](docs/demo-guide.md) or the [setup and verification guide](docs/reproduce-demo.md). The [database diagram](docs/database/er-diagram.html) documents the schema, and [implementation status](docs/implementation-status.md) records automated checks and remaining manual acceptance.

## Run locally

Use PowerShell 7 from the project root after completing [environment setup](docs/environment-setup.md):

```powershell
.\scripts\start-mysql.ps1
.\scripts\run.ps1 -SeedDemo
.\scripts\run.ps1
```

The seed command creates missing fictional accounts and catalog entries without resetting existing passwords or profiles. For a demo company without jobs, it adds four fictional postings across two categories and verifies the company; repeated seeding preserves existing jobs. Each demo account uses password `SmartHireDemo!21`:

Seeded approved jobs also need a compatible active policy. If an admin has set skills weight to zero, restore a positive skills weight before seeding the skills-only demo postings; incompatible job fixtures roll back without changing existing jobs.

| Role | Fictional name | Email |
| --- | --- | --- |
| Student | Arafat Hasan | student@smarthire.example |
| Company | Dhaka Software Demo | company@smarthire.example |
| Admin | Farhana Rahman | admin@smarthire.example |

These public credentials are for the local fictional demonstration only. Student and company accounts may also register in the UI. Admins verify companies and moderate jobs through Jobs and administration. Students browse approved jobs through Find jobs. After confirming qualifications in My CV, open Job matches for recommendations. Select Open selected job for the full posting or Why this match? for a simple explanation; Show score details reveals the calculation breakdown. Refresh after changing qualifications; opening a posting or requesting an explanation rechecks current access.

After confirming a CV, students wait for admin approval of that exact version before applying from job details. **My CV** shows Pending/Approved/Rejected and any rejection reason. Admins use the separate **CV review** button to inspect original CVs and confirmed qualifications, then approve or reject. Confirmed replacements and qualification edits require fresh approval; existing unapproved CVs are Pending. See the [CV approval rules and acceptance guide](docs/acceptance-cv-approval.md).

Students track/withdraw through **My applications**. Companies use **Applicants** to filter by job/status, review submitted qualifications and original CVs, shortlist, hire or reject. The first application freezes posting requirements. Current-policy rankings are recalculated from submitted evidence; original submission scores remain separate.

Ctrl-select two jobs in Job matches to compare them. Companies select a posting in their job list and choose **Attach circular**, or use the **Job circular** section in job details. They can preview editable suggestions from Description or the attached circular in the job editor. Admins configure **Matching weights** from Jobs and administration. Every policy activation validates approved open jobs and jobs with applicants before changing the active version.

The launcher reads the existing private `.local/database.json` and writes `.local/application.properties`. Neither credentials nor portable tools belong in Git. The database must already exist; startup creates only missing tables from `src/main/resources/db/schema.sql`. No reset or destructive schema migration runs. For local tool installation details, see [environment setup](docs/environment-setup.md).

All roles have **Notifications** with persistent read state and an Overview chart. Admins use **Accounts** to enable/disable users and **Application monitoring** for read-only filtering. Disabling preserves records. Sample CVs under `output/pdf` demonstrate 90.48% but ineligible, 42.86% with skill gaps, and 100% eligible for the controlled posting in the demo guide; these scores depend on its exact requirements and default weights.

## Build and verify

```powershell
. .\scripts\dev-env.ps1
mvn test
mvn '-Dsmarthire.integration=true' test
mvn package
.\scripts\verify.ps1 -Offline -CleanDatabaseSmoke
```

The ordinary test run does not require MySQL. The explicit integration run needs MySQL and `.local/application.properties` (created by the launcher); it uses `smarthire_test`, creates random test accounts, and removes those accounts afterward. It checks identity, publication, CV/document transactions, matching, immutable applications, races, permissions, policies, circulars, comparison, notifications and oversight. Workflow tests restore the prior active test policy and remove the versions they created. Tests do not reset shared tables. The separate opt-in clean smoke needs private admin configuration; it creates/drops only its own temporary `smarthire_smoke_<uuid>` database and verifies a fresh end-to-end journey.

The JAR is not a fat JAR. Use `scripts/run.ps1` to build the runtime classpath and launch it. In NetBeans, open the root Maven project and run `smarthire.Main` with the working directory set to the project root after generating local configuration through the launcher. Dependencies may need downloading on a new machine; an existing dependency cache supports offline builds.

## Structure and checks

- `identity`: authentication, session ownership, role checks, profile validation, PBKDF2 password hashing.
- `storage`: local configuration, schema initialization, prepared JDBC statements and transactional registration.
- `jobs` / `skills`: publication services, structured requirements, and shared skill normalization.
- `documents`: shared parsing, immutable copies, reference-aware cleanup, and recovery; see [the storage contract](docs/document-storage.md).
- `cv`: conservative qualification suggestions, confirmed reviews, immutable versions, and student-only access. A replacement becomes current only after confirmation; editing a review creates another version of the same original document.
- `matching`: pure explained scoring under the current versioned policy, student recommendations, and separate comparison text similarity. Eligibility is independent of the percentage; results are recalculated on request without a cache.
- `applications`: transactional submissions, immutable evidence/contact/assessment references, student tracking, recruiter access, per-job ranking and permitted status transitions.
- `workspace`: owned notification inbox/read state, admin enable/disable and read-only monitoring, scoped dashboard counts. Notification source writes use their existing JDBC transaction.
- `ui`: resizable Swing forms and job workflows; slow operations run through `SwingWorker`.

Manual acceptance: sign in as each role; save a profile and reopen the app; register a student and company; reject duplicate emails and invalid inputs; sign out and switch accounts. Stop MySQL and confirm startup gives an actionable error and a retry button. Check keyboard navigation and screen fit on the demo laptop.

The full domain rules remain in [the specification](docs/spec-draft.md) and [CONTEXT.md](CONTEXT.md). Live Swing navigation, keyboard/file dialogs and laptop display-scaling acceptance remain pending; see delivery status. Back up `.local/documents` with the database; only one app instance may open the document store. Scoring policies and historical CV/circular versions are retained for evidence and recovery.
