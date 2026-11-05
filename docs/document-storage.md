# Shared document lifecycle

`ManagedDocuments` is a storage adapter, not an authorization boundary. Invoke it off the Swing event-dispatch thread from application services that check the active role and ownership. `CvService` uses it for CV versions, `ApplicationService` for submitted CV availability/downloads, and `JobService` for circulars.

## Prepare, reference, and close

1. `prepare(source)` streams at most 5 MiB into a new UUID `.part` file. The selected extension must be PDF or DOCX; the actual bytes must parse as that format. Text is capped at 500,000 characters. OCR and language identification are not performed; supported content is English, text-based documents.
2. Parsing succeeds before an atomic rename to the final UUID `.pdf` or `.docx` file. A `Pending` handle exposes immutable metadata and extracted text. The original source path is never used as the durable reference. Each preparation creates a new identifier.
3. For a standalone reference, `store.commit(pending)` checks file integrity and commits its database metadata. For CV/job operations, call `pending.validate()`, then `JdbcDocumentReferences.insert(connection, pending.document())` alongside the owning CV version/circular row in the same transaction. The caller owns that connection's commit or rollback. Never commit a replacement as current before review is confirmed.
4. Always close the pending handle after the database transaction finishes. Close consults committed references: it removes only a copy without a reference. If database commit succeeded but the response was lost, the file is retained. If the reference lookup is unavailable, cleanup fails safely and retains bytes for later recovery.
5. A submission refers to its confirmed immutable CV version and uses `read(id)` to check availability; changing the source file or a student's current CV cannot change old document IDs. Missing or changed saved bytes fail with UNAVAILABLE and block new submissions that rely on them. Existing history remains readable as metadata.

Standalone document references have no application-owned record because slice 02a is the adapter foundation. `JdbcCvs` uses the shared-transaction insertion API to save a new document reference, confirmed review, and current-version pointer together. `JobService`/`JdbcJobs` use the same pattern for circular metadata, a retained job-owned circular version, the current circular pointer, and pending-approval status. Editing a CV review inserts another review version referencing the same immutable document; it never changes that document's metadata or bytes. Applications reference that immutable version and preserve submitted contact details and the original assessment separately. Circular replacements retain earlier job-owned document references; referenced historical bytes remain protected during reconciliation.

## Startup recovery

Open `.local/documents` with `JdbcDocumentReferences`, then call `reconcile()` before accepting uploads. `Main` performs this step. The exclusive file lock restricts the store to one active process. In-process pending handles are protected from cleanup.

Recovery first reads all committed IDs. If this fails, it does not delete anything. It scans only the dedicated store's top-level generated UUID filenames, removing unreferenced abandoned `.part`, `.pdf`, and `.docx` files. Unknown names and symbolic links are left alone. Any file whose ID is referenced is retained, including corrupt files. Missing or changed references are reported; startup prints these IDs without exposing credentials or document content.

Read checks compare byte length and SHA-256 against immutable database metadata. This detects accidental managed-file changes; it is not an access-control mechanism. Back up the database and managed files together. Do not modify or manually delete managed files.

## Failure contract

`DocumentFailure.code()` distinguishes UNSUPPORTED, TOO_LARGE, MALFORMED, ENCRYPTED, NO_TEXT, STORAGE, INTERRUPTED, UNAVAILABLE, and IN_USE. Failed imports do not alter an earlier version or reference. Copy or cleanup failure may leave an unreferenced generated file, which startup reconciliation can remove. The application never deletes referenced versions to recover from an error.

`src/test/resources/documents` contains fictional text PDF/DOCX, textless PDF/DOCX, and encrypted PDF fixtures. `FixtureWriter` regenerates them explicitly; ordinary tests consume the checked-in bytes. The parser tests use temporary stores. The database lifecycle test inserts and removes only its own UUIDs in `smarthire_test`.
