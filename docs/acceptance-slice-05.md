# Slice 05: applications and recruiter review

1. Start MySQL and SmartHire. Use an active, verified company and an approved open job with a mandatory skill. Confirm one student's reviewed CV without that skill: attempting **Apply with my reviewed CV** from job details must fail even when other qualifications produce a high score.
2. Confirm a second student's CV with the mandatory skill, open the posting from Find jobs or Job matches, and apply. **My applications** shows the submission and status. A second submission to the same job must fail.
3. As the owning company, open **Applicants**. Filter by job ID and status. Applicants are ordered within each job by current-policy score, then submission time and stable application ID. Open a submission to see original assessment, submitted contact details, reviewed qualifications, extracted CV text, and **Save submitted CV**.
4. Update the student's profile, replace their CV with a different file, and confirm new qualifications. The application must keep the old contact details, qualifications, original score/policy/explanation, and original downloadable bytes after restart.
5. As the company, shortlist then hire or reject. As the student, withdraw while Applied or Shortlisted. Hired, Rejected and Withdrawn are terminal. Withdrawal still prevents resubmission and still freezes job requirements.
6. After the first submission, editing/resubmitting the job must fail. Closing it remains allowed and stops new applications while preserving existing review/download access. An active student retains their own application history after company disablement; disabled accounts cannot act.
7. Another student/company and an admin must not obtain or change the submission through this service. Admin read-only oversight follows in slice 06. Missing managed bytes retain history but block download and new submissions using that CV.

The application row references the immutable CV version using a composite ownership foreign key. It snapshots contact details and the original policy, unrounded score, and complete calculation explanation. Frozen job requirements retain the submission basis. Only application status is updated.

Transactions acquire the existing catalog mutex, lock the job row, recheck current job/company state, lock/recheck the student, read the current CV and verify its managed bytes before insertion. Edits and closure use the same catalog and job locks. Uniqueness on `(student_id, job_id)` provides a database backstop. Race tests use separate connections and exercise submission-first (edit rejected) and edit-first (job pending, submission rejected). Injected failure after writes must roll back the submission and leave editing available.

Interactive keyboard/navigation, download dialogs, screen fit and the complete three-role journey remain manual acceptance checks; automated service tests do not establish visual acceptance.
