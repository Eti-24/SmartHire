# SmartHire specification

Status: draft for test-approach review; product scope confirmed.

## Problem Statement

Students need a clear way to understand which jobs fit their qualifications, what they lack, and whether they satisfy mandatory requirements. Recruiters need an understandable comparison of applicants. A university demonstration must show this complete journey locally on one 8 GB laptop, with explainable results rather than opaque claims of hiring suitability.

## Solution

A Java 21 Swing desktop application with local MySQL, separate student/company/admin accounts, reviewed CV extraction, structured job requirements, weighted compatibility scoring, independent eligibility checks, and an application workflow. The interface uses a modern white theme, straightforward navigation, readable forms and tables, and restrained decoration.

## User Stories

1. As a student, I want to register and log in so that my qualifications and applications are associated with my account.
2. As a company, I want to register and maintain my profile so that students know who is recruiting.
3. As an admin, I want to log in using a provisioned account so that I can manage the demonstration.
4. As any user, I want role-appropriate navigation and logout so that I can reach my tasks and safely switch accounts.
5. As a student, I want to maintain my profile so that companies receive useful contact and qualification information.
6. As a student, I want to upload an English PDF or DOCX CV so that I can reuse information from my document.
7. As a student, I want to review and correct extraction suggestions so that parsing errors do not determine my results.
8. As a student, I want an explanation when a document is unsupported or contains no readable text so that I know how to proceed.
9. As a student, I want to update my CV without changing previous submissions so that applications retain their original evidence.
10. As a company, I want to enter a job description, salary range, and location so that students can assess the opportunity.
11. As a company, I want to specify education, skills, experience, project skills, and certifications so that matching uses explicit requirements.
12. As a company, I want to distinguish required and optional skills and mark mandatory qualifications so that scoring and eligibility reflect different concerns.
13. As an admin, I want to verify companies so that only accepted companies can publish jobs.
14. As an admin, I want to approve or reject job postings so that visible jobs have been reviewed.
15. As a company, I want to edit unfilled postings and resubmit changed requirements so that approval remains meaningful.
16. As a company, I want to close jobs so that applications stop without deleting hiring history.
17. As a student, I want to browse and filter available jobs so that I can find suitable opportunities.
18. As a student, I want recommended jobs ranked by compatibility so that promising opportunities are easy to find.
19. As a student, I want category-level score explanations so that I understand each match percentage.
20. As a student, I want failed mandatory requirements shown independently so that a high score does not mislead me about eligibility.
21. As a student, I want matched and missing skills and rule-based improvement suggestions so that I can identify useful next steps.
22. As a student, I want to compare two jobs side by side so that I can weigh their requirements and fit.
23. As a student, I want to apply to an eligible open job so that the recruiter receives my current reviewed qualifications and CV.
24. As a student, I want to track and withdraw an undecided application so that I can manage my job search.
25. As a company, I want applicants ranked and filtered per job so that I can review relevant submissions efficiently.
26. As a company, I want to view and download submitted CVs so that I can inspect the application evidence.
27. As a company, I want to shortlist, reject, or hire an applicant so that the application reflects my decision.
28. As an applicant, I want my submitted evidence and score explanation preserved so that later edits do not silently change my application.
29. As a company, I want applicant access limited to my own postings so that unrelated student records are not exposed.
30. As an admin, I want basic account, category, and skill management so that I can maintain demonstration data.
31. As an admin, I want configurable matching weights so that the weighting policy is visible and adjustable.
32. As a user, I want approval and application-status notifications inside the application so that I can see relevant changes.
33. As a user, I want dashboard statistics and a few readable charts so that I can understand my activity at a glance.
34. As a presenter, I want fictional accounts, CVs, and jobs so that I can reliably demonstrate all three roles.
35. As a developer, I want a NetBeans-compatible Maven build, database scripts, an ER diagram, instructions, and automated checks so that the project is reproducible and explainable during assessment.
36. As a student, I want technical and soft skills identified separately so that my reviewed profile represents both kinds of qualification.
37. As a company, I want to attach a job circular and obtain editable requirement suggestions from its text or my pasted description so that I do not have to enter every recognized requirement manually.
38. As an admin, I want to monitor applications across companies and filter by job, company, student, and status so that I can oversee the demonstration without changing recruiter decisions.

