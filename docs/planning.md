# SmartHire planning

## Confirmed constraints

- University proof of concept, demonstrated on one laptop.
- Separate student, company, and admin logins in a Java Swing application with local MySQL.
- Java 21, JDBC, and the Java desktop stack specified in the supplied brief; no React, Python, or Spring Boot.
- Laptop has 8 GB RAM. CV processing and explainable matching must run locally and remain lightweight.
- Demonstrates a matching approach that could be extended; scalability has not been established or measured.
- Planning only on 2026-11-04. User intends to start implementation on 2026-11-05.
- Aim for a complete initial build in one five-hour quota session, without treating this as a guaranteed completion time or a hard deadline. Additional sessions are acceptable.

## Accepted first-release decisions

- Cover all three roles and the complete hiring journey: registration/login, profiles, company verification, job approval, CV upload, explained matching, applications, shortlisting, status tracking, and basic dashboard statistics.
- Defer learning roadmaps, complaints, duplicate CV detection, and advanced analytics.
- Support DOCX and text-based PDFs, with an editable extraction review form before extracted information affects matching. Defer OCR for scanned/image-only PDFs.
- Show compatibility and eligibility separately. A high score does not override failed mandatory requirements; explain failures and block applications when a confirmed mandatory requirement fails.
- Use Java-written Swing layouts that open and run in NetBeans. GUI Builder .form files are not required.
- Recruiters enter structured education, skills, experience, project-skill, and certification requirements alongside a free-text description. Extraction may suggest requirements; recruiters confirm them before publishing.
- Default configurable weights: skills 40%, education 20%, experience 15%, projects 15%, certifications 10%. Normalize common skill aliases and show category contributions. Redistribute weights proportionally across categories with requirements. Missing student information receives no credit and fails corresponding mandatory checks.
- Companies can access only applicants to their own jobs, ranked per job, including viewing/downloading the CV submitted with an application.
- Application lifecycle: Applied to Shortlisted to Hired or Rejected; rejection is also possible directly from Applied. Students may withdraw before a final decision. One application per student per job. Preserve submitted CV and qualifications independently of later profile updates.
- Admin verifies companies and approves each job before visibility. Editing approved job requirements requires fresh approval. Closing a job stops new applications and preserves existing applications.
- Skills score: matched listed skills divided by total listed skills, with required skills weighted twice as much as optional skills. Education: full credit only when minimum degree level and any specified field match. Experience: relevant months divided by required months, capped at 100%. Projects: coverage of requested project skills in reviewed projects. Certifications: coverage of named certifications. Mandatory checks are independent. Reject jobs without scored requirements.
- Structured matching determines the match percentage. TF-IDF/cosine similarity is a separate supplementary text-similarity indicator. Students confirm extracted qualifications; the system does not verify the truth of their claims.
- Freeze job requirements after the first application. Recruiters may close the posting and create a revised one. Requirement edits before any applications still require fresh approval.
- One role per account. Students and companies self-register; seed an admin account. Hash passwords. Include in-app notifications; defer email verification, email alerts, and password recovery.
- Deliver a Maven project importable into NetBeans, SQL schema and sample data, ER diagram, setup/run instructions, sample PDF/DOCX CVs, demo accounts, a short demo script, and JUnit coverage for scoring, mandatory eligibility, and application rules. Use fictional demo data.
- Support English-language DOCX and text-based PDF CVs up to 5 MB. Extract text and suggest recognizable skills, education, experience, projects, and certifications; students correct incomplete results. Store local CV copies so application snapshots survive movement of source files.
- Include job search/filtering, side-by-side comparison of two jobs, matched/missing skills, rule-based improvement suggestions, salary/location fields, applicant filtering, and basic admin account/category/skill management. Notifications cover approvals and application status changes. Defer personalized learning roadmaps, smart alerts, complaints, duplicate detection, and advanced analytics.
- Use a modern white/light theme with clear navigation, readable forms and tables, consistent spacing, restrained decoration, sidebars, cards, score indicators, and a few basic charts. Prioritize straightforward human workflows over decorative dashboard styling.
- Use PDFBox, POI, Connector/J, JUnit, and JFreeChart. Add OpenNLP only when a specific extraction task benefits from it. Run file parsing and matching off the Swing UI thread for responsiveness on the 8 GB laptop.

## Planning status

The draft specification now defines how to count relevant experience, validate policy changes against existing jobs, preserve managed files on failure, serialize first submission with job edits, and enforce access after disablement. The build plan gives the shared document reader its own prerequisite and schedules an end-to-end submission and original-CV download before comparison, circular suggestions, and admin policy changes.

Accepted after requirements review: when weights change, rank applicants using a common current scoring policy against immutable submitted qualifications and frozen job requirements. Preserve original submission scores, policies, and explanations separately; label recalculated rankings clearly. The requirements review also restored explicit soft-skill extraction, job-circular attachments, job-description requirement suggestions, and admin application monitoring in the draft specification.

The user confirmed the consolidated scope. Implementation has not started. A specification and dependency-ordered ticket breakdown are being prepared for review; planning artifacts do not authorize starting implementation today.

The supplied brief ends mid-item at "Job comparison d". Treat the missing remainder as unknown.
