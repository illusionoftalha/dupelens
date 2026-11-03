package dev.dupelens;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;

/** No native trash operations: only isolated fixtures and an injected test Trash. */
public final class ReviewTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void rejected(Runnable operation, String message) {
        try { operation.run(); throw new AssertionError(message); } catch (IllegalArgumentException expected) { }
    }
    static final class Fixture implements AutoCloseable {
        final Path root = Files.createTempDirectory("dupelens-review-test");
        final Path a = root.resolve("original.txt"), b = root.resolve("copy.txt"), c = root.resolve("third.txt");
        final Path bin = Files.createDirectory(root.resolve("test-trash"));
        int moves;
        boolean support = true, refuse, changeKeeperAfterMove;
        final Review review;
        Fixture() throws Exception { this(true); }
        Fixture(boolean supported) throws Exception {
            support = supported;
            Files.writeString(a, "same"); Files.writeString(b, "same"); Files.writeString(c, "same");
            review = new Review(new Main.Scan(List.of(new Main.Group(4, List.of(a, b, c))), List.of(), 3), new Review.Trash() {
                public boolean supported() { return support; }
                public boolean move(Path path) throws java.io.IOException {
                    moves++;
                    if (refuse) return false;
                    Files.move(path, bin.resolve(path.getFileName()));
                    if (changeKeeperAfterMove) Files.writeString(a, "diff");
                    return true;
                }
            });
        }
        public void close() throws Exception {
            review.close();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        try (Fixture f = new Fixture()) {
            check(f.moves == 0, "scan must not move files");
            rejected(() -> f.review.moveSelected(List.of("0:0", "0:1", "0:2")), "all copies selected");
            rejected(() -> f.review.moveSelected(List.of("../../outside")), "arbitrary path accepted");
            rejected(() -> f.review.moveSelected(List.of("0:1", "0:1")), "duplicate request accepted");
            check(f.moves == 0, "invalid requests moved files");
            check(f.review.moveSelected(List.of("0:1", "0:2")).startsWith("Moved 2"), "valid selection failed");
            check(Files.exists(f.a) && !Files.exists(f.b) && !Files.exists(f.c), "keeper or selected copies incorrect");
            rejected(() -> f.review.moveSelected(List.of("0:0")), "last remaining copy accepted");
            rejected(() -> f.review.moveSelected(List.of("0:1")), "repeated deletion accepted");
            check(f.moves == 2 && Files.exists(f.a), "keeper protection bypassed");
        }
        try (Fixture f = new Fixture()) {
            Files.writeString(f.b, "changed");
            check(f.review.moveSelected(List.of("0:1")).contains("Skipped 1"), "stale size accepted");
            check(f.moves == 0 && Files.exists(f.b), "stale file moved");
        }
        try (Fixture f = new Fixture()) {
            FileTime time = Files.getLastModifiedTime(f.b);
            Files.writeString(f.b, "diff"); Files.setLastModifiedTime(f.b, time);
            check(f.review.moveSelected(List.of("0:1")).contains("Skipped 1"), "forged same-size/hash group accepted");
            check(f.moves == 0, "byte comparison bypassed");
        }
        try (Fixture f = new Fixture()) {
            Files.delete(f.a);
            check(f.review.moveSelected(List.of("0:1")).contains("Skipped 1"), "missing keeper accepted");
            check(f.moves == 0 && Files.exists(f.b), "file moved without keeper");
        }
        try (Fixture f = new Fixture()) {
            Files.setLastModifiedTime(f.b, FileTime.fromMillis(Files.getLastModifiedTime(f.b).toMillis() + 5000));
            check(f.review.moveSelected(List.of("0:1")).contains("Skipped 1"), "mtime change accepted");
            check(f.moves == 0, "mtime change moved");
        }
        try (Fixture f = new Fixture()) {
            f.support = false;
            rejected(() -> f.review.moveSelected(List.of("0:1")), "unsupported trash accepted");
            check(f.moves == 0, "unsupported trash called");
        }
        try (Fixture f = new Fixture()) {
            f.refuse = true;
            check(f.review.moveSelected(List.of("0:1")).contains("Skipped 1"), "refusal not reported");
            check(Files.exists(f.a) && Files.exists(f.b), "permanent deletion fallback");
        }
        try (Fixture f = new Fixture()) {
            f.changeKeeperAfterMove = true;
            String result = f.review.moveSelected(List.of("0:1", "0:2"));
            check(result.startsWith("Moved 1") && result.contains("Skipped 1"), "keeper was not rechecked between moves");
            check(f.moves == 1 && Files.exists(f.a) && Files.exists(f.c), "batch continued with changed keeper");
        }
        try (Fixture f = new Fixture()) {
            try {
                Files.delete(f.b);
                Files.createSymbolicLink(f.b, f.a);
                check(f.review.moveSelected(List.of("0:1")).contains("Skipped 1"), "symlink replacement accepted");
                check(f.moves == 0 && Files.exists(f.a), "symlink moved");
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
                System.out.println("Symlink fixture unavailable on this platform: " + e.getClass().getSimpleName());
            }
        }
        try (Fixture f = new Fixture(false)) {
            HttpClient client = HttpClient.newHttpClient();
            var page = client.send(HttpRequest.newBuilder(f.review.uri()).GET().build(), HttpResponse.BodyHandlers.ofString());
            check(page.body().contains("Deletion unavailable:") && page.body().contains("CAN_TRASH=false"), "unsupported Trash UI missing");
            rejected(() -> f.review.moveSelected(List.of("0:1")), "initially unsupported Trash accepted");
            check(f.moves == 0, "unsupported native Trash invoked");
        }
        try (Fixture f = new Fixture()) {
            HttpClient client = HttpClient.newHttpClient();
            URI url = f.review.uri();
            String origin = "http://" + url.getAuthority();
            String token = url.getPath().split("/")[1];
            var page = client.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofString());
            check(page.statusCode() == 200 && page.body().contains("Delete selected"), "review page missing");
            check(page.headers().firstValue("Content-Security-Policy").orElse("").contains("frame-ancestors 'none'"), "frame protection missing");
            check(f.moves == 0, "GET page changed files");
            var getTrash = client.send(HttpRequest.newBuilder(url.resolve("trash")).GET().build(), HttpResponse.BodyHandlers.ofString());
            check(getTrash.statusCode() == 404 && f.moves == 0, "GET trash route moved files");
            for (String badOrigin : List.of("https://example.com", "null")) {
                var bad = client.send(HttpRequest.newBuilder(url.resolve("trash")).header("Origin", badOrigin)
                    .header("X-DupeLens-Token", token).header("Content-Type", "text/plain;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString("0:1")).build(), HttpResponse.BodyHandlers.ofString());
                check(bad.statusCode() == 403 && f.moves == 0, "cross-origin deletion accepted");
            }
            var missingToken = client.send(HttpRequest.newBuilder(url.resolve("trash")).header("Origin", origin)
                .header("Content-Type", "text/plain;charset=UTF-8").POST(HttpRequest.BodyPublishers.ofString("0:1")).build(), HttpResponse.BodyHandlers.ofString());
            check(missingToken.statusCode() == 403 && f.moves == 0, "tokenless deletion accepted");
            var arbitrary = client.send(HttpRequest.newBuilder(url.resolve("trash")).header("Origin", origin).header("X-DupeLens-Token", token)
                .header("Content-Type", "text/plain;charset=UTF-8").POST(HttpRequest.BodyPublishers.ofString("../../outside")).build(), HttpResponse.BodyHandlers.ofString());
            check(arbitrary.statusCode() == 400 && f.moves == 0, "unlisted path accepted over HTTP");
            var valid = client.send(HttpRequest.newBuilder(url.resolve("trash")).header("Origin", origin).header("X-DupeLens-Token", token)
                .header("Content-Type", "text/plain;charset=UTF-8").POST(HttpRequest.BodyPublishers.ofString("0:1")).build(), HttpResponse.BodyHandlers.ofString());
            check(valid.statusCode() == 200 && f.moves == 1 && Files.exists(f.a), "confirmed HTTP request failed");
            var log = client.send(HttpRequest.newBuilder(url.resolve("actions.csv")).GET().build(), HttpResponse.BodyHandlers.ofString());
            check(log.body().contains("trashed") && log.body().contains(f.b.toString()), "action log missing");
            var reloaded = client.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofString());
            check(reloaded.body().contains("REMOVED='0:1'"), "reload lost successful action state");
        }
        try (Fixture f = new Fixture()) {
            Path excluded = Files.createDirectories(f.root.resolve("node_modules"));
            Files.writeString(excluded.resolve("excluded-copy.txt"), "same");
            Main.Options options = Main.options(new String[] {f.root.toString(), "--exclude", "node_modules"}, false);
            Main.Scan filtered = Main.scan(options.directory(), options.exclusions());
            check(filtered.files() == 3 && filtered.groups().get(0).files().size() == 3,
                    "review scan must exclude selected folders");
            try (Review review = new Review(filtered, new Review.Trash() {
                public boolean supported() { return true; }
                public boolean move(Path path) { throw new AssertionError("page request must not move files"); }
            })) {
                var page = HttpClient.newHttpClient().send(HttpRequest.newBuilder(review.uri()).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                check(page.statusCode() == 200 && !page.body().contains("excluded-copy.txt"),
                        "excluded copies must not appear in browser review");
                rejected(() -> review.moveSelected(List.of("0:3")), "excluded copy received a selectable ID");
            }
        }
        System.out.println("All review safety and HTTP checks passed (test Trash only).");
    }
}