## Implementation Decisions

### Runtime and architecture

- Java 21, Swing/AWT, JDBC, MySQL, and Maven. No React, Python, Spring Boot, or required REST service.
- Single-machine deployment. One application instance can demonstrate roles sequentially through login/logout. This deployment does not establish production access control or scalability across computers.
- Keep the main behavior behind application-service operations for authentication, CV review, job moderation, matching, and applications. Swing handles presentation; services enforce permissions and rules; repositories handle JDBC persistence; document adapters handle parsing and local storage.
- Java-written Swing layouts are sufficient; NetBeans GUI Builder forms are not required.
- PDFBox and POI parse documents; Connector/J connects MySQL; JFreeChart supports basic charts; JUnit covers meaningful behavior. OpenNLP is conditional on a concrete extraction benefit.
- Parsing, matching, file access, and slow database operations use background work. Swing component updates occur on its event-dispatch thread. Avoid retaining whole document collections or loading unnecessary models.
- Show actionable validation and configuration errors. Keep database credentials in local configuration outside committed source and never display passwords in diagnostics.

### Identity and access

- Exactly one role per account. Student and company self-registration; seeded admin; salted password hashing. No plaintext stored passwords.
- One company account owns one company profile and its jobs in this first release. Multi-recruiter company teams and role switching are outside the agreed single-role demonstration model.
- Company verification and individual job approval are distinct decisions.
- Check role and record ownership in service operations, including CV downloads and application transitions. UI visibility alone is insufficient.
- Basic account management must preserve records referenced by applications; disabling accounts is preferable to destructive deletion when history exists.

| Request | Service rule |
| --- | --- |
| Browse a posting, open its circular, or apply as a student | Require an active student and an approved, open posting owned by an active, verified company. Applying also requires the checks under Applications. Recheck even when opening a previously displayed result. |
| Review a circular before publication | Allow the active owning company and the active admin moderating the posting. |
| Read an existing submission after closure or company disablement | The active student retains their own history. An active owning company retains access to its applicants after closure. A disabled account cannot act, including through an existing session. Admin monitoring remains read-only. |

### CV processing and storage

