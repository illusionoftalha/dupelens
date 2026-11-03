package dev.dupelens;

import com.sun.net.httpserver.*;
import java.awt.Desktop;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Explicitly opt-in, loopback-only browser review. Main remains a read-only CLI. */
public final class Review implements AutoCloseable {
    interface Trash {
        boolean supported();
        boolean move(Path path) throws IOException;
    }

    private static final class SystemTrash implements Trash {
        public boolean supported() {
            return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
        }
        public boolean move(Path path) { return Desktop.getDesktop().moveToTrash(path.toFile()); }
    }

    record Snapshot(Path path, Path real, long size, java.nio.file.attribute.FileTime modified, Object key) {
        static Snapshot capture(Path path, long expectedSize) throws IOException {
            Path normalized = path.toAbsolutePath().normalize();
            BasicFileAttributes attrs = Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile() || attrs.size() != expectedSize) throw new IOException("File changed since scan; rescan required");
            return new Snapshot(normalized, normalized.toRealPath(), attrs.size(), attrs.lastModifiedTime(), attrs.fileKey());
        }
        void validate() throws IOException {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile() || size != attrs.size() || !modified.equals(attrs.lastModifiedTime())
                    || !Objects.equals(key, attrs.fileKey()) || !real.equals(path.toRealPath()))
                throw new IOException("File or its path changed; rescan required");
        }
    }

    private record Entry(int group, Snapshot snapshot) {}
    private record Action(String status, String path, String reason) {}
    private final Main.Scan scan;
    private final Trash trash;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Set<String> removed = new LinkedHashSet<>();
    private final List<Action> actions = new ArrayList<>();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final String token;
    private final String nonce;
    private final String host;
    private final String origin;
    private final String basePath;
    private final String unavailable;

    Review(Main.Scan scan, Trash trash) throws IOException {
        this.scan = scan;
        this.trash = trash;
        this.unavailable = trash.supported() ? "" : "This system does not support moving files to Trash. No permanent-delete fallback is used.";
        for (int gi = 0; gi < scan.groups().size(); gi++) {
            Main.Group group = scan.groups().get(gi);
            for (int fi = 0; fi < group.files().size(); fi++) {
                String id = gi + ":" + fi;
                try { entries.put(id, new Entry(gi, Snapshot.capture(group.files().get(fi), group.size()))); }
                catch (IOException e) { actions.add(new Action("unavailable", group.files().get(fi).toString(), e.getMessage())); }
            }
        }
        token = randomToken();
        nonce = randomToken();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16);
        host = "127.0.0.1:" + server.getAddress().getPort();
        origin = "http://" + host;
        basePath = "/" + token + "/";
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    URI uri() { return URI.create(origin + basePath); }

    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("--help")) {
            System.out.println("Usage: java -jar dupelens-review.jar <directory> [--exclude <folder>]...\nA folder name matches at any depth; a relative path matches from the scan root. No folders are excluded by default.\nStarts a local browser session. Nothing is moved until you select files and confirm.");
            return;
        }
        try {
            Main.Options options = Main.options(args, false);
            System.out.println("Scanning " + options.directory().toAbsolutePath() + " …");
            Main.Scan scan = Main.scan(options.directory(), options.exclusions());
            Review review = new Review(scan, new SystemTrash());
            Runtime.getRuntime().addShutdownHook(new Thread(review::close));
            System.out.println("Open this private local session: " + review.uri());
            System.out.println("Keep this terminal running. Press Ctrl+C to end the session.");
            if (!review.unavailable.isEmpty()) System.out.println(review.unavailable);
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                    Desktop.getDesktop().browse(review.uri());
            } catch (Exception e) { System.out.println("Open the URL above in your browser."); }
        } catch (Exception e) {
            System.err.println("DupeLens Review: " + e.getMessage());
            System.exit(1);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!host.equals(exchange.getRequestHeaders().getFirst("Host"))) { respond(exchange, 403, "text/plain", "Invalid host"); return; }
            String path = exchange.getRequestURI().getRawPath();
            if (exchange.getRequestURI().getRawQuery() != null) { respond(exchange, 404, "text/plain", "Not found"); return; }
            if (exchange.getRequestMethod().equals("GET") && path.equals(basePath)) {
                respond(exchange, 200, "text/html", page());
            } else if (exchange.getRequestMethod().equals("GET") && path.equals(basePath + "actions.csv")) {
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=dupelens-actions.csv");
                respond(exchange, 200, "text/csv", log());
            } else if (exchange.getRequestMethod().equals("POST") && path.equals(basePath + "trash")) {
                if (!origin.equals(exchange.getRequestHeaders().getFirst("Origin"))
                        || !token.equals(exchange.getRequestHeaders().getFirst("X-DupeLens-Token"))
                        || !"text/plain;charset=UTF-8".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                    respond(exchange, 403, "text/plain", "Request rejected. Reload the local review page."); return;
                }
                byte[] body = exchange.getRequestBody().readNBytes(16385);
                if (body.length > 16384) { respond(exchange, 413, "text/plain", "Select fewer files per operation (maximum 500)."); return; }
                try {
                    List<String> ids = Arrays.asList(new String(body, StandardCharsets.UTF_8).split(",", -1));
                    respond(exchange, 200, "text/plain", moveSelected(ids));
                } catch (IllegalArgumentException e) { respond(exchange, 400, "text/plain", e.getMessage()); }
            } else { respond(exchange, 404, "text/plain", "Not found"); }
        } catch (RuntimeException e) {
            System.err.println("Review request failed: " + e.getMessage());
        }
    }

    private void respond(HttpExchange exchange, int status, String type, String body) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", type + ";charset=UTF-8");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-" + nonce
                + "'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** All checks are server-side; the browser submits only session-scoped numeric IDs. */
    synchronized String moveSelected(List<String> requested) {
        if (!unavailable.isEmpty() || !trash.supported()) throw new IllegalArgumentException("Trash is unavailable; deletion is disabled.");
        LinkedHashSet<String> selected = new LinkedHashSet<>(requested);
        if (selected.isEmpty() || selected.size() > 500 || selected.size() != requested.size())
            throw new IllegalArgumentException("Choose between 1 and 500 distinct files.");
        for (String id : selected)
            if (!entries.containsKey(id) || removed.contains(id)) throw new IllegalArgumentException("Unknown, stale, or unavailable file; reload and rescan if needed.");
        Map<Integer, String> keepers = new HashMap<>();
        for (String id : selected) {
            int group = entries.get(id).group;
            if (keepers.containsKey(group)) continue;
            String keeper = entries.entrySet().stream().filter(e -> e.getValue().group == group && !selected.contains(e.getKey()) && !removed.contains(e.getKey()))
                .map(Map.Entry::getKey).findFirst().orElseThrow(() -> new IllegalArgumentException("At least one copy must be kept in every group."));
            keepers.put(group, keeper);
        }
        int moved = 0, skipped = 0;
        StringBuilder results = new StringBuilder();
        for (String id : selected) {
            Entry entry = entries.get(id);
            Snapshot file = entry.snapshot;
            Snapshot keeper = entries.get(keepers.get(entry.group)).snapshot;
            try {
                verify(file, keeper);
                if (!trash.move(file.path)) throw new IOException("System Trash refused the file; nothing was permanently deleted");
                removed.add(id);
                actions.add(new Action("trashed", file.path.toString(), "Matching keeper: " + keeper.path));
                moved++;
            } catch (IOException | RuntimeException e) {
                skipped++;
                actions.add(new Action("skipped", file.path.toString(), e.getMessage()));
                results.append("\nSkipped ").append(file.path).append(": ").append(e.getMessage());
            }
        }
        return "Moved " + moved + " file(s) to Trash. Skipped " + skipped + "." + results;
    }

    private static void verify(Snapshot file, Snapshot keeper) throws IOException {
        file.validate();
        keeper.validate();
        if (Files.isSameFile(file.path, keeper.path)) throw new IOException("Selected file and keeper refer to the same file");
        if (file.size != keeper.size || !sameBytes(file.path, keeper.path)) throw new IOException("File contents no longer match the keeper");
        file.validate();
        keeper.validate();
    }

    private static boolean sameBytes(Path a, Path b) throws IOException {
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel left = Files.newByteChannel(a, options); SeekableByteChannel right = Files.newByteChannel(b, options)) {
            ByteBuffer x = ByteBuffer.allocate(65536), y = ByteBuffer.allocate(65536);
            while (true) {
                x.clear(); y.clear();
                while (x.hasRemaining() && left.read(x) != -1) { }
                while (y.hasRemaining() && right.read(y) != -1) { }
                x.flip(); y.flip();
                if (!x.equals(y)) return false;
                if (!x.hasRemaining()) return true;
            }
        }
    }

    private synchronized String log() {
        StringBuilder csv = new StringBuilder("status,path,reason\r\n");
        for (Action action : actions) csv.append(csv(action.status)).append(',').append(csv(action.path)).append(',').append(csv(action.reason)).append("\r\n");
        return csv.toString();
    }
    private static String csv(String value) {
        if (value == null) value = "";
        // Avoid spreadsheet formula execution when opening logs containing unusual filenames.
        if (!value.isEmpty() && "=+@-\t\r\n".indexOf(value.charAt(0)) >= 0) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private synchronized String page() {
        String gone = String.join(",", removed);
        String blocked = entriesMissing();
        String bootstrap = "const SESSION_TOKEN='" + token + "', REMOVED='" + gone + "', BLOCKED='" + blocked
            + "', CAN_TRASH=" + unavailable.isEmpty() + ";\n";
        return Main.html(scan, true, unavailable).replace("<!--REVIEW_SCRIPT-->", "<script nonce='" + nonce + "'>" + bootstrap + SCRIPT + "</script>");
    }
    private String entriesMissing() {
        List<String> missing = new ArrayList<>();
        for (int gi = 0; gi < scan.groups().size(); gi++)
            for (int fi = 0; fi < scan.groups().get(gi).files().size(); fi++)
                if (!entries.containsKey(gi + ":" + fi)) missing.add(gi + ":" + fi);
        return String.join(",", missing);
    }

    @Override public void close() { server.stop(0); executor.shutdownNow(); }

    private static final String SCRIPT = """
        'use strict';
        const $=s=>document.querySelector(s), $$=s=>Array.from(document.querySelectorAll(s));
        const groups=$$('.group'), dialog=$('#confirm'), message=$('#message');
        let busy=false, pending=[];
        function size(n){if(n<1024)return n+' B';const u=['KiB','MiB','GiB','TiB','PiB','EiB'];let i=-1;do{n/=1024;i++;}while(n>=1024&&i<u.length-1);return n.toFixed(1)+' '+u[i];}
        const selected=()=>$$('.pick:checked').map(p=>p.closest('.file-row'));
        function update(){
          groups.forEach(g=>{
            const rows=Array.from(g.querySelectorAll('.file-row'));
            if(!rows.length)return;
            let keep=rows.find(r=>r.querySelector('.keep input').checked&&!r.dataset.blocked)||rows.find(r=>!r.dataset.blocked);
            if(keep)keep.querySelector('.keep input').checked=true;
            rows.forEach(r=>{const p=r.querySelector('.pick'),k=r===keep;p.disabled=busy||!CAN_TRASH||k||!!r.dataset.blocked;if(k||r.dataset.blocked)p.checked=false;r.classList.toggle('kept',k);r.classList.toggle('selected',p.checked);r.querySelector('.keep input').disabled=busy||!!r.dataset.blocked;});
            g.querySelector('.copy-count').textContent=rows.length+' files';
            const resolved=rows.length<2;g.classList.toggle('resolved',resolved);
            g.querySelector('.reclaim').firstChild.textContent=resolved?'Reviewed':size((rows.length-1)*Number(rows[0].dataset.size));
            g.querySelector('.select-group').disabled=busy||!CAN_TRASH||resolved;
          });
          const rows=selected(), bytes=rows.reduce((n,r)=>n+Number(r.dataset.size),0);
          $('#selection-count').textContent=rows.length?rows.length+' files selected · '+size(bytes):'No files selected';
          $('#delete').disabled=busy||!CAN_TRASH||!rows.length;
          $('#select-all').disabled=busy||!CAN_TRASH||!$$('.pick:not(:disabled):not(:checked)').length;
          $('#clear').disabled=busy||!rows.length;
          $('#confirm-delete').disabled=busy;$('#cancel-delete').disabled=busy;
        }
        REMOVED.split(',').filter(Boolean).forEach(id=>{const r=$('[data-file="'+id+'"]');if(r)r.remove();});
        BLOCKED.split(',').filter(Boolean).forEach(id=>{const r=$('[data-file="'+id+'"]');if(r){r.dataset.blocked='true';r.querySelector('.file-name').append(' — unavailable; rescan');}});
        groups.forEach(g=>{
          g.addEventListener('change',update);
          g.querySelector('.select-group').addEventListener('click',()=>{g.querySelectorAll('.pick:not(:disabled)').forEach(p=>p.checked=true);update();});
        });
        $('#clear').addEventListener('click',()=>{$$('.pick').forEach(p=>p.checked=false);update();});
        $('#select-all').addEventListener('click',()=>{$$('.pick:not(:disabled)').forEach(p=>p.checked=true);update();});
        $('#search').addEventListener('input',e=>{const term=e.target.value.trim().toLowerCase();let count=0;groups.forEach(g=>{g.hidden=!g.textContent.toLowerCase().includes(term);if(!g.hidden)count++;});$('#no-matches').hidden=count!==0;});
        function list(target,values){target.replaceChildren();values.forEach(value=>{const li=document.createElement('li');li.textContent=value;target.append(li);});}
        $('#delete').addEventListener('click',()=>{
          pending=selected();if(!pending.length)return;
          const affected=[...new Set(pending.map(r=>r.closest('.group')))];
          const keepers=affected.map(g=>Array.from(g.querySelectorAll('.file-row')).find(r=>!pending.includes(r)&&!r.dataset.blocked)).filter(Boolean);
          if(keepers.length!==affected.length){message.textContent='Keep one available copy in each group.';return;}
          $('#confirm-summary').textContent=pending.length+' files · '+size(pending.reduce((n,r)=>n+Number(r.dataset.size),0))+' · '+keepers.length+' protected copies';
          list($('#confirm-files'),pending.map(r=>r.querySelector('.path').textContent));
          list($('#confirm-keepers'),keepers.map(r=>r.querySelector('.path').textContent));
          dialog.showModal();
        });
        $('#cancel-delete').addEventListener('click',()=>{if(!busy)dialog.close();});
        dialog.addEventListener('cancel',e=>{if(busy)e.preventDefault();});
        $('#confirm-delete').addEventListener('click',async()=>{
          if(busy||!pending.length)return;busy=true;update();$('#confirm-delete').textContent='Verifying files…';
          const results=[];
          try{
            for(let start=0;start<pending.length;start+=500){
              $('#confirm-delete').textContent='Verifying '+(start+1)+'–'+Math.min(start+500,pending.length)+' of '+pending.length+'…';
              const res=await fetch('trash',{method:'POST',headers:{'Content-Type':'text/plain;charset=UTF-8','X-DupeLens-Token':SESSION_TOKEN},body:pending.slice(start,start+500).map(r=>r.dataset.file).join(',')});
              const text=await res.text();if(!res.ok)throw new Error(text);results.push(text);
            }
            sessionStorage.setItem('dupelens-result-'+SESSION_TOKEN,results.join('\\n'));location.reload();
          }catch(e){dialog.close();message.classList.add('error');message.textContent=results.join('\\n')+'\\nStopped. '+e.message+' Reload this page and check the action log before retrying.';}
          finally{busy=false;$('#confirm-delete').textContent='Move to Trash';update();}
        });
        const last=sessionStorage.getItem('dupelens-result-'+SESSION_TOKEN);if(last){message.textContent=last;sessionStorage.removeItem('dupelens-result-'+SESSION_TOKEN);}
        update();
        """;
}
