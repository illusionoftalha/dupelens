# Maintainer guide

The current maintainer is [@illusionoftalha](https://github.com/illusionoftalha). CODEOWNERS requests reviews; it does not itself enable branch protection or guarantee response times.

## Triage and review

- Route vulnerabilities through [SECURITY.md](../.github/SECURITY.md) and conduct concerns through the [Code of Conduct](../.github/CODE_OF_CONDUCT.md).
- Confirm bugs with disposable fixtures. Use `bug`, `enhancement`, `help wanted`, and `good first issue` according to scope.
- Check existing work before accepting overlapping PRs. Close duplicates with a concise reason and links.
- Inspect a first-time contributor's diff, scripts, POM, and workflows before approving waiting CI. Never expose secrets to fork code.
- Test integration with current `main`, preserve contributor authorship, and use descriptive merge messages.
- Require relevant tests and passing CI for executable changes. Check JAR boundaries when changing packaging.

## Validation

```sh
python scripts/check_repository.py
sh scripts/test.sh
mvn --batch-mode clean package
python scripts/check_artifacts.py
```

Use `./scripts/test.ps1` on Windows. UI changes need browser checks for keepers, global selection including hidden groups, confirmation/cancellation, and narrow screens. Normal automated tests must use fake Trash. A native Trash smoke test may use only a deliberately created disposable file.

Maven's test-entry limitation is documented in [CONTRIBUTING.md](../.github/CONTRIBUTING.md) and tracked in issue #2. A zero-test Surefire summary is not evidence that checks ran.

## Releases

1. Choose a version; update `pom.xml`, README download examples, and `CHANGELOG.md`. Move relevant Unreleased notes under a dated version heading; leave historical notes intact.
2. Run validation and `git diff --check`. Check direct CLI source execution and both JAR entry points.
3. Commit and push the release revision, create an annotated `v<version>` tag there, and push it. Never move a published tag.
4. The release workflow tests Java 17/21 on all three operating systems before publishing both JARs and `SHA256SUMS.txt`. Notes come from the matching changelog heading, not Unreleased.
5. Verify the public page, asset names, checksums, and workflow result. Use disposable fixtures for release smoke tests.

Investigate failures before retrying. The manual workflow accepts an existing tag that has no published release. If a public release already exists, publish a new version rather than silently replacing its assets.

## Routine upkeep

Review monthly Dependabot updates for Maven build plugins and GitHub Actions. Actions are pinned by commit SHA with version comments; updates still require review. Keep permissions minimal. Do not execute contributor code through `pull_request_target` with write permissions.

Keep support docs, templates, privacy guidance, and examples aligned with actual behavior. The contact email is published with the maintainer's permission. Avoid automatic stale-issue closure and support timelines the project cannot maintain.