- Accept English DOCX and text-based PDF files up to 5 MB. Validate actual parseability and handle malformed, encrypted, unsupported, and empty documents without crashing.
- Student confirmation submits that immutable CV/qualification version for admin approval. Current versions have Pending, Approved or Rejected approval state. Only an active admin may inspect confirmed submissions and their original files or approve/reject them; only the owning student sees their decision through My CV. Company access remains limited to actual applications to their jobs.
- Approval applies to the exact current version, including its confirmed qualifications. Any confirmed replacement or qualification-only edit starts Pending, even when it reuses the original document. Drafts/cancellation leave the existing current version and approval unchanged. No fallback to an older approved version is used for new applications.
- Admin decisions require an active student and the version still being current under the student lock. Approval rechecks original-file integrity. Rejection requires a nonblank reason of at most 1000 characters. Decisions record reviewer/time and notify the student in the same transaction. A decided version cannot be decided again; correction/resubmission creates a new version. Existing confirmed versions without a decision are Pending, never implicitly approved.
- Matching/comparison previews remain available before approval. Approval permits use in applications; it does not imply that the CV meets a job's mandatory requirements or independently authenticate the student's claims. Earlier application evidence, decisions and downloads remain unchanged when the current CV is replaced/rejected.
- OCR and image-only CV extraction are excluded. Explain the limitation in the upload flow.
- Extract text and suggest recognized skills, education, experience, projects, and certifications. Suggestions are editable and must be confirmed before contributing to matches. Do not present keyword recognition as verification of a student's claims.
- Preserve raw extraction and reviewed structured qualifications separately. Replacing a CV does not silently reuse an old review as a new one.
- Copy documents into managed local storage using safe generated identifiers. An application references an immutable submitted copy/version and qualification snapshot; moving the source document or editing a profile does not invalidate it.
- Stage, parse, and finish the managed copy before committing its database reference. Keep the previous reviewed version current until the replacement is confirmed. An application references that confirmed version. On rollback discard the unreferenced copy; at startup reconcile abandoned files against committed references. Never remove a referenced version during cleanup. If a referenced file is missing, retain its history, report the unavailable document, and block new submissions using it until a readable version is available.
- Identify technical and soft skills as separate skill types in the vocabulary and review form. Both may be requested explicitly by a recruiter; do not infer personality or proficiency from keyword presence. The existing skills-category formula remains unchanged, and both types are visible in its explanation.
- Provide lightweight requirement suggestions from pasted job descriptions or readable job-circular attachments. Recognize known skills, education/experience phrases, and named certifications conservatively; flag unrecognized material for manual entry. Recruiter-confirmed structured fields are authoritative; free text never silently creates mandatory requirements.
- Allow an optional PDF/DOCX job-circular attachment under the same English, text-based, 5 MB limitations as CVs. Copy it into managed local storage and allow authorized viewing/downloading. A company can still create a job entirely through the structured form; an attachment never bypasses review or approval.
- Record skill aliases explicitly in a curated, extensible vocabulary. Match token/phrase boundaries so that Java is not automatically JavaScript and C is not automatically C++. Reuse normalization in job requirements, CV review, projects, and explanations.

### Job publication

- Capture description, category, salary range, location, minimum degree level and any specified field, skills with required/optional priority, relevant experience in months, project skills, certifications, and explicit mandatory flags.
- Reject configurations without any scored requirements. Validate ranges and nonnegative experience, and avoid duplicate canonical requirements inflating scores.
- Only approved open jobs from verified companies are available for new applications. Draft, pending, rejected, and closed postings are not available to apply to.
- Requirement changes before any application require fresh approval and remove the previous approved version from new applications until approved again.
- Requirements freeze at the first application. A company can close the job and create a revised posting rather than changing the basis on which existing applicants were assessed.
- Serialize submission, requirement edits, and closure on the same job row. Under that lock, reread the current job and application state before writing. An edit that commits first returns the job to pending. A submission that commits first freezes the requirements, including after withdrawal.

### Matching contract

