package dev.dupelens;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** Read-only duplicate discovery. Symbolic links are never followed. */
public final class Main {
    public record Group(long size, List<Path> files) {
        public long reclaimable() { return size * (files.size() - 1); }
    }
    public record Scan(List<Group> groups, List<String> warnings, long files) {}

    record Options(Path directory, Path report, List<Path> exclusions) {}

    static Options options(String[] args, boolean allowReport) {
        if (args.length == 0 || args[0].startsWith("--"))
            throw new IllegalArgumentException("Expected a directory before options");
        Path report = null;
        List<Path> exclusions = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            String option = args[i];
            if ((!option.equals("--exclude") && !(allowReport && option.equals("--html")))
                    || i + 1 == args.length || args[i + 1].startsWith("--"))
                throw new IllegalArgumentException("Expected --exclude <folder>" + (allowReport ? " or --html <new-report.html>" : ""));
            String value = args[++i];
            if (option.equals("--exclude")) exclusions.add(exclusion(value));
            else {
                if (report != null) throw new IllegalArgumentException("--html may only be used once");
                report = Path.of(value);
            }
        }
        return new Options(Path.of(args[0]), report, List.copyOf(exclusions));
    }

    private static Path exclusion(String value) {
        // Accept portable folder names or slash-separated paths relative to the scan root.
        String portable = value.replace('\\', '/');
        if (portable.isBlank() || portable.startsWith("/") || portable.contains(":")
                || portable.matches(".*[?*\\[\\]{}].*"))
            throw new IllegalArgumentException("Exclusions must be folder names or relative paths, without wildcards");
        for (String part : portable.split("/")) {
            if (part.equals("..") || part.equals("."))
                throw new IllegalArgumentException("Exclusions cannot contain . or .. segments");
        }
        return Path.of(portable).normalize();
    }

    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("--help")) {
            System.out.println("DupeLens — find duplicate files, change nothing.\nUsage: java -jar dupelens.jar <directory> [--exclude <folder>]... [--html <report.html>]\nA folder name matches at any depth; a relative path matches from the scan root. No folders are excluded by default.");
            return;
        }
        try {
            Options options = options(args, true);
            Scan scan = scan(options.directory(), options.exclusions());
            long bytes = scan.groups.stream().mapToLong(Group::reclaimable).sum();
            System.out.printf("Scanned %d files | %d duplicate groups | %,d redundant bytes%n", scan.files, scan.groups.size(), bytes);
            for (Group group : scan.groups) {
                System.out.printf("%n%,d bytes each (%d copies)%n", group.size, group.files.size());
                group.files.forEach(p -> System.out.println("  " + p));
            }
            scan.warnings.forEach(w -> System.err.println("Warning: " + w));
            if (options.report() != null) {
                Path report = options.report().toAbsolutePath();
                // Never replace an existing file, even if it happens to be an input.
                Files.writeString(report, html(scan), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                System.out.println("\nReport: " + report);
            }
        } catch (Exception e) {
            System.err.println("DupeLens: " + e.getMessage());
            System.exit(1);
        }
    }

    public static Scan scan(Path root) throws IOException {
        return scan(root, List.of());
    }

    public static Scan scan(Path root, List<Path> excludedFolders) throws IOException {
        root = root.toAbsolutePath().normalize();
        final Path scanRoot = root;
        final List<Path> exclusions = excludedFolders.stream().map(p -> exclusion(p.toString())).toList();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Not a directory: " + root);
        Map<Long, List<Path>> sizes = new TreeMap<>();
        List<String> warnings = new ArrayList<>();
        Set<Object> identities = new HashSet<>();
        long[] count = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                if (!directory.equals(scanRoot)) {
                    Path relative = scanRoot.relativize(directory);
                    for (Path excluded : exclusions) {
                        if (excluded.getNameCount() == 1 ? directory.getFileName().equals(excluded) : relative.equals(excluded))
                            return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    count[0]++;
                    // Hard links already share storage; don't count them twice where file keys exist.
                    if (attrs.fileKey() != null && !identities.add(attrs.fileKey())) return FileVisitResult.CONTINUE;
                    sizes.computeIfAbsent(attrs.size(), ignored -> new ArrayList<>()).add(path);
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path path, IOException error) {
                warnings.add(path + ": " + error.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
        List<Group> groups = new ArrayList<>();
        for (var bucket : sizes.entrySet()) {
            if (bucket.getValue().size() < 2) continue;
            Map<String, List<Path>> hashes = new TreeMap<>();
            for (Path path : bucket.getValue()) {
                try {
                    BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!before.isRegularFile() || before.size() != bucket.getKey()) throw new IOException("file changed during scan");
                    String hash = digest(path);
                    BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                            || !Objects.equals(before.fileKey(), after.fileKey())) throw new IOException("file changed during scan");
                    // Windows providers may return null file keys. Check actual file identity
                    // within matching-content buckets before counting another physical copy.
                    List<Path> matches = hashes.computeIfAbsent(hash, ignored -> new ArrayList<>());
                    boolean alias = false;
                    for (Path existing : matches) {
                        if (Files.isSameFile(existing, path)) { alias = true; break; }
                    }
                    if (!alias) matches.add(path);
                } catch (IOException e) { warnings.add(path + ": " + e.getMessage()); }
            }
            for (List<Path> paths : hashes.values()) if (paths.size() > 1) {
                paths.sort(Comparator.comparing(Path::toString));
                groups.add(new Group(bucket.getKey(), List.copyOf(paths)));
            }
        }
        groups.sort(Comparator.comparingLong(Group::reclaimable).reversed().thenComparing(g -> g.files.get(0).toString()));
        return new Scan(List.copyOf(groups), List.copyOf(warnings), count[0]);
    }

    private static String digest(Path path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) md.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    static String readable(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB", "EiB"};
        double value = bytes;
        int unit = -1;
        do { value /= 1024; unit++; } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static String icon(String type) {
        String shape = switch (type) {
            case "folder" -> "<path d='M3 7h6l2 3h10v11H3z'/>";
            case "layers" -> "<path d='m12 3 10 5-10 5L2 8zM2 13l10 5 10-5M2 18l10 5 10-5'/>";
            case "image" -> "<rect x='3' y='3' width='18' height='18' rx='2'/><circle cx='8' cy='8' r='1'/><path d='m3 18 6-6 4 4 4-6 4 5'/>";
            default -> "<path d='M5 2h9l5 5v15H5zM14 2v6h5'/>";
        };
        return "<svg aria-hidden='true' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.7' stroke-linejoin='round' stroke-linecap='round'>" + shape + "</svg>";
    }

    /** Static exports have no deletion controls or scripts. */
    public static String html(Scan scan) { return html(scan, false, ""); }

    static String html(Scan scan, boolean live, String unavailable) {
        long bytes = scan.groups.stream().mapToLong(Group::reclaimable).sum();
        long copies = scan.groups.stream().mapToLong(g -> g.files.size() - 1).sum();
        StringBuilder out = new StringBuilder("""
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>DupeLens · Duplicate review</title><style>
            :root{color-scheme:light;--ink:#18212d;--muted:#657185;--line:#e1e6ec;--paper:#fff;--bg:#f4f6f9;--accent:#3159c9;--danger:#b52e3d}
            *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.5 "Segoe UI",Arial,sans-serif}button,input{font:inherit}a{color:inherit;text-decoration:none}button,a,input,summary{outline-offset:4px}button:focus-visible,a:focus-visible,input:focus-visible,summary:focus-visible{outline:3px solid #6c8be5}button{cursor:pointer}button:disabled{cursor:not-allowed;opacity:.45}svg{width:22px;height:22px;flex-shrink:0}button{border:1px solid var(--line);border-radius:7px;background:#fff;color:var(--ink);padding:8px 13px;font-weight:600}button:hover:not(:disabled){background:#f0f3f8}.danger{background:var(--danger);border-color:var(--danger);color:#fff}.danger:hover:not(:disabled){background:#972332}
            header{height:72px;background:#fff;border-bottom:1px solid var(--line);padding:0 36px;display:flex;align-items:center;justify-content:space-between;gap:20px}.brand{display:flex;align-items:center;gap:11px;font-size:21px;font-weight:750;letter-spacing:-.6px}.brand-icon{display:grid;place-items:center;width:34px;height:36px;background:#182536;border-radius:9px;color:white}.brand-icon svg{width:21px;height:21px}.header-meta{display:flex;align-items:center;gap:20px;color:var(--muted);font-size:12px}.status{display:flex;align-items:center;gap:7px}.status:before{content:"";height:6px;width:6px;border-radius:50%;background:#248468}.header-meta a{text-decoration:underline;text-underline-offset:4px}
            .layout{max-width:1550px;display:grid;grid-template-columns:225px minmax(0,1fr);margin:auto}.sidebar{padding:34px 22px;border-right:1px solid var(--line);min-height:calc(100vh - 72px)}.section-label{color:#8590a0;text-transform:uppercase;letter-spacing:1.4px;font-size:10px;font-weight:700;margin:0 12px 12px}.side-link{display:flex;align-items:center;justify-content:space-between;padding:10px 12px;border-radius:7px;color:#5b687b;margin:4px 0;font-weight:600}.side-link.active{background:#e7edf9;color:#2c4d9e}.side-count{font-size:11px;font-weight:700}.side-note{padding:20px 12px;color:var(--muted);font-size:12px;line-height:1.7;border-top:1px solid var(--line);margin-top:32px}.side-note strong{color:#37455a;font-size:12px;display:block;margin-bottom:6px}
            main{padding:35px 40px 100px;min-width:0}.eyebrow{font-size:10px;font-weight:700;letter-spacing:1.5px;color:#7b8799;margin-bottom:5px}h1{font-size:30px;letter-spacing:-1px;font-weight:700;line-height:1.25;margin:0 0 8px}.intro{margin:0;color:var(--muted);max-width:680px;font-size:14px}.heading{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;margin-bottom:27px}.tag{border:1px solid #dce4df;color:#39745b;border-radius:5px;background:#f0f7f3;font-size:11px;padding:5px 9px;white-space:nowrap;font-weight:600}
            .stats{display:grid;grid-template-columns:1.35fr 1fr 1fr;gap:15px;margin-bottom:28px}.stat{background:#fff;border:1px solid var(--line);padding:20px 23px;border-radius:10px}.stat.dark{background:#1a2738;color:white;border-color:#1a2738}.stat-label{font-size:11px;color:var(--muted);margin-bottom:7px}.dark .stat-label{color:#b4c1d2}.stat-value{font-size:30px;letter-spacing:-.8px;font-weight:650;line-height:1.2}.stat-foot{font-size:11px;color:#8792a2;margin-top:8px}.dark .stat-foot{color:#a9b8cd}.stat-value small{font-size:14px;font-weight:400;letter-spacing:0;color:#b9c8db}.notice{padding:13px 16px;border:1px solid #e3d8ba;border-radius:8px;background:#fffaf0;color:#745e31;margin:0 0 18px;font-size:12px}.notice a{text-decoration:underline}
            .toolbar{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:14px}.toolbar h2{font-size:16px;letter-spacing:-.2px;margin:0}.toolbar p{color:var(--muted);font-size:11px;margin:2px 0 0}.tools{display:flex;align-items:center;gap:8px}.search{width:260px;max-width:100%;background:white;border:1px solid #d8dfe9;border-radius:7px;padding:9px 12px;color:var(--ink)}.sort-label{font-size:11px;color:var(--muted);white-space:nowrap}.group{background:#fff;border:1px solid var(--line);border-radius:9px;margin:0 0 12px;overflow:hidden}.group.resolved{opacity:.65}summary{list-style:none;cursor:pointer;display:flex;align-items:center;gap:13px;padding:17px 20px}summary::-webkit-details-marker{display:none}summary:after{content:"";width:7px;height:7px;border-bottom:1.5px solid #7e899b;border-right:1.5px solid #7e899b;transform:rotate(-45deg);margin-left:12px;flex-shrink:0}details[open] summary:after{transform:rotate(45deg);margin-top:-4px}.file-icon{background:#eef2f9;color:#5e7496;border:1px solid #e4eaf4;border-radius:8px;width:37px;height:40px;display:grid;place-items:center;flex-shrink:0}.group-title{min-width:0;flex:1}.group-title h3{font-size:14px;margin:0 0 2px;font-weight:650;overflow-wrap:anywhere;letter-spacing:-.1px}.group-meta{font-size:11px;color:var(--muted)}.identical{color:#5c728c;font-size:10px;display:inline-block;margin-right:9px}.reclaim{text-align:right;white-space:nowrap;font-size:14px;font-weight:650}.reclaim small{display:block;font-size:10px;color:var(--muted);font-weight:400}.file-table{border-top:1px solid #edf0f5}.file-row{display:grid;grid-template-columns:minmax(0,1fr) 80px;gap:16px;align-items:center;padding:13px 20px 13px 70px}.file-row+.file-row{border-top:1px solid #edf0f5}.file-row.selected{background:#fff4f4}.file-row.kept{background:#f8fafc}.file-row.live-row{grid-template-columns:22px minmax(0,1fr) 70px 82px;padding-left:22px}.file-name{font-size:12px;font-weight:600;margin-bottom:2px;overflow-wrap:anywhere}.path{font:11px/1.6 Consolas,"Courier New",monospace;color:#748096;overflow-wrap:anywhere;word-break:break-word}.size{font-size:11px;color:var(--muted);text-align:right}.keep{font-size:11px;display:flex;align-items:center;gap:6px;cursor:pointer;color:#556478}.keep input{margin:0;accent-color:#3159c9}.pick{width:15px;height:15px;margin:0;accent-color:var(--danger)}.group-bottom{border-top:1px solid #edf0f5;padding:8px 20px;font-size:10px;color:#8390a2;display:flex;justify-content:space-between}.group-bottom button{padding:3px 8px;font-size:10px}.empty{padding:35px;text-align:center;background:#fff;border:1px solid var(--line);border-radius:9px;color:var(--muted)}[hidden]{display:none!important}.warnings{margin-top:30px;overflow-wrap:anywhere}.warnings h2{font-size:16px}.warnings ul{padding-left:20px;color:var(--muted);font-size:12px}footer{margin-top:28px;font-size:11px;color:#8791a0;line-height:1.8}
            .selection-bar{position:sticky;bottom:14px;z-index:5;background:#1b2738;color:#fff;border:1px solid #2e3d52;border-radius:10px;padding:14px 18px;margin-top:24px;display:flex;justify-content:space-between;gap:16px;align-items:center;box-shadow:0 8px 30px #1826381c}.selection-bar strong{font-size:13px;display:block}.selection-bar small{color:#b2bfd0;font-size:11px}.selection-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.selection-actions .clear{background:transparent;border-color:#526079;color:#d4dce8}.message{font-size:12px;margin:14px 0;color:#49607e;white-space:pre-wrap}.message.error{color:var(--danger)}dialog{width:min(640px,calc(100% - 30px));border:1px solid #dce1e9;border-radius:13px;padding:28px;box-shadow:0 24px 80px #0003;color:var(--ink)}dialog::backdrop{background:#0e1c32a6}dialog h2{font-size:21px;letter-spacing:-.4px;margin:0 0 10px}dialog p{color:var(--muted);font-size:13px}dialog h3{font-size:12px;margin:18px 0 8px}.confirmation-list{max-height:180px;overflow:auto;border:1px solid var(--line);border-radius:7px;padding:10px 12px;list-style:none}.confirmation-list li{font:11px/1.7 Consolas,monospace;overflow-wrap:anywhere;margin:5px 0}.dialog-actions{display:flex;justify-content:flex-end;gap:10px;margin-top:24px}.dialog-summary{background:#f4f6fa;border-radius:7px;padding:12px;font-size:13px}
            @media(min-width:1600px){main{padding-right:60px;padding-left:60px}}@media(max-width:1050px){.layout{grid-template-columns:180px minmax(0,1fr)}main{padding:28px 24px 70px}.sidebar{padding:28px 14px}.stats{gap:10px}.stat{padding:16px}.stat-value{font-size:26px}.toolbar{flex-wrap:wrap}.search{width:230px}}
            @media(max-width:720px){header{height:62px;padding:0 18px}.header-meta{gap:10px}.header-meta>a{display:none}.brand{font-size:19px}.layout{display:block}.sidebar{display:none}main{padding:25px 16px 50px}h1{font-size:26px}.heading{gap:10px}.tag{font-size:9px}.stats{grid-template-columns:1fr 1fr;gap:10px}.stat.dark{grid-column:1/-1;display:grid;grid-template-columns:1fr 1fr;align-items:center}.dark .stat-label{margin:0}.dark .stat-value{text-align:right}.dark .stat-foot{grid-column:1/-1}.stat-value{font-size:26px}.intro{font-size:13px}.tools{width:100%}.search{flex:1;min-width:0}.sort-label{font-size:10px}summary{padding:14px 12px;gap:10px}.reclaim{font-size:12px}.group-title h3{font-size:12px}.file-row{padding:12px 14px;gap:10px}.file-row.live-row{padding-left:12px;grid-template-columns:18px minmax(0,1fr) 60px}.live-row .size{display:none}.file-name{font-size:11px}.path{font-size:10px}.selection-bar{padding:12px;flex-wrap:wrap;bottom:8px}.selection-actions{width:100%;justify-content:flex-end}.selection-bar strong{font-size:12px}dialog{padding:20px}.group-meta{font-size:10px}}
            </style></head><body><header><a class="brand" href="#overview"><span class="brand-icon">
            """);
        out.append(icon("layers")).append("</span>DupeLens</a><div class='header-meta'><span class='status'>")
            .append(live ? "Local session" : "Offline report").append("</span><a href='#about'>About this scan</a></div></header>");
        out.append("<div class='layout'><aside class='sidebar'><p class='section-label'>Workspace</p><a class='side-link' href='#overview'>Overview</a><a class='side-link active' href='#groups'>Duplicate groups <span class='side-count'>")
            .append(scan.groups.size()).append("</span></a><a class='side-link' href='#warnings'>Scan warnings <span class='side-count'>")
            .append(scan.warnings.size()).append("</span></a><div class='side-note'><strong>")
            .append(live ? "Your files. Your choice." : "A snapshot of your files.")
            .append("</strong>").append(live ? "Choose a copy to keep, then select unwanted copies. Files move to Trash only after you confirm." : "This saved report is read-only. Use browser review mode to select and move duplicate copies to Trash.")
            .append("</div></aside><main id='overview'><div class='heading'><div><div class='eyebrow'>SCAN RESULTS</div><h1>Duplicate review</h1><p class='intro'>Review matching files, keep what matters, and make room for what’s next.</p></div><span class='tag'>Scan complete</span></div><section class='stats' aria-label='Scan summary'><div class='stat dark'><div class='stat-label'>Potential space to recover</div><div class='stat-value'>")
            .append(readable(bytes)).append("</div><div class='stat-foot'>Across ").append(String.format(Locale.ROOT, "%,d", copies))
            .append(" extra copies · estimated</div></div><div class='stat'><div class='stat-label'>Duplicate groups</div><div class='stat-value'>")
            .append(String.format(Locale.ROOT, "%,d", scan.groups.size())).append("</div><div class='stat-foot'>Sorted by redundant size</div></div><div class='stat'><div class='stat-label'>Files scanned</div><div class='stat-value'>")
            .append(String.format(Locale.ROOT, "%,d", scan.files)).append("</div><div class='stat-foot'>Matched by size + SHA-256</div></div></section>");
        if (live && !unavailable.isEmpty()) out.append("<div class='notice' role='status'>Deletion unavailable: ").append(escape(unavailable)).append("</div>");
        out.append("<section id='groups' aria-label='Duplicate groups'><div class='toolbar'><div><h2>Duplicate groups</h2><p>Expand a group to compare full file paths.</p></div><div class='tools'>");
        if (live) out.append("<input id='search' class='search' type='search' placeholder='Search names or paths…' aria-label='Search duplicate groups'>");
        out.append("<span class='sort-label'>Largest impact first ↓</span></div></div>");
        for (int gi = 0; gi < scan.groups.size(); gi++) {
            Group group = scan.groups.get(gi);
            String name = group.files.get(0).getFileName().toString();
            boolean image = name.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|gif|bmp|webp|svg)");
            out.append("<article class='group' data-group='").append(gi).append("'><details").append(gi < 3 ? " open" : "").append("><summary><span class='file-icon'>")
                .append(icon(image ? "image" : "file")).append("</span><div class='group-title'><h3>").append(escape(name))
                .append("</h3><div class='group-meta'><span class='identical'>Matching content</span><span class='copy-count'>")
                .append(group.files.size()).append(" files</span> · ").append(readable(group.size)).append(" each</div></div><div class='reclaim'>")
                .append(readable(group.reclaimable())).append("<small>redundant</small></div></summary><div class='file-table'>");
            for (int fi = 0; fi < group.files.size(); fi++) {
                Path path = group.files.get(fi);
                String id = gi + ":" + fi;
                out.append("<div class='file-row").append(live ? " live-row" : "").append("' data-file='").append(id).append("' data-size='").append(group.size).append("'>");
                if (live) out.append("<input class='pick' type='checkbox' aria-label='Select ").append(escape(path.toString())).append(" for deletion' ")
                    .append(fi == 0 || !unavailable.isEmpty() ? "disabled" : "").append(">");
                out.append("<div><div class='file-name'>").append(escape(path.getFileName().toString())).append("</div><div class='path'>")
                    .append(escape(path.toString())).append("</div></div><span class='size'>").append(readable(group.size)).append("</span>");
                if (live) out.append("<label class='keep'><input type='radio' name='keeper-").append(gi).append("' aria-label='Keep ")
                    .append(escape(path.toString())).append("' ").append(fi == 0 ? "checked" : "").append(">Keep</label>");
                out.append("</div>");
            }
            out.append("</div><div class='group-bottom'><span>Group ").append(String.format(Locale.ROOT, "%04d", gi + 1))
                .append(" · Review paths before choosing copies</span>");
            if (live) out.append("<button type='button' class='select-group' ").append(!unavailable.isEmpty() ? "disabled" : "").append(">Select other copies</button>");
            out.append("</div></details></article>");
        }
        if (scan.groups.isEmpty()) out.append("<div class='empty'>No duplicates found. Nothing to review.</div>");
        if (live) out.append("<div id='no-matches' class='empty' hidden>No matching groups. Try a different name or path.</div>");
        out.append("</section>");
        if (live) out.append("""
            <div class="message" id="message" role="status" aria-live="polite"></div>
            <div class="selection-bar"><div><strong id="selection-count">No files selected</strong><small>Selections span all groups. Each chosen keeper stays protected.</small></div><div class="selection-actions"><button class="clear" id="select-all" type="button" title="Select extra copies in every group, including groups hidden by search">Select all extra copies</button><button class="clear" id="clear" type="button">Clear</button><button class="danger" id="delete" type="button" disabled>Delete selected</button></div></div>
            <dialog id="confirm"><h2>Move selected copies to Trash?</h2><p>Only the files listed below will be moved to your system’s Recycle Bin or Trash. A matching copy will be kept in every affected group.</p><div class="dialog-summary" id="confirm-summary"></div><h3>Files to move</h3><ul class="confirmation-list" id="confirm-files"></ul><h3>Copies to keep</h3><ul class="confirmation-list" id="confirm-keepers"></ul><div class="dialog-actions"><button id="cancel-delete" type="button">Cancel</button><button class="danger" id="confirm-delete" type="button">Move to Trash</button></div></dialog>
            <noscript><p class="notice">JavaScript is required for selection and deletion. No files will be changed while it is disabled.</p></noscript>
            """);
        out.append("<section class='warnings' id='warnings'><h2>Scan warnings</h2>");
        if (scan.warnings.isEmpty()) out.append("<p class='intro'>No warnings reported.</p>");
        else {
            out.append("<ul>");
            scan.warnings.forEach(w -> out.append("<li>").append(escape(w)).append("</li>"));
            out.append("</ul>");
        }
        return out.append("</section><footer id='about'>").append(live ? "Local browser review. No files move until you select copies and confirm. Keep this Java session running. <a href='actions.csv'>Download action log (CSV)</a>." : "Read-only report. No files modified. Open browser review mode to manage copies.")
            .append("<br>Space estimates may differ from physical disk usage. Matching hashes are not proof; browser review verifies bytes before moving files. Reports contain full paths—review before sharing.</footer></main></div><!--REVIEW_SCRIPT--></body></html>").toString();
    }
}
