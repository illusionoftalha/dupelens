# Support

## Where to ask

| Request | Route |
| --- | --- |
| Usage help | Read the [README](../README.md) and troubleshooting below, then open a usage-question issue. |
| Reproducible bug | Use the [bug report form](https://github.com/illusionoftalha/dupelens/issues/new?template=bug_report.yml). |
| Improvement | Search [open issues](https://github.com/illusionoftalha/dupelens/issues), then use the feature-request form. |
| Vulnerability | Use the private contact in [SECURITY.md](SECURITY.md). |
| Conduct concern | Follow the [Code of Conduct](CODE_OF_CONDUCT.md). |

DupeLens is maintained by volunteers. Response and delivery dates are not guaranteed. Keep one topic per issue and use disposable fixtures.

## Troubleshooting

### Java is missing or too old

Run `java -version`. DupeLens requires Java 17 or newer. Compilation also needs `javac` from a JDK; check `javac -version` before running tests. Restart your terminal after changing Java or PATH.

### The JAR cannot be found

Run from the directory containing the downloaded JAR, or pass its full path. Quote paths containing spaces. Download the release asset itself: GitHub's source-code ZIP is not a runnable JAR.

### An HTML report already exists

Choose a new output filename. The CLI intentionally refuses to overwrite an existing file, even an earlier report.

### A saved report has no Delete button

Static HTML exports are read-only. Start `dupelens-review-<version>.jar` for selection and Trash actions. Keep the Java process running while using the browser.

### Trash is unavailable or a file is skipped

Support depends on the OS, JDK, and desktop session. Headless environments may not support Trash. Deletion is disabled rather than falling back to permanent deletion. Rescan after external file changes instead of retrying stale selections.

### Windows cannot establish a loopback connection

Some Windows/JDK environments have trouble creating sockets in their default temporary directory. Try the writable socket-directory command in the [README](../README.md#browser-review-and-deletion). The directory must already exist and be writable.

### Recovered space differs from the estimate

Estimates count logical bytes, not allocated filesystem blocks. Compression, snapshots, storage deduplication, and Trash retention affect free space. Trashed files may keep using space until the system removes them.

## What to include

Provide the release version or source commit, OS, Java version, command, expected result, actual result, and minimal reproduction. Redact usernames, private paths, file contents, and session URLs. Review scan reports and logs for private information before attaching them.