- Default category weights: skills 40, education 20, experience 15, projects 15, certifications 10. Admin can configure weights; reject negative values and configurations with no positive total.
- For each job, include only categories with actual requirements. Normalize their configured weights to a total of one. If active requirements have no positive total weight, reject the scoring configuration instead of dividing by zero or fabricating a score.
- Before activating a policy, validate every approved open job and every job with applications. List any incompatible job IDs and retain the previous policy on failure. Approval must validate the job against the active policy. Serialize approval and policy activation on the same policy record so simultaneous changes cannot publish an unscorable job.
- Skills category fraction equals the sum of weights of matched canonical skills divided by all listed skill weights. Required skill weight is two; optional skill weight is one. Required priority does not itself imply mandatory eligibility.
- Education earns one only when the reviewed degree satisfies the minimum level and any specified field; otherwise zero. Document the degree ordering and field aliases used by the demonstration and avoid unsupported universal equivalence claims.
- Experience earns the lesser of one and reviewed relevant months divided by required months. A zero-month minimum imposes no scoring requirement. Missing evidence earns zero for an actual requirement.
- For a positive minimum, the recruiter selects one or more canonical skills that define relevant experience. Students confirm a start month, end month, and skill tags for each work entry. Count distinct calendar months covered by entries matching any selected tag, including both endpoints. Reject reversed or future ranges. For an ongoing role, freeze the end month at CV review so the same submitted evidence yields the same months on recalculation. Show the counted months and tags in the explanation and use the same count for mandatory eligibility.
- Projects earn the fraction of requested canonical project skills present in reviewed projects. Skills listed only in the general profile do not automatically count as project evidence.
- Certifications earn the fraction of specified certifications present in the reviewed profile, normalized consistently. No automatic accreditation verification is implied.
- Overall percentage is 100 times the sum of each active category fraction multiplied by its normalized weight. Retain calculation precision and round only for display.
- A separate mandatory check reports satisfied and failed requirements. Missing information fails a corresponding mandatory check. A high match score cannot override ineligibility and cannot permit an application.
- Return category contributions, matched/missing items, failed mandatory requirements, and concise rule-based suggestions with the score. Scores are compatibility indicators, not hiring probabilities.
- Supplementary TF-IDF/cosine text similarity is labeled separately and never changes eligibility or the structured percentage. Empty text must yield a defined result without arithmetic errors.
- Persist or invalidate cached matches when reviewed qualifications, applicable job requirements, or weights change. Preserve the calculation evidence for submitted applications rather than reinterpreting them under later weights.
- Accepted ranking rule: compare all applicants to a job using the same current scoring policy against their immutable submitted qualifications and the frozen job requirements. Retain the original submission score/policy separately and label a recalculated ranking clearly. This avoids comparing percentages calculated under different weights without overwriting application history.
- Suggested deterministic ordering: eligible jobs first, then descending unrounded match score, then stable job identifier; retain a visible explanation for ineligible jobs. Applicant ties use submission time and stable application identifier. Matching without a reviewed CV prompts review instead of inventing a zero-qualification recommendation.

### Applications and notifications

- One application per student per job, enforced atomically and with a database uniqueness constraint. Withdrawal does not remove this uniqueness rule.
- Applying requires an active student, the current confirmed CV version approved by an active admin at decision time, an approved open job from an active verified company, readable original CV bytes, and successful mandatory checks. Recheck current version/approval inside the submission transaction under the student lock rather than relying on a previous screen result. Later admin disablement does not erase an earlier committed decision.
- At submission, preserve CV version, reviewed qualifications, score breakdown, eligibility evidence, and the relevant weighting/job basis. Do not compute historical applicant rankings from a student's subsequently edited profile.
- Allowed recruiter transitions: Applied to Shortlisted or Rejected; Shortlisted to Hired or Rejected. Students can withdraw while Applied or Shortlisted. Hired, Rejected, and Withdrawn are terminal.
- Recruiters can access applicants only to their own jobs and can filter/rank them per job. Closing a posting prevents new applications while retaining review of existing submissions.
- Admin application monitoring is a read-only system-wide oversight view, not an extra recruiter role. Ordinary companies retain the own-applicants-only rule.
- Make application creation and status transitions transactional; notifications must correspond to committed changes. In-app notifications cover verification/job approval decisions and application-status changes.

### Persistence and presentation

- Normalized MySQL entities include users, students, companies, CV versions, skills, student skills, jobs, job requirements, matches, applications, certifications, projects, and notifications, with supporting category, review, snapshot, and scoring-policy data where needed.
- Use primary keys, foreign keys, appropriate uniqueness constraints, transactions, prepared statements, and indexes for ordinary lookup/filtering. Do not implement history by destructive cascading deletion.
- Modern white/light styling, restrained accent colors, readable typography, clear labels, consistent navigation, sensible empty states, and explicit loading/error feedback. Do not communicate eligibility by color alone.
- Keep primary navigation labels consistent across screens, provide visible form labels and validation near the relevant field, use scrollable/resizable layouts, and preserve normal keyboard focus and table behavior. Avoid hard-coded layouts that clip controls on the demonstration laptop; check the actual screen dimensions during implementation.
- Include job search/filtering, comparison of two jobs, applicant filters, profiles, basic admin management, dashboard statistics, and a few charts. Avoid decorative controls that have no working behavior.
- Deliver source, schema/seed scripts, fictional CV samples and accounts, ER diagram, setup/run instructions, a demo script, and tests. Provide a reproducible seed/reset path with clear data-loss implications.
- Include MySQL Workbench-compatible schema/seed instructions and an ER diagram export that can be opened without special editor setup. Document managed-file storage and how to copy both database and files when moving the demo. Pin dependency versions during implementation and record installed Java/Maven/MySQL versions after checking the environment.
- Use local Git history for implementation if available; GitHub publication is optional and requires a configured destination. Local Markdown issue tracking does not replace version control. The application should run without internet after Java, MySQL, and Maven dependencies are installed; the first dependency download may require connectivity.

