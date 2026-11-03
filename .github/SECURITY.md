# Security policy

## Supported versions

Security fixes target the latest stable release. Older releases are not maintained separately; upgrade through [GitHub Releases](https://github.com/illusionoftalha/dupelens/releases). The `main` branch may contain changes not yet in a published JAR.

## Private reporting

Email **muhammadtalhabinkhalid@gmail.com** with the subject **DupeLens security report**. Do not open a public issue for an exploitable vulnerability or attach real private files, credentials, or review-session URLs.

Include the affected version, OS and Java version, impact, and reproduction steps using disposable files and synthetic paths. A minimal proof of concept or proposed mitigation is helpful.

The maintainer will investigate and coordinate a fix and disclosure where appropriate. This is a volunteer project: response and release dates are not guaranteed, and there is no paid bug-bounty program. Please allow time for investigation before publishing exploit details.

## Boundaries and limitations

- The CLI JAR and saved HTML reports are read-only. Report creation refuses to overwrite an existing output file.
- The separate review JAR moves selected copies to system Trash only after explicit confirmation. It has no permanent-delete fallback.
- Review binds to `127.0.0.1` and checks the request host, origin, and session token. Do not share its private URL or expose it through a tunnel or reverse proxy.
- Byte and metadata checks happen before moving files. Verification and the native Trash operation are not atomic; races with external writers remain possible.
- Reports and action logs contain full file paths. Review before sharing. In-memory action logs disappear when review ends.
- There are no runtime dependencies, remote services, or telemetry. Build plugins and GitHub Actions are development dependencies and still require maintenance.

Ordinary bugs belong under [SUPPORT.md](SUPPORT.md). Conduct concerns follow the [Code of Conduct](CODE_OF_CONDUCT.md).
