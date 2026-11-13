# SmartHire UI redesign brief

The user accepted the three-role design preview on 2026-11-05 and accepted the implemented redesign after the Swing workflow follow-up. See [implementation status](implementation-status.md) for verification and the outstanding physical laptop check.

## Agreed direction

- Professional and precise, with a distinct identity and purposeful color.
- Equal attention to student and company experiences; include admin screens in the redesign.
- Redesign layouts and navigation within existing features. Secondary information may move to detail views or dialogs.
- Use persistent left navigation with a clear selected section.
- Design for a 1366 × 768 laptop display, allowing for window decorations and the taskbar.
- Refine labels, help text and empty states while preserving domain terms and application rules.
- Make hiring evidence clear: eligibility and unmet mandatory requirements take visual priority over match score.
- Establish the design first through Job matches, Applicants and admin CV review before extending it throughout the application.

## Rules the presentation must preserve

Use CONTEXT.md and docs/spec-draft.md as the domain authorities. Eligibility, CV approval and application status are separate concepts. Meeting mandatory requirements does not establish that a CV is approved for submission. Current-policy ranking and original submission assessments must remain clearly distinguished. Existing permissions, immutable submitted evidence and application transitions remain unchanged.

## Starting point

Before the redesign, the theme mainly set panel background and label font. Feature entry replaced overview navigation. Large outer padding, persistent status areas and nested scrolling competed for available height. Match explanations put score before eligibility, and much of the evidence appeared in long plain-text blocks.

The existing dashboard chart uses teal (#087F8C). The accepted direction uses navy, teal and neutral surfaces with semantic status colors. Pair status color with readable labels or icons.

## Accepted first checkpoint

The accepted preview uses a list with an adjacent concise preview and a full detail view for deeper inspection. Technical identifiers, policy versions and calculations sit under details. The palette uses navy navigation, a light neutral workspace, teal actions and labeled semantic status colors. The preview established the visual direction. Swing acceptance is recorded separately.

The conversation preview contains fictional records for Student Job matches, Company Applicants and Admin CV review. It demonstrates role switching, record selection and the information intended for a full detail view. It does not submit applications or moderation decisions. The preview adapts to the conversation width; actual Swing sizing at 1366 × 768 still requires validation.

## Acceptance for implementation

- All three roles retain navigation and a clear current location throughout their workflows.
- Essential actions remain reachable at the target display size; long content scrolls without clipping controls.
- High-score mandatory failures are immediately distinguishable from eligible matches; CV approval remains separately identifiable.
- Evidence, original assessments and current rankings retain their meaning and access restrictions.
- Verify keyboard traversal, selected/disabled/loading/error/empty states, long names and explanations, and display scaling with the live app.
- Run existing unit tests and packaging; run database verification if service or database behavior changes. Review the full diff and report interactive checks separately.

The redesigned build passed 84 automated checks, including the clean-database smoke test. A compact Swing run at 125% Java scaling exercised the three-role workflow programmatically. Physical 1366 × 768 acceptance through Windows input remains unverified.

## November 2026 client polish

Retain the supplied SmartHire logo and teal/navy identity. Apply consistent rounded buttons with visible keyboard focus, hover and disabled states; white section headers with teal accents; clear form labels; readable table headings and alternating rows; and prominent dashboard counts. Keep the sign-in card compact with Sign in, Create an account and recovery actions aligned. Failed sign-in opens an error dialog and supports immediate retry. Saved searches and alerts remain in scope and enabled; removal is deferred.

The compact three-role Swing walkthrough and normal/125% authentication alignment checks passed. Physical input and the client's Windows 10/XAMPP setup remain separate acceptance checks. See `implementation-status.md` for evidence.

## Saved-item and colour follow-up

Keep Saved jobs, Saved searches and automatic approval alerts. The client's report did not specify a failure. A reproducible compact-screen check found wrapped search controls clipped by a one-row action-bar height. Shared action bars now measure wrapped rows, including the Job matches footer. Authentication action rows retain compact spacing to preserve registration/recovery reachability.

Extend teal with blue for applications/reports, violet for CV/profile/saved sections and amber for interviews/notifications/admin access tools. Use tinted section headers and selected navigation, varied dashboard count cards, and pale blue table/tab surfaces. Status text and existing semantic colours retain their meaning; section colours do not imply eligibility, CV approval or hiring status. Keep the supplied logo unchanged.

The programmatic compact walkthrough screenshots are `target/ui-checkpoint-jobs.png` and `target/ui-checkpoint-admin-dashboard.png`, with other role screens alongside them. Their layouts and palette were visually inspected. The action-row regression is covered by `SavedItemsUiIntegrationTest`; authentication alignment is separately checked at normal and 125% scaling.
