# Changelog

## Unreleased

No changes yet.

## 1.3.0 — 2026-11-03

- Execute the dependency-free scanner and review safety suites during `mvn test` and `mvn package`, failing the build on errors.
- Add repeated `--exclude` folder options to both CLI and browser-review startup; skip matched subtrees before scanning.

- Simplify the repository root, group community policies under `.github`, and refresh README navigation and the documentation index.
- Use IEC size labels consistently in reports and browser selections; add formatter boundary tests (PR #6 and follow-up).
- Add community conduct, private security reporting, support, architecture, and maintainer documentation.
- Improve contributor onboarding, issue forms, PR templates, ownership, and editor settings.
- Pin workflow actions, check packaged class boundaries and documentation links, exercise native OS test scripts, and schedule build-tool update proposals.
- Extract release notes from the matching version heading so Unreleased notes cannot leak into older releases.

## 1.2.1 — 2026-11-02

- Add **Select all extra copies** across every duplicate group, including groups hidden by search, while preserving each group's chosen keeper.
- Support selections larger than 500 files with sequential requests after one explicit confirmation. Each request retains the existing server-side validation and keeper protection.
- Disable bulk selection when Trash is unavailable, all eligible copies are selected, or an operation is in progress.

## 1.2.0 — 2026-11-02

- Replace the decorative report mockup with a compact review workspace, readable file rows, and responsive layouts.
- Add a separate browser-review JAR with search, explicit keeper selection, selection summaries, and a confirmation dialog listing files and retained copies.
- Move selected duplicate copies to the system Recycle Bin/Trash only after confirmation. No permanent deletion fallback; disable deletion when Trash is unsupported.
- Protect a remaining copy server-side, verify file bytes, and recheck size, modification time, identity, and resolved paths immediately before each move.
- Bind browser review to loopback with private session URLs, origin/host checks, request tokens, and a downloadable session action log.
- Add isolated safety and HTTP regression checks to both test scripts. The CLI JAR contains only read-only scanner/report classes; static reports remain script-free.

Run browser review: `java -jar dupelens-review-1.2.0.jar "/path/to/folder"`. Keep the terminal running while reviewing files.

## 1.1.0 — 2026-11-02

- Redesign the offline HTML report with a light layout, dark banner, scan summary, and expandable duplicate cards.
- Show readable redundant sizes, group filenames, copy counts, and full file paths.
- Support narrow screens and keyboard navigation without JavaScript or external assets.
- Add report regression checks for summary values, filenames, and empty results.
- Publish a runnable Java 17 JAR and SHA-256 checksum through a tested release workflow.

The CLI remains read-only. Scanner behavior and command syntax are unchanged.

## 1.0.0

Initial read-only duplicate scanner and offline HTML report.
