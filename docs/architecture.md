# Architecture

DupeLens is one Java 17 Maven project with two executable JARs and no runtime dependencies.

| Source | Responsibility |
| --- | --- |
| [Main.java](../src/main/java/dev/dupelens/Main.java) | Read-only CLI, traversal, size grouping, SHA-256 hashing, sorted results, HTML rendering. |
| [Review.java](../src/main/java/dev/dupelens/Review.java) | Browser entry point, loopback server, session state, byte verification, Trash adapter, CSV log, embedded JavaScript. |
| [SmokeTest.java](../src/test/java/dev/dupelens/SmokeTest.java) | Scanner/static-report integration checks. |
| [ReviewTest.java](../src/test/java/dev/dupelens/ReviewTest.java) | Keeper, stale-file, byte-verification, fake-Trash, and HTTP boundary checks. |
| [pom.xml](../pom.xml) | Compilation and separate CLI/review manifests and class contents. |

## Scan flow

1. Skip explicitly excluded directory subtrees, then walk regular files without following symlinks, group paths by size, and track identities to avoid counting hard links twice where supported.
2. Hash only size buckets with multiple candidates. SHA-256 reads 64 KiB chunks; metadata is checked around hashing.
3. Group fingerprints, check aliases with `Files.isSameFile`, and sort by redundant logical bytes.
4. Return immutable result lists. The CLI prints them and can write a new script-free HTML report with `CREATE_NEW`.

## Review flow

Review reuses the scanner, captures file snapshots, and starts the JDK HTTP server on an ephemeral port at `127.0.0.1`. A private session path, request token, and script nonce belong to each process. The browser submits numeric IDs; arbitrary client-supplied paths are not accepted.

The UI starts with no deletion selection and a chosen keeper per group. Confirmation lists selected paths and retained copies. Large selections use serial requests of up to 500 IDs; request errors stop further requests. The server independently rejects unknown/stale IDs, duplicate IDs, and selections with no available keeper.

Before each Trash call, file and keeper snapshots are validated, bytes are compared through streams opened without following final-component symlinks, and metadata is checked again. Failures skip the file. Tests inject a fake `Trash`; there is no permanent-delete path.

Mutation requests validate host, origin, and token. The server uses content/framing headers and no remote assets. The CSV action log remains in memory until shutdown.

## Packaging and limitations

`target/dupelens.jar` includes only `Main` and its nested classes. `target/dupelens-review.jar` starts `Review` and includes both application classes. [check_artifacts.py](../scripts/check_artifacts.py) verifies these boundaries after packaging. `Main` does not reference `Review`, preserving single-source execution.

Scanning is not an atomic snapshot; verification and Trash are separate operations. External writers can race the final move. File keys and Trash vary by platform. Path metadata grows with file count; stream buffers are bounded. CLI hash matches are not byte-verified until a review move requests it.

Image similarity, wildcard exclusions, cancellation, and persistent logs are not implemented. The [issue tracker](https://github.com/illusionoftalha/dupelens/issues) is the source of truth for planned work. Use [SECURITY.md](../.github/SECURITY.md) for vulnerabilities.
