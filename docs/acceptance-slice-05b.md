# Slice 05b: comparison, circulars and versioned scoring policies

Slice 05b began after slice 05 passed its complete 55-test/package checkpoint. It reuses confirmed CVs, frozen application evidence, managed document storage, and publication permissions.

## Student comparison

1. Confirm a CV and open **Job matches**. Ctrl-select or Shift-select two different jobs and choose **Compare two jobs**.
2. Check side-by-side company, location, salary, structured score, eligibility, active-category results, mandatory gaps and separately labeled text similarity. Both assessments use one CV review and one policy. Missing categories say Not required.
3. Close or change one job to pending, then attempt comparison through its old row. Expect an access error. Selecting one job still supports **Open selected job** and **Why this match?**.

Text similarity uses only extracted CV text and job descriptions. A shared three-document corpus (CV and both jobs) uses raw term frequency, smoothed inverse document frequency `log((N+1)/(df+1))+1`, and cosine normalization. NFKC/lowercase word tokens retain language suffixes such as `C++`. Empty vectors return zero. This is wording overlap, separate from structured score and eligibility; it does not change ranking or allow applications.

## Circulars and requirement suggestions

1. As the verified owning company, create a structured job. Select it in **My jobs** and choose **Attach circular**, or open its details and use **Attach / replace circular** in the **Job circular** tab. Choose a readable PDF or DOCX up to 5 MB. A job can remain entirely structured with no attachment.
2. Attaching or replacing returns the job to pending approval. The owning company and an active admin can **View circular** (extracted text) or **Save circular** (original bytes) before publication. Another company cannot access it.
3. As a student, opening/viewing/downloading requires a currently approved open job from an active verified company. Approval enables access; closing, pending edits, company disablement or verification removal revokes it even through an old result.
4. From **Edit and resubmit**, paste requirement text into **Description** on **Posting information**. On the **Requirements** tab, choose **Suggest from description** or **Suggest from circular** for an attached posting. Preview the proposals and notes. Accept to add new rows to the editable form, then correct or remove any row before submitting.
5. Suggestions recognize curated technical/soft skills, conservative degree/field phrases, explicitly labeled certificates, and explicit numeric months/years of experience. Suggested relevance tags must be reviewed. Unknown/ambiguous material remains manual work. Existing canonical requirements are not overwritten or duplicated by preview acceptance.
6. Every suggestion starts optional and nonmandatory, even when the text says must/required. Only the recruiter-confirmed structured form controls priority and mandatory flags. Saving still needs admin approval.
7. Textless/malformed/unsupported/oversized files fail without replacing the previous attachment. Failure after metadata writes rolls back both the attachment pointer and metadata; the pending file is cleaned up safely. Once an application exists, circular changes are frozen with the posting requirements.

Each committed circular version is an immutable managed document with a job ownership record; the current pointer changes atomically with metadata and pending status. Historical versions remain referenced for recovery. The same parsing, hash/integrity checks and startup reconciliation as CVs apply. Moving a source file does not affect a saved copy; missing managed bytes produce an unavailable error.

## Admin policy changes and recruiter ranking

1. As admin, open **Jobs and administration → Matching weights**. Enter nonnegative integer weights with a positive total. They need not sum to 100; each job redistributes the active-category weights.
2. Activate a valid change. Expect a new immutable policy version. Reopen/refresh student matches and recruiter Applicants to recalculate with that policy. Submitted CV evidence and original assessments remain unchanged.
3. For an approved skills-only job, attempt zero skills weight with positive education weight. Activation must list the incompatible job ID and leave the prior policy active.
4. Submit an application to a skills-only job, close that job, and repeat the incompatible policy attempt. It must still fail because historical applicants need a common valid ranking basis.
5. Create a pending skills-only job, activate an education-only policy while no protected job is incompatible, then try approving it. Approval must fail. Approval and activation lock the same current-policy record (after the existing catalog mutex), so neither concurrency ordering can publish an unscorable posting.
6. For a job with skills and education requirements, submit one student under the old policy. Change to a valid policy and submit another. The recruiter sees both ranked using the same current policy against their immutable submitted qualifications and frozen job requirements. **Original assessment** preserves each submission's original policy, unrounded score and explanation. Ties use submission time then stable application ID.
7. A mandatory requirement with zero scoring weight still blocks eligibility and submission. A displayed score of 100% never overrides a failed mandatory check.

## Validation and remaining manual checks

Pure tests cover conservative nonmandatory suggestions, token boundaries and experience suggestions, identical/disjoint/empty similarity text and known cosine values. MySQL service tests cover policy persistence/permissions/rejection, both approval/activation race orderings, cross-policy submissions/rankings, unchanged originals, perfect-score mandatory failure, circular permissions/reapproval/byte exports/freeze/rollback/missing bytes, and comparison access/basis.

Latest counts and the exact build command are in `implementation-status.md`. Interactive dialogs, tab/keyboard navigation, long comparison cells, laptop screen fit and the complete three-role walkthrough remain unverified because the computer-use runtime fails during initialization. View/save circulars use extracted-text inspection and original-file export; no embedded PDF/Word renderer or OCR is included.
