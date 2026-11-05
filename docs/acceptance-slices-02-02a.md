# Acceptance: slices 02 and 02a

Implemented sequentially on 2026-11-05. Automated checks pass; user acceptance is requested after both slices. Visual Swing walkthrough and laptop screen-fit checks remain unverified because the computer-use runtime failed to initialize.

## Start the demonstration

From PowerShell 7 in `E:\SmartHire`:

```powershell
.\scripts\start-mysql.ps1
.\scripts\run.ps1 -SeedDemo
.\scripts\run.ps1
```

The additive seed creates missing catalog entries and, for the demo company when it has no jobs, four fictional Graduate analyst postings. It verifies that company and creates two approved jobs in Software and Business, one pending Software job, and one closed Software job. Repeating the seed preserves existing jobs and moderation decisions. All three demo accounts use `SmartHireDemo!21`.

## Slice 02: publish an approved job

1. Sign in as `student@smarthire.example`. Open **Find jobs**. Search for `Graduate` and select **Software**: the initial seed shows only the approved Software posting. Pending and closed jobs must not appear. Search an unrelated title: no results. **Clear** restores the approved Software and Business postings.
2. Sign out and register a new company. Open **Jobs and administration**, create a job with a title, description, category, location, salary range, and one SKILL requirement (Java, REQUIRED, mandatory checked). Submission must explain that company verification is needed.
3. Sign in as `admin@smarthire.example`. Open **Jobs and administration → Verify companies**, select the new company, check **Verified company**, and save. Categories can be added/renamed under **Categories**. Technical/soft skill names and aliases can be maintained under **Skills**; stable IDs are retained.
4. Sign in as the new company. Submit the job. Its status is PENDING. Sign in as the admin, open it, review requirements, and **Approve**. The student can now search for and open it.
5. As its company, use **Edit and resubmit**. The posting returns to PENDING and disappears from student results. As admin, **Reject** with a reason. The company can read the reason, edit, and resubmit for approval.
6. Approve it again, then close it as the owning company. The student must no longer see it; the company retains the posting. Closed jobs cannot be edited.
7. Optional checks: remove verification to hide a company's approved jobs; restore verification to show them again. Try duplicate skill aliases, reversed salary ranges, empty requirements, and positive experience without relevance tags. Each must give a useful validation message.
8. Confirm keyboard navigation, scrolling, and screen fit on this laptop. Reopen the application to confirm persistence. Review the existing sign-in/profile/logout flows as well.

Structured requirements support skills, a minimum degree, an optional degree field, relevant experience months and skill tags, project skills, and named certifications. Required priority and mandatory eligibility are separate. The demo degree order is SECONDARY < DIPLOMA < BACHELOR < MASTER < DOCTORATE; this is a demonstration convention, not a claim of universal degree equivalence. Field and certification labels use Unicode normalization, case folding, and collapsed whitespace; no inferred academic equivalences are applied.

Requirements remain editable until applications are implemented in slice 05, where application creation and requirement freezing must share the job lock. No application submission workflow is claimed in this slice.

## Slice 02a: read and store a document

This slice delivers the shared Java adapter, reference storage, and startup recovery. CV upload/review screens belong to slice 03, and circular attachments belong to 05b.

Inspect the fictional PDF/DOCX files in `src/test/resources/documents`. The PDF and DOCX contain a fictional student's text; the DOCX includes a table. Separate fixtures cover textless and encrypted documents. Run:

```powershell
. .\scripts\dev-env.ps1
mvn -o '-Dtest=ManagedDocumentsTest' test
mvn -o '-Dsmarthire.integration=true' '-Dtest=DocumentIntegrationTest' test
```

These checks verify actual parsing, immutable copies after source replacement, unsupported/malformed/encrypted/textless/oversized failures, interrupted copies, streaming size enforcement, rollback cleanup, recovery, exclusive store access, missing/changed referenced files, database outages, and uncertain commit outcomes. The database check uses only `smarthire_test` and a temporary document directory.

The application store lives in `.local/documents`. Startup reconciles abandoned managed files before accepting work. Referenced files are never removed by reconciliation; missing or changed bytes are reported and reads fail while the database history stays intact. Back up this directory together with the database. Only one application instance may open a given store.

## Verification recorded

- Slice 02: full suite passed with 12 unit tests and 4 MySQL integration tests; Maven packaging and explicit demo seeding passed.
- Slice 02a: full suite passed with 21 unit tests and 5 MySQL integration tests (26 total, no failures or skips with integration enabled).
- Subsequent job normalization checks were run after the final form refinement.
- Visual/manual acceptance is pending, not implied by automated service checks.

After reviewing these steps, accept both slices or identify corrections before development proceeds to slice 03.
