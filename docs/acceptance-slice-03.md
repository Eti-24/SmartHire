# Acceptance: slice 03 — CV upload and review

This slice adds the student **My CV** workspace. Matching and applications are later slices. Automated service and MySQL checks are available; an interactive Swing walkthrough and screen-fit check remain pending because the computer-use runtime could not initialize.

## Run the journey

From PowerShell 7 at the project root:

```powershell
.\scripts\start-mysql.ps1
.\scripts\run.ps1 -SeedDemo
.\scripts\run.ps1
```

1. Sign in as `student@smarthire.example` with the existing fictional demo password `SmartHireDemo!21`. Open **My CV**. Before the first confirmation, the screen explains that there is no reviewed CV.
2. Choose **Upload / replace CV** and select `src/test/resources/documents/fictional-cv.docx`. The supported input is English text-based PDF/DOCX up to 5 MB. OCR and encrypted documents are unsupported.
3. Inspect **Extracted text** and **Skill catalog**. The fictional DOCX includes project evidence from a table. Suggestions are proposals, not verified claims. Unknown information must be entered manually; the original extracted text stays separate.
4. On **Review qualifications**, confirm technical and soft skills separately. Ctrl+click toggles a skill; keyboard users can use Ctrl+Space and Shift+arrows. Correct the degree/field, project evidence and its skills, dated work entries and their skill tags, and certifications. Project/work skills accept comma-separated catalog names or stable IDs. Certifications use one line each.
5. Add work evidence with start `2025-01`, end `2025-06`, and Java as a skill tag. For an ongoing role, select Ongoing; its end freezes at the month of confirmation. Future or reversed dates must fail with feedback. Blank qualification categories are allowed and do not invent evidence.
6. Select **Confirm reviewed CV**. Inspect the current indicator and **View selected review**. Restart the app and verify the review, dates, projects, skill tags, and certifications persist.
7. **Save selected original…** must export the uploaded original bytes. Choose a destination outside `.local/documents`; the managed store is maintained by the app.
8. Start a replacement with `fictional-cv.pdf`, then **Cancel review**. The previous version must remain current. Repeat with a textless or encrypted fixture; the failure must leave the previous review usable.
9. Confirm a replacement. Both versions must remain in history, each with its own review and original download. **Edit current review** creates another immutable review version using the same original bytes; it must not alter earlier reviews.
10. While a file is being read, return to the overview. The UI must remain responsive; an abandoned upload is discarded after the worker finishes. Leaving an open review cancels it. Closing the whole app may leave an unreferenced file for startup recovery.
11. Check keyboard navigation, table-cell editing, scrolling to the confirmation buttons, and screen fit. Sign in as a company or admin and confirm that **My CV** is absent; service tests separately reject those roles and cross-student access.

## Persistence and failure rules

- `cv_versions` and its qualification children are append-only through the service. `cv_current` points to the student's confirmed version. Raw extracted text lives with the immutable managed document.
- Confirmation locks the active student, rejects a stale draft, validates qualifications, and inserts a new document reference (when uploading), review, child rows, and current pointer in one transaction. The prior version remains current on rollback.
- Invalid inputs stay editable. A failed database save can be retried or cancelled; cancel checks committed references before removing pending bytes. This also protects a commit whose response was lost.
- Original bytes are integrity-checked before confirmation/download. Missing or changed bytes block editing/downloading that document while preserving review history. A readable replacement can still be uploaded.
- Ongoing work ends are frozen independently in each review. General skills do not automatically become project evidence or work tags. Skill IDs are shared with job requirements; field/certification normalization matches the job service.
- Suggestions recognize curated skill phrases, explicit degree labels, `Project:`/`Project evidence:`, `Certification:`/`Certificate:`, and work lines such as `Intern: 2025-01 to 2025-06 Java`. Dates use `YYYY-MM`; ambiguous text remains for manual review. No language detection or truth verification is claimed.

## Automated checks

```powershell
. .\scripts\dev-env.ps1
mvn -o test
mvn -o '-Dsmarthire.integration=true' test
mvn -o package -DskipTests
```

The CV extractor runs the shared skill-phrase fixture, keeping Java/JavaScript and C/C++ distinct. MySQL tests exercise real PDF/DOCX uploads, confirmed corrections, replacement/cancellation, immutable originals, review edits, restart persistence, frozen ongoing dates, validation, stale drafts, role/ownership/disabled-account checks, missing bytes, and a failure after all replacement writes but before commit. These use `smarthire_test` and temporary document directories. The existing document tests cover streaming limits, interrupted copies, and uncertain commits.
