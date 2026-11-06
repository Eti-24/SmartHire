# Admin approval of student CVs

## Rules

- Upload → student corrects qualifications → Confirm → Pending admin approval. Drafts are not listed for admin review.
- Admin has a separate **CV review** navigation button alongside job administration. It defaults to Pending, with filters for Approved, Rejected or all current submissions. Account name/email, confirmation time and active state identify each submission.
- Review CV version shows the exact version, original filename, extracted text and confirmed qualifications. Save original CV exports the immutable bytes for inspection in a PDF/Word viewer. Approval checks saved-byte integrity again.
- An active admin can approve/reject only the active student's current Pending version. Rejection requires a reason (1–1000 characters). Both outcomes save reviewer/time, send a student notification, and become visible in My CV. Repeat/conflicting decisions are refused; the first committed decision wins.
- A replacement or qualification-only edit creates another Pending version. The old decision stays in version history. Rejected students correct and confirm a new version. Existing legacy versions without an approval record count as Pending. An unconfirmed/cancelled draft leaves the old approved version usable.
- New applications use only the approved **current** CV and still require readable bytes and every existing job/company/eligibility check. A 100% score does not bypass approval. Preview matching and comparison do not require approval.
- Earlier applications keep the submitted CV, qualifications, contact snapshot, original assessment and recruiter status even after a replacement/rejection. No records are deleted by moderation. Companies cannot inspect CVs that have not been submitted to their jobs; students cannot review or approve another student's CV.
- Submission, approval and confirmation serialize on the student account lock. A replacement committed first blocks stale approval/new application. An application committed first keeps the approved original version even if a replacement follows. Admin approvals also share the catalog mutex used by account disablement and application transactions.

## Manual journey

1. Restart the rebuilt app to initialize the additive approval table. As student, open My CV, upload/confirm a sample and observe Pending. Job matches remains available; an eligible Apply attempt must explain the approval requirement.
2. Sign in as admin → CV review → select the student's CV → Review CV version. Inspect text/qualifications and save/open the original. Approve this version. Check the approved filter and Overview's CVs awaiting approval count.
3. As student, Refresh approval status in My CV; verify Approved and the saved notification. Apply to an eligible job. Another student's/company's account must not be able to approve or inspect this submission through the admin service.
4. Edit confirmed qualifications or replace the CV. The new version must be Pending while the earlier approved version remains in history. Applying to a different job must fail until this new version is approved.
5. Reject the new version with a correction reason. Confirm student feedback and notification, then correct/resubmit and approve the resulting new version. Existing applications must still show/download the original approved evidence.
6. With an old admin detail page open, confirm a replacement as student. An attempted decision on the old version must request refresh. Two admins deciding the same Pending version must not overwrite each other.

## Automated evidence

`CvApprovalIntegrationTest` covers Pending and legacy fail-closed behavior, actual original-byte/admin inspection, reviewer/time persistence, rejection validation/resubmission, draft cancellation, replacement blocking with preserved application snapshots, roles/ownership/disabled accounts, stale versions/missing originals, decision/notification rollback, duplicate decisions and separate-connection races for both approval/replacement and application/replacement commit orders. Existing workflow fixtures explicitly obtain real service approval before application tests. The clean-database smoke now includes admin approval and Pending refusal.

Live Swing navigation, file chooser behavior and laptop display scaling remain unverified in this environment; service tests are not a claim that these visual checks ran.
