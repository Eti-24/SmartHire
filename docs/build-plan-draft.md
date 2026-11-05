# SmartHire build plan for review

Status: draft, not published implementation tickets. Product scope is confirmed; proposed ticket granularity, blocking edges, and test approach await review.

## Proposed local tracker

Use local Markdown because this folder currently has no Git repository or remote. After tracker confirmation, publish the specification under `.scratch/smarthire/spec.md` and each approved ticket as a separate numbered file under `.scratch/smarthire/issues/`. Apply `Status: ready-for-agent`; a ticket may start only when all its blockers are complete. Do not require GitHub setup to begin the demonstration build.

## Proposed vertical slices

### 01: Sign in and navigate by role

Blocked by: None.

Deliver a runnable Java 21 Maven/Swing application connected to MySQL, fictional seeded accounts, student/company registration, hashed passwords, role-specific white navigation, profile editing, and logout. Validate credentials, duplicate registration, role access, persistence across restart, and useful missing-database errors. Include minimal setup instructions so this slice can be demonstrated independently.

### 02: Publish an approved job

Blocked by: 01.

Deliver company verification, recruiter job forms with structured requirements, admin approval/rejection, approved-job browsing, and company job closing. Include salary/location/category fields and supporting admin category/skill maintenance. Verify ownership, company verification, job visibility, validation, and requirement edits returning to approval. Extend schema/seed data and UI together.

Own the shared skill vocabulary and normalization rules for job requirements and CV review, including technical and soft skills, curated aliases, and experience relevance tags. Provide a fixture of phrases and expected canonical skills that distinguishes Java from JavaScript and C from C++. Use the structured form for the first publication. Attachments and requirement suggestions follow in 05b.

Deliver student job search by title and filtering by category. Seed jobs in two categories, including matching titles on pending and closed postings. Verify that combining a title query with a category returns only matching approved, open jobs from active, verified companies. Check a query with no results and that clearing the filters restores the visible jobs.

### 02a: Read and store a document

Blocked by: 01.

Share one reader and managed store between CVs and job circulars. Given a local file, return extracted text and an immutable managed document identifier, or a typed validation/storage failure. Verify with a real text PDF, a DOCX, a textless file, and an interrupted copy. Define reference commit, rollback cleanup, and startup reconciliation as specified in CV processing and storage.

### 03: Upload and review a CV

Blocked by: 02, 02a.

Deliver student upload of English text-based PDF/DOCX files up to 5 MB, local managed copies, extracted text and qualification suggestions, editable review, and a saved reviewed CV profile. Reject unsupported, malformed, oversized, and image-only inputs with clear feedback. Exercise real fixtures and confirm parsing does not freeze navigation. Preserve versions for later applications.

Use the vocabulary from 02 for both CV and project skills. Run its shared phrase fixture through CV extraction and compare the resulting canonical skills with the expected IDs. Retain editable project evidence and dated, skill-tagged experience entries. Exercise replacement rollback and confirm the old review remains usable.

### 04: Explain job matches

Blocked by: 02, 03.

Deliver weighted job recommendations, explicit eligibility, category contributions, matched/missing skills, and rule-based suggestions. Refresh results when the reviewed CV or approved requirements change. Test aliases, absent categories, overlapping experience months, caps, and high-score mandatory failures. Use the default versioned policy for the first journey.

Use deterministic ordering and a clear no-reviewed-CV state. The accepted ranking rule compares immutable submitted qualifications under a common current scoring policy, with original submission scores preserved separately and recalculated rankings clearly labeled.

### 05: Apply and review applicants

Blocked by: 04.

Deliver eligible application submission with immutable evidence, student tracking/withdrawal, recruiter ranking/filtering, CV viewing/downloading, and shortlisting/hiring/rejection. Enforce one submission per student/job, ownership, terminal states, job closure, and requirement freezing after the first application. Verify that the committed submission retains its CV version, reviewed qualifications, policy version, original score, and explanation after the student updates their profile or CV.

Use this slice as the first demonstration checkpoint for the five-hour target. The first end-to-end acceptance run uses a seeded admin, an active verified company, and one approved job with a mandatory skill. Show a high-scoring student blocked by that skill, then submit a second, eligible student's application. Replace the second student's CV and verify that the recruiter downloads the originally submitted bytes. Exercise both orderings of a first submission racing a requirement edit using separate MySQL connections.

### 05b: Compare jobs, suggest requirements, and change scoring policy

Blocked by: 05.

Deliver two-job comparison, separately labeled TF-IDF/cosine similarity, optional circular upload/view/download, and editable suggestions from pasted text or attachments using 02a. Verify circular permissions and that suggestions cannot silently introduce mandatory requirements. Deliver admin weight configuration and policy-based match refresh. Exercise policy rejection for open jobs and closed jobs with applicants. Submit one application, activate a valid weight change through the admin workflow, then submit another to the same job. Verify the recruiter ranking uses the new policy for both applicants and that each submission retains its original policy, score, and explanation.

### 06: Complete notifications and oversight

Blocked by: 05.

Deliver persisted in-app approval/application notifications with read state, basic admin account management, and role-relevant dashboard counts/basic charts. Verify notifications reflect committed changes, account management preserves application history, and students/companies see only their permitted information. Approvals and application services from earlier slices become notification event sources here.

Include admin read-only application monitoring with job/company/student/status filters, technical/soft skill maintenance, and disablement checks that block future actions without deleting history.

### 07: Reproduce and present the complete demo

Blocked by: 05b, 06.

Deliver the complete ER diagram, final schema/fictional seeds and sample CVs, NetBeans/Maven setup and run instructions, clear database-test instructions, and a scripted three-role demonstration. Run the relevant automated checks and clean-database smoke test; verify light UI navigation, background processing, comparison, downloads, failure feedback, and restart persistence. Fix integration failures and remove placeholders. Record exactly which checks ran and any limitations.

Document MySQL Workbench setup, first-build dependency downloads versus offline runtime, managed-file/database portability, and actual checked tool versions. Provide an easily viewable ER export. Verify screen fit on the laptop and include sample outcomes for a high-score ineligible candidate, a skill gap, and an eligible application. Keep local version control distinct from the local ticket tracker; do not publish to GitHub without a configured destination.

This final slice integrates and packages working features; it must not defer all testing, validation, documentation, or styling until the end. Every earlier slice must already be demonstrable and tested at the appropriate level.

## Test approach for approval

Use application-service operations as the main test boundary for permissions and workflows, focused scoring tests for exact arithmetic, and isolated MySQL integration checks for persistence/transactions. Verify actual CV fixtures and manually smoke-test the Swing workflows. No brittle tests tied to component internals.

## Setup still to confirm

- Tracker recommendation: local Markdown, as described above.
- The triage skill is installed. Setup asks whether to retain the defaults: needs-triage, needs-info, ready-for-agent, ready-for-human, wontfix.
- Follow the required Plan, Implement, Validate loop in `AGENTS.md` for every slice.
- Single-context domain layout already exists: CONTEXT.md and docs/adr/.

## Review criteria

Confirm that the slices are neither too coarse nor too fine, their blockers are genuine prerequisites, and the proposed service-level testing matches expectations. Merge or split slices before publishing one ready-for-agent file per ticket.