## Testing Decisions

- Proposed primary test seam: application-service operations, exercised with realistic inputs and observable outputs, persisted records, authorization failures, and state transitions. Keep Swing component structure outside the unit-test contract.
- Use focused pure matching tests for numerical edge cases and explanation consistency. This subordinate seam makes arithmetic failures easy to diagnose without launching a GUI or database.
- Use isolated MySQL integration checks for constraints, rollback, ownership, application snapshots, and duplicate submissions. Keep these explicitly runnable; a unit-only pass must not be described as database verification.
- Verify actual small PDF/DOCX fixtures through parsing and review, including malformed and textless cases. Do not mock the parser and then claim file-format support was tested.
- Cover mandatory failure despite a high percentage, missing data, aliases, unused category redistribution, weight changes, zero denominators, caps, empty similarity text, prohibited transitions, cross-company access, closed jobs, and duplicate applications.
- Verify an application retains its submitted evidence after a student changes their profile/CV. Verify job requirement editing stops once an application exists.
- With two MySQL connections, exercise both commit orders for an edit racing the first application. Expect a pending job with no application, or a submitted snapshot and a refused edit. Also attempt to activate a policy with zero skills weight for a skills-only job, first while open and again after closure with an applicant. Both attempts must leave the active policy unchanged.
- Check that Java-tagged roles spanning January–April and March–June earn six months for a Java requirement. An untagged retail role adds none. Inject managed-copy and database-commit failures during replacement; verify the previous review and submitted download survive. Simulate a missing referenced file and verify the explicit unavailable state. Exercise each access rule above directly through its service, including a stale student result and an already signed-in disabled company.
- Cover technical/soft skill review, token-boundary alias mistakes, recruiter-confirmed description suggestions, job-circular attachment handling, admin read-only monitoring, account disablement, and notification read state. Test that cross-policy submissions are compared consistently and original scores remain intact.
- Manually smoke-test all three roles, login/logout, readable white styling, keyboard/tab navigation, upload responsiveness, job comparison, CV viewing/downloading, and the complete demonstration journey.
- No existing code or test conventions are available. Introduce only tests that establish useful external behavior; avoid tests that merely mirror private helpers or layout details.

## Out of Scope

- Shared multi-computer deployment, production scale claims, external AI services, trained-model requirements, OCR, non-English extraction, and automatic truth verification of CV statements.
- Personalized learning roadmaps, smart/email alerts, email verification, password recovery, complaints, duplicate CV detection, and advanced analytics.
- Required NetBeans GUI Builder forms, React, Python, Spring Boot, and a separate web frontend.
- Separate searches across all student profiles, multi-recruiter company teams, semantic personality assessment, and automatic real-world credential verification.
- Material missing after the truncated final item in the supplied brief. Do not invent requirements from that missing text.

## Further Notes

The user intends to start implementation in a later session and hopes to fit the initial build into one five-hour quota session; additional sessions are acceptable. This is a target, not a delivery guarantee. Deliver complete vertical journeys in dependency order and report remaining work honestly rather than replacing agreed functionality with placeholders. This draft must be reviewed for the proposed test approach before publication as a ready-for-agent specification.
