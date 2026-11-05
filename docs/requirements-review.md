# Requirements review

Compared the original supplied brief, accepted interview answers, specification draft, and ticket breakdown. This is a document review, not verification of an implemented application.

## Omissions corrected

| Original requirement or accepted goal | Correction | Delivery slice |
| --- | --- | --- |
| Extract technical and soft skills | Separate vocabulary types and review fields; avoid inferring personality | 02, 03, 04 |
| Upload job circulars | Optional local PDF/DOCX attachment with viewing/downloading and the same size/text limitations as CVs | 02a, 05b |
| Extract job requirements | Lightweight editable suggestions are required; the prior optional wording was too weak | 05b |
| Student job search and filtering | Title search and category filtering respect publication and company visibility rules | 02 |
| Admin monitors applications | Read-only cross-company monitoring and explicit filters | 06 |
| Candidate ranking after weight changes | Accepted common current policy while preserving original submission results | 04, 05, 05b |
| Normalized matching | Curated aliases and token boundaries; Java/JavaScript and C/C++ must stay distinct | 02, 03, 04 |
| Professional white UI | Resizing, scrolling, keyboard focus, field-level errors, screen-fit checks | All UI slices; final check 07 |
| Workbench, Git, reproducible demonstration | Workbench instructions, version-control distinction, offline-runtime boundary, database/file portability | 01, 07 |

## Original brief coverage

- Student features: covered, with reviewed English PDF/DOCX extraction and two-job comparison as accepted refinements.
- Company features: covered, with applicant-only access replacing unrestricted student browsing by explicit user choice.
- Admin features: covered except reports/complaints and advanced analytics, explicitly deferred; basic statistics and application monitoring remain included.
- Matching: weighted categories, configurable policy, mandatory eligibility, explanations, missing skills, suggestions, and separate text similarity covered. Do not describe these as trained AI or measured production scalability.
- Stack: Java 21, Swing/AWT, JDBC/MySQL, PDFBox, POI, JFreeChart, JUnit, NetBeans, and Workbench covered. GUI Builder forms are waived, OpenNLP is conditional, and REST/Postman are unnecessary under accepted choices.
- Database: all thirteen named core entity/table concepts are represented, with additional data for categories, aliases, snapshots, and policy versions. Implementation must supply the actual schema and ER diagram, not only this list.
- UI: all three dashboards, sidebar, cards, forms, tables, search, score indicators, charts, notifications, and profiles covered with the accepted white theme.
- Optional advanced features: skill gaps, rule-based CV suggestions, applicant ranking, and job comparison included. Roadmaps, smart alerts, duplicate detection, and advanced analytics deferred by agreement.
- The pasted original ends at "Job comparison d". Its missing remainder remains unknown.

## Accepted ranking clarification

When weights change after some applications have been submitted, ranking their original percentages together is inconsistent. The user accepted comparison of immutable submitted qualifications under one current policy, with an explicit recalculated label, while preserving each original submission score and policy.

## Planning limits

- The product scope is confirmed; draft test seams, ticket granularity/blockers, and local tracker setup still need review under the selected skills.
- The revised sequence demonstrates submission and historical CV download in 05. Circulars, comparison, similarity, and policy changes remain required in 05b before final integration.
- The environment smoke check loaded the document and chart libraries, connected to local MySQL through JDBC, and verified offline Maven dependency resolution for the setup project. Application behavior, schema, performance, and screen usability still require implementation and validation. [Environment verification](environment-setup.md#verification-completed)
