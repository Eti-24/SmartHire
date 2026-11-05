# Repository Guidelines

## Required PIV loop

1. **Plan.** Before editing, state the intended outcome, check prerequisites, and identify observable acceptance criteria and the checks that will verify them.
2. **Implement.** Make the smallest change that meets the plan. Add or update tests for uncovered behavior or regressions. Revisit the plan if scope changes.
3. **Validate.** Run the checks, compare results with each criterion, and review the full change for unintended effects. Return to Implement for fixes and rerun affected checks. Mark work complete only when required checks pass. At handoff, report results and disclose anything unverified.

## Project structure

SmartHire is a Java 21 Swing and MySQL demo with a root Maven build. Before continuing development, read `docs/implementation-status.md` for delivered slices and unverified acceptance checks.

- Use `CONTEXT.md` for domain terms and `docs/spec-draft.md` for matching, permissions, and application rules. Check ticket prerequisites in `docs/build-plan-draft.md` before starting a slice.
- Consult `docs/environment-setup.md` for installed tools and database setup. Keep portable tooling in `.tools/` and private credentials and database files in `.local/`. Keep both directories out of commits.
- Keep application code in `src/main/java`, JUnit tests in `src/test/java`, and fictional PDF/DOCX fixtures in `src/test/resources`.

## Development commands

Use PowerShell 7 from the root.

| Command | Purpose |
| --- | --- |
| `. .\scripts\dev-env.ps1` | Select the project-local JDK, Maven, and dependency cache for this shell. |
| `.\scripts\start-mysql.ps1` | Start the local database. |
| `.\scripts\cache-dependencies.ps1` | Run the setup smoke check with MySQL running. |
| `.\scripts\start-netbeans.ps1` | Open the configured IDE. |
| `.\scripts\stop-mysql.ps1` | Shut down the database. |

Use `mvn test` for unit tests and `mvn package` to build. For database changes run `scripts/verify.ps1`; for fresh-schema/reproducibility changes add `-CleanDatabaseSmoke`. Read `docs/reproduce-demo.md` for prerequisites and disposable-schema cleanup. Follow `docs/demo-guide.md` for interactive acceptance.

## Coding style and architecture

Use four-space Java indentation, `UpperCamelCase` classes, and `lowerCamelCase` methods and variables. Keep role and ownership checks in application services and JDBC in repositories. Run document parsing and database work off the Swing event-dispatch thread. Update components on that thread.

## Testing

Name JUnit Jupiter classes for the behavior under test, such as `EligibilityTest`. Cover high-score mandatory failures, duplicate submissions, and immutable CV snapshots. Use `smarthire_test` for transaction and rollback checks. Report database checks separately from unit tests.

## Commits and pull requests

Until Git history establishes a convention, use imperative subjects such as `Preserve the submitted CV version`. PRs should identify the slice, behavior changed, checks run, and screenshots for visible Swing changes.
