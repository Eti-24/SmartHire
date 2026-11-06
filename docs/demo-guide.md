# Presenting SmartHire

## Start

Follow the [README](../README.md) to configure MySQL, seed the demo and launch SmartHire from the release ZIP or source. On a fresh database, the seed creates eight fictional accounts and eight postings, plus shared skills and categories. It preserves existing passwords, profiles, CVs, applications and decisions. Close an older instance before launching.

The [demo accounts list](../demo%20data/Demo%20accounts.md) has the sign-in details and sample CVs. Use an ordinary account registration for additional fictional students. Use a fresh posting when repeating the demo. One student can apply to a given job once, including after a withdrawal. A disposable fresh-database run is available through `scripts/verify.ps1 -CleanDatabaseSmoke -Offline`.

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

1. Confirm the mandatory-gap sample. It starts Pending; an Apply attempt must refuse pending approval. Sign in as admin → CV review → Review CV version → inspect its original/qualifications → Approve this version. Return as student to Job matches → Why this match? Show the simple explanation and failed Java check despite 90.48%. Open selected job and attempt to apply; it must still refuse for the missing mandatory skill without creating an application.
2. Replace with the skill-gap sample. Refresh matches to show 42.86%, eligibility and the SQL/communication/teamwork gaps. Ctrl-select two visible jobs and choose Compare two jobs. Explain that similarity is separate from the structured score.
3. Replace with the eligible sample, confirm and obtain admin approval of this new version through CV review, then apply to the controlled posting. Each confirmed replacement or edited review needs its own approval. Open My applications and note the saved original score and policy.
4. Replace the current CV with the skill-gap sample again and edit the student's contact profile. Existing application contact details, original assessment, submitted qualifications and downloaded original must remain unchanged.

## Company and admin journey

1. As company, open Applicants, filter by the posting ID, inspect the immutable submitted qualifications and original assessment, and save the submitted CV. Its bytes must be the eligible sample even though the student replaced the current review.
2. Shortlist, then hire the application. Verify that further decision/withdrawal buttons are unavailable on the hired application. Open **Hiring history** as company to see the hire and its application link. As student, verify that the one-vacancy posting appears in **Hiring history** and disappears from **Find jobs** and **Job matches** when reopened. The student's own outcome remains in **My applications**.
3. As student/company, read the matching saved alerts in Notifications. Mark one read, sign out/restart and confirm the read state stays saved. The role overview shows relevant counts and the application-status chart; choose Overview or Refresh to reload current data.
4. As admin, open Application monitoring, combine job/company/student IDs and status filters, and view the original assessment. This view cannot change recruiter decisions. Use Accounts to disable a fictional student/company and demonstrate blocked login/actions, then enable it again. Do not disable a participant until its journey is finished unless intentionally demonstrating this failure.
5. As admin, change valid matching weights and show that current recommendation/ranking labels use the new version while original submission scores remain saved. To demonstrate two different original policies on one posting, use two fictional student accounts and submit the second before closing the posting. See acceptance-slice-05b.md for the precise sequence.

## Feature expansion walkthrough

1. In **Find jobs**, select a posting and choose **Save job**. Open **Saved jobs** and check that it can be opened or removed. After the posting fills, refresh to see **Filled**. If the student already applied, **View application** opens their retained submission.
2. In **Job matches**, open **Why this match?** and choose **What to improve**. Compare the mandatory-gap sample with the eligible skill-gap sample. Open **My CV** from the dialog and use its back action to refresh recommendations.
3. On a shortlisted fictional application, use the company **Interview** tab to propose a future time. Sign in as the student, open **Interviews**, accept, and request another time. Confirm the replacement. In the company **Feedback** tab, save a private note. A scorecard becomes available after a confirmed interview ends.
4. Publish a fresh job with two vacancies and submit two eligible fictional applications. Hire one and check that the job stays visible to students. Hire the second and check that it leaves discovery and enters student Hiring history. Company Hiring history shows both hires.
5. In admin **Activity log**, filter for a job moderation, a CV decision, or a data export. Use fictional records for any erase demonstration. The log retains the erase outcome after the target disappears from Data management.

## Local roadmap walkthrough

1. As a student, open **My CV** and choose **Download CV template (.docx)**. Save it, replace the bracketed prompts in Word, and upload the edited file. Confirm the suggested qualifications and approve the new version through **CV review**.
2. As a company, choose **Create job** and download the circular template. Enter an application deadline and its time zone. Submit the job, attach the edited circular, and approve the posting as an admin. Check its search filters and the behavior at the deadline.
3. As a student, use **More filters** and **Save this search** in **Find jobs**. Approve a new matching posting as admin. Open the neutral notice from the student inbox and check that a filled, expired or withdrawn posting no longer opens from that link.
4. Submit an application and shortlist it as the recruiter. Open **Timeline** from both accounts. In **Interview**, propose a start and end time with a time zone, then accept as the student. Request another time, confirm the replacement, and cancel as the recruiter. Check that both participants can see the revisions in SmartHire.
5. Open **Reports** as the company and the administrator. Compare conversion and average times for the same posting and date range. Move the cohort dates beyond the applications and check that the current capacity remains visible.
6. Use a disposable fictional account for **Change password** and **Recovery codes** in **My profile**. Save the generated codes somewhere private, sign out and reset through **Recover with a saved code**. Check that a used code and the other codes from that set are refused.

## Failures to demonstrate safely

Use `src/test/resources/documents/textless.pdf` or `encrypted.pdf` to show readable import errors. An existing reviewed CV must remain available after a refused upload or cancelled review. Use another company's account to show that it cannot open someone else's applicant records. Do not manually delete managed files in the live demo to simulate missing bytes; automated integration tests exercise that case in isolated storage.

## Presenter checks still requiring the laptop

Verify Tab/Shift+Tab, selection and buttons at the laptop's display scaling; long requirement tables and side-by-side comparison; Notifications message dialogs; account/filter controls; file chooser save/overwrite flow; light styling and chart labels; navigation during document work; and startup retry after stopping/restarting MySQL. Avoid stopping MySQL during a write. The Windows automation runtime did not initialize during delivery, so these interactive checks are pending rather than inferred from service tests.
