package dev.dupelens;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Dependency-free integration checks; run with scripts/test.ps1 or scripts/test.sh. */
public final class SmokeTest {
    private record CliResult(int exitCode, String output) {}

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static String sizeLabel(long bytes) { return Main.readable(bytes); }

    private static CliResult runCli(String... args) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Main.class.getName());
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new CliResult(process.waitFor(), output);
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("dupelens-test");
        try {
            Files.writeString(root.resolve("one.txt"), "same");
            Files.writeString(root.resolve("two.txt"), "same");
            Files.writeString(root.resolve("different.txt"), "diff");
            Files.createFile(root.resolve("empty1"));
            Files.createFile(root.resolve("empty2"));
            var scan = Main.scan(root);
            check(scan.groups().size() == 2, "same-size different contents must not match");
            check(scan.groups().get(0).reclaimable() == 4, "reclaimable bytes");
            check(scan.files() == 5, "file count");
            check(Files.readString(root.resolve("one.txt")).equals("same"), "source preserved");
            String html = Main.html(new Main.Scan(List.of(), List.of("<script> & " + (char) 34), 0));
            check(!html.contains("<script>"), "HTML escaping");
            check(html.contains("&lt;script&gt;"), "escaped warning");
            check(html.contains("No duplicates found."), "empty report");
            String report = Main.html(new Main.Scan(List.of(
                new Main.Group(1887436, List.of(Path.of("hero&.png"), Path.of("backup.png")))),
                List.of(), 12480));
            check(report.contains("<details open>"), "expandable group");
            check(report.contains("hero&amp;.png"), "escaped filename and path");
            check(report.contains("12,480"), "formatted scan count");
            check(report.contains("1.8 MiB"), "readable redundant size");
            check(sizeLabel(1023).equals("1023 B"), "bytes below one KiB keep the byte unit");
            check(sizeLabel(1024).equals("1.0 KiB"), "one KiB boundary");
            check(sizeLabel(1024 * 1024).equals("1.0 MiB"), "one MiB boundary");
            check(sizeLabel(1024 * 1024 - 1).equals("1024.0 KiB"), "just below one MiB stays KiB");
            check(report.contains("2 files"), "group file count");
            check(!report.contains("<script") && !report.contains("src=\"http"), "offline report");
            try { Main.scan(root.resolve("missing")); throw new AssertionError("missing directory accepted"); }
            catch (java.io.IOException expected) { }
            try {
                Files.createLink(root.resolve("hardlink"), root.resolve("one.txt"));
                check(Main.scan(root).groups().get(0).files().size() == 2, "hard links counted twice");
            } catch (UnsupportedOperationException | java.io.IOException ignored) { }

            Path input = root.resolve("cli input with spaces");
            Files.createDirectory(input);
            Path firstInput = input.resolve("first file.txt");
            Path secondInput = input.resolve("second file.txt");
            byte[] inputBytes = "identical input bytes".getBytes(StandardCharsets.UTF_8);
            Files.write(firstInput, inputBytes);
            Files.write(secondInput, inputBytes);

            Path existingReport = root.resolve("existing report.html");
            byte[] existingBytes = "keep this existing report\n".getBytes(StandardCharsets.UTF_8);
            Files.write(existingReport, existingBytes);
            CliResult existing = runCli(input.toString(), "--html", existingReport.toString());
            check(existing.exitCode() != 0, "existing report must make CLI exit nonzero: " + existing.output());
            check(Arrays.equals(Files.readAllBytes(existingReport), existingBytes), "existing report must remain byte-for-byte unchanged");

            byte[] firstBefore = Files.readAllBytes(firstInput);
            CliResult samePath = runCli(input.toString(), "--html", firstInput.toString());
            check(samePath.exitCode() != 0, "input path used as report must make CLI exit nonzero: " + samePath.output());
            check(Arrays.equals(Files.readAllBytes(firstInput), firstBefore), "input path used as report must remain unchanged");

            Path freshReport = root.resolve("fresh report.html");
            CliResult fresh = runCli(input.toString(), "--html", freshReport.toString());
            check(fresh.exitCode() == 0, "fresh report should succeed: " + fresh.output());
            check(Files.isRegularFile(freshReport), "fresh report should be created");
            check(Files.readString(freshReport).contains("Duplicate review"), "fresh report should contain the HTML report");

            CliResult missingDirectory = runCli(root.resolve("missing input directory").toString());
            check(missingDirectory.exitCode() != 0, "missing directory should make CLI exit nonzero");
            CliResult invalidArguments = runCli(input.toString(), "--html");
            check(invalidArguments.exitCode() != 0, "invalid arguments should make CLI exit nonzero");
            check(Arrays.equals(Files.readAllBytes(firstInput), inputBytes), "first scanned file must remain unchanged");
            check(Arrays.equals(Files.readAllBytes(secondInput), inputBytes), "second scanned file must remain unchanged");
            System.out.println("All integration checks passed.");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
