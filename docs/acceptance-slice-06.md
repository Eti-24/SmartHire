# Slice 06: notifications and oversight

Prerequisites: slices 05/05b, running MySQL, and a rebuilt/restarted app. Startup adds the notification table without deleting existing records. Existing events are not backfilled.

## Observable journey

1. Sign in as admin. Overview shows account/job/application counts and an application-status chart. Open Accounts, select a student or company and disable it. Existing sessions must fail their next service action and new login must fail. Enable it to restore login. Admin accounts are protected from disablement here.
2. Verify a company and approve or reject its pending posting. Sign in as that company and open **Notifications**. Select an alert to preview the decision and any rejection reason. Choose **Read notification** to open the message and persist read state. Refresh or restart to check that it stays read. Repeating an unchanged verification creates no alert.
3. Apply as an eligible student, shortlist as the owning company, then hire/reject or withdraw as allowed. Both participants receive saved alerts after committed submission/status changes. Refused transitions, duplicate submissions and rolled-back changes produce no alerts. Inbox ownership is checked by the service, including attempts to mark someone else's notice read.
4. Overview for a student counts only their applications/CV versions and currently visible jobs. A company counts only its jobs/applicants. Each role's chart groups only its permitted applications. Overview reloads counts; Notifications has a refresh button. There is no automatic polling.
5. Admin opens Application monitoring and combines job ID, company account ID, student account ID and status filters. IDs are shown in the monitoring table; Accounts also lists account IDs. View selected record shows the saved submission assessment and contact name/email. This workspace has no decision buttons or CV download access. Student/company service calls to monitoring/accounts must fail.
6. Disable an applicant or company after submission. Admin can still monitor the record. The active owning company retains submitted evidence when a student is disabled; an active student retains their history/original bytes when the company is disabled. Disabling does not delete or rewrite a profile, job, CV version or application.
7. Admin uses Jobs and administration → Skills to maintain technical/soft types and curated aliases. Existing catalog validation and canonical IDs remain in effect.

## Automated evidence and limitations

`WorkspaceIntegrationTest` exercises committed verification/approval/application events, persistence and read ownership, rollback after event and submission writes, prohibited actions, all monitor filters, cross-role denial, account disable/enable with old sessions and preserved original bytes, protected admins, and scoped/refreshed dashboard counts. These run in `smarthire_test` with isolated fictional data, alongside every earlier test.

Notifications carry plain text and record identifiers rather than embedding mutable records. Long notification text is shortened; the complete rejection reason remains on the posting. Event writes share the JDBC transaction that commits the source change. Read state is idempotent.

Interactive Swing navigation, keyboard traversal, chart readability and laptop screen fit still require visual acceptance. Automated service checks do not establish those outcomes.
