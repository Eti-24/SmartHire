# SmartHire

SmartHire connects students seeking jobs with companies through CV analysis and explainable job matching.

## Language

**Student**:
A person seeking jobs through SmartHire, whether currently studying or already graduated.
_Avoid_: treating student and job seeker as separate roles.

**Match score**:
A percentage expressing compatibility between a student's qualifications and a job's requirements, with an explanation of the contributing factors. It is distinct from eligibility.
_Avoid_: hiring probability.

**Eligibility**:
Whether a student satisfies a job's mandatory requirements, assessed separately from the match score.

**Reviewed CV profile**:
The student's confirmed qualifications after reviewing and correcting information extracted from a CV. These qualifications are used for matching.

**CV confirmation**:
A student's submission of an exact CV version and corrected qualifications for admin review.
_Avoid_: treating student confirmation as admin approval.

**CV approval**:
An admin's acceptance of a specific confirmed CV version for use in new applications. A replacement or qualification edit is a different version requiring its own decision.

**Mandatory requirement**:
A job qualification that must be satisfied for a student to be eligible to apply, regardless of the match score.

**Application**:
A student's submission to a particular job, preserving the CV and qualifications submitted at that time. A student may submit only one application per job.

**Applicant**:
A student who has submitted an application to a job. A company can access applicants to its own jobs.

**Verified company**:
A company whose registration has been accepted by an admin. Verification is separate from approval of its individual jobs.

**Approved job**:
A job accepted by an admin for student visibility. Changes to its requirements need fresh approval.

**Text similarity**:
A supplementary measure of textual overlap between a CV and a job description. It is separate from both the structured match score and eligibility.

**Required skill**:
A listed job skill with twice the scoring weight of an optional skill. It blocks eligibility only when also marked mandatory.

**Optional skill**:
A listed job skill that contributes to compatibility without being necessary for eligibility.
