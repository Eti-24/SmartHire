# Presenting SmartHire

## Start

From the project root in PowerShell 7, start MySQL with `scripts/start-mysql.ps1`, then run `scripts/run.ps1 -SeedDemo`. This explicit additive seed creates three fictional accounts, shared skills/categories and four jobs if the demo company has no jobs. It never resets existing passwords, profiles, CVs, applications or decisions. Then run `scripts/run.ps1` to open the app. Close an older instance first.

The accounts and public demo password are in README.md. Use an ordinary account registration for additional fictional students. Use a fresh posting when repeating the demo; one student cannot apply to the same job twice, even after withdrawal. The app has no destructive reset button. A disposable fresh-database run is available through `scripts/verify.ps1 -CleanDatabaseSmoke -Offline`; it never resets `smarthire` or `smarthire_test`.

## Prepare the assessment posting

As admin, verify the demo company, check that the catalog includes Java, SQL, communication and teamwork, and activate the default weights: skills 40, education 20, experience 15, projects 15, certifications 10. An existing incompatible posting can prevent a weight change; the UI lists its ID. Do not overwrite historical data to force the demo.

As company, create a new Software job called **Demo Java graduate developer**, location Dhaka, salary 30000–50000. Enter a short fictional description and these exact structured rows:

| Kind | Value | Priority | Mandatory | Months |
| --- | --- | --- | --- | --- |
| SKILL | Java | OPTIONAL | yes | 0 |
| SKILL | SQL | REQUIRED | no | 0 |
| SKILL | communication | REQUIRED | no | 0 |
| SKILL | teamwork | REQUIRED | no | 0 |
| DEGREE | BACHELOR | OPTIONAL | no | 0 |

There is no field, project, certification or experience requirement in this controlled example. Required/optional changes score weight; only the Mandatory flag blocks applying. Attach `output/pdf/demo-job-circular.pdf` from the company list's **Attach circular** button if demonstrating documents. Attach before approval/applications; suggestions are optional and must not change these rows if the exact sample scores are being presented. The circular describes a separate fictional role, so use these structured rows as the assessment authority.

Sign out and approve the posting as admin. Create/approve a second fresh posting with at least one different structured skill to demonstrate two-job comparison. Note both IDs.

## Student journey and expected results

Use My CV to upload each sample below, inspect the extracted text, confirm the suggested degree/skills, and save the review. These are fictional English text PDFs, provided in both `output/pdf` and `src/test/resources/demo`. Reviewing the extracted data remains a required step.

| Sample | Fictional name | Reviewed skills | Score for the controlled posting | Eligibility |
| --- | --- | --- | --- | --- |
| demo-cv-mandatory-gap.pdf | Tanvir Ahmed | SQL, communication, teamwork | 90.48% | Fails mandatory Java; Apply must refuse |
| demo-cv-skill-gap.pdf | Nusrat Jahan | Java | 42.86% | Eligible; explanation identifies the other three skill gaps |
| demo-cv-eligible.pdf | Arafat Hasan | Java, SQL, communication, teamwork | 100.00% | Eligible |

Each sample has Bachelor in Computer Science. Only skills and education are active in this posting, so their weights redistribute to 2/3 and 1/3. Exact fractions are 19/21, 3/7 and 1. Similarity percentages vary with the other job's description and never alter these scores or eligibility.

1. Confirm the mandatory-gap sample. It starts Pending; an Apply attempt must refuse pending approval. Sign in as admin → CV review → Review selected CV → inspect its original/qualifications → Approve this CV version. Return as student to Job matches → Why this match? Show the simple explanation and failed Java check despite 90.48%. Open selected job and attempt to apply; it must still refuse for the missing mandatory skill without creating an application.
2. Replace with the skill-gap sample. Refresh matches to show 42.86%, eligibility and the SQL/communication/teamwork gaps. Ctrl-select two visible jobs and choose Compare two jobs. Explain that similarity is separate from the structured score.
3. Replace with the eligible sample, confirm and obtain admin approval of this new version through CV review, then apply to the controlled posting. Each confirmed replacement or edited review needs its own approval. Open My applications and note the saved original score and policy.
4. Replace the current CV with the skill-gap sample again and edit the student's contact profile. Existing application contact details, original assessment, submitted qualifications and downloaded original must remain unchanged.

## Company and admin journey

1. As company, open Applicants, filter by the posting ID, inspect the immutable submitted qualifications and original assessment, and save the submitted CV. Its bytes must be the eligible sample even though the student replaced the current review.
2. Shortlist, then hire the application. Verify that further decision/withdrawal buttons are unavailable. The job cannot be edited or have its circular replaced after any application. Close it when finished; student application history remains available.
3. As student/company, read the matching saved alerts in Notifications. Mark one read, sign out/restart and confirm the read state stays saved. The role overview shows relevant counts and the application-status chart; choose Overview or Refresh to reload current data.
4. As admin, open Application monitoring, combine job/company/student IDs and status filters, and view the original assessment. This view cannot change recruiter decisions. Use Accounts to disable a fictional student/company and demonstrate blocked login/actions, then enable it again. Do not disable a participant until its journey is finished unless intentionally demonstrating this failure.
5. As admin, change valid matching weights and show that current recommendation/ranking labels use the new version while original submission scores remain saved. To demonstrate two different original policies on one posting, use two fictional student accounts and submit the second before closing the posting. See acceptance-slice-05b.md for the precise sequence.

## Failures to demonstrate safely

Use `src/test/resources/documents/textless.pdf` or `encrypted.pdf` to show readable import errors. An existing reviewed CV must remain available after a refused upload or cancelled review. Use another company's account to show that it cannot open someone else's applicant records. Do not manually delete managed files in the live demo to simulate missing bytes; automated integration tests exercise that case in isolated storage.

## Presenter checks still requiring the laptop

Verify Tab/Shift+Tab, selection and buttons at the laptop's display scaling; long requirement tables and side-by-side comparison; Notifications message dialogs; account/filter controls; file chooser save/overwrite flow; light styling and chart labels; navigation during document work; and startup retry after stopping/restarting MySQL. Avoid stopping MySQL during a write. The Windows automation runtime did not initialize during delivery, so these interactive checks are pending rather than inferred from service tests.
