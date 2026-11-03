# Contributing to DupeLens

Code, documentation, tests, accessibility feedback, and reproducible bug reports are welcome. Participation follows the [Code of Conduct](CODE_OF_CONDUCT.md).

## Before you start

Search existing issues and pull requests. [Good first issues](https://github.com/illusionoftalha/dupelens/labels/good%20first%20issue) suit newcomers; [help wanted](https://github.com/illusionoftalha/dupelens/labels/help%20wanted) marks other opportunities. Comment with your approach to avoid duplicate effort. An unassigned issue is not a guarantee that nobody is working on it.

Discuss substantial features or file-operation changes in an issue first. Keep each PR focused; avoid unrelated reformatting and generated files.

## Setup and validation

Use JDK 17 or newer. Maven packages the two JARs; the test scripts need no Maven or test framework. Python 3.10+ is used only for repository checks.

```sh
git clone https://github.com/illusionoftalha/dupelens.git
cd dupelens
git switch -c fix/short-description
sh scripts/test.sh
mvn --batch-mode package
python scripts/check_repository.py
python scripts/check_artifacts.py
```

On Windows run `./scripts/test.ps1` instead of the shell test script. See [SUPPORT.md](SUPPORT.md) for socket troubleshooting.

Both `mvn test` and `mvn package` run `SmokeTest.main()` and `ReviewTest.main()` in separate JVMs through the build-only Exec plugin. Assertion failures and nonzero exits fail the build. Surefire is disabled because these suites use main methods, not a test framework. The standalone test scripts still work without Maven. `-DskipTests` deliberately skips both suites and is not validation evidence. Python 3.10+ is used only for repository and packaging checks.

## Project boundaries

- Keep the CLI and static reports read-only. The CLI JAR must exclude review/deletion classes.
- Preserve Java 17 compatibility, zero runtime dependencies, and direct execution of `Main.java`.
- Keep file-moving code in `Review.java`, behind explicit confirmation and server-side checks.
- Never add a permanent-delete fallback or permit moving the last available copy.
- Keep app processing local: no telemetry, remote assets, or remote services.
- Document new options, defaults, limits, and behavior changes. Do not claim untested support.

See [docs/architecture.md](../docs/architecture.md) for the structure and limitations.

## Tests and review

Add focused regression checks for changed behavior. Use temporary fixtures, never real Downloads, Pictures, or other user files. Automated review tests use the injected fake Trash and must not call native Trash. Platform-specific symlink fixtures may be skipped when the OS cannot create them; report skips honestly.

For file operations, cover keeper protection, stale metadata, mismatched bytes, and failures. For UI changes, check keyboard use, narrow screens, selection, and confirmation/cancellation as relevant. State which browser/manual checks actually ran.

CI checks documentation and tests/packages Java 17/21 on Linux, macOS, and Windows. Windows exercises the PowerShell script; other systems use the shell script.

## Pull requests

Explain the problem, resulting behavior, related issue, and validation. Include screenshots for visible UI changes using synthetic paths. Maintainers may ask for revisions or close duplicates with links to existing fixes.

Use descriptive commits and preserve accurate authorship. Do not include secrets, private reports, session URLs, JARs, or unrelated editor files. Contributions use the [MIT license](../LICENSE).

Report vulnerabilities through [SECURITY.md](SECURITY.md), not public issues. Maintainer procedures are in [docs/maintaining.md](../docs/maintaining.md).
