package lark;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Local-only HTTP server. The editor in the browser talks to this, and nowhere else. */
public final class Server {
    private static final AtomicLong FOCUS_SEQ = new AtomicLong(0);
    private static volatile long lastUiSeenMs;
    private static final long UI_FRESH_MS = 5_000;

    private final HttpServer http;
    private final int port;
    private final Projects projects;
    private final Runner runner;
    private final Debugger debugger;
    private final Tests tests;
    private final Checker checker;
    private final GitHub github;
    private final Updates updates;
    private final Terminal terminal;
    private final Recovery recovery;
    private final Settings settings;
    private final Path webDir;
    private final boolean lan;
    /** Failed pair attempts per remote IP (rate limit). */
    private final ConcurrentHashMap<String, long[]> pairFails = new ConcurrentHashMap<>();

    /** True when an editor tab has polled within the freshness window. */
    public static boolean uiFresh() {
        long seen = lastUiSeenMs;
        return seen > 0 && (System.currentTimeMillis() - seen) < UI_FRESH_MS;
    }

    public static long bumpFocus() {
        return FOCUS_SEQ.incrementAndGet();
    }

    public static long focusSeq() {
        return FOCUS_SEQ.get();
    }

    private static void touchUi() {
        lastUiSeenMs = System.currentTimeMillis();
    }

    private Server(HttpServer http, int port, Projects projects, Runner runner, Debugger debugger, Tests tests,
                   Checker checker, GitHub github, Updates updates, Terminal terminal, Settings settings, Path webDir,
                   boolean lan) {
        this.http = http;
        this.port = port;
        this.projects = projects;
        this.runner = runner;
        this.debugger = debugger;
        this.tests = tests;
        this.checker = checker;
        this.github = github;
        this.updates = updates;
        this.terminal = terminal;
        this.recovery = new Recovery();
        this.settings = settings;
        this.webDir = webDir;
        this.lan = lan;
    }

    public static Server start(int port, Projects projects, Runner runner, GitHub github, Settings settings) throws IOException {
        return start(port, projects, runner, github, settings, false);
    }

    public static Server start(int port, Projects projects, Runner runner, GitHub github, Settings settings, boolean lan)
            throws IOException {
        HttpServer http = null;
        IOException last = null;
        int chosen = port;
        String bind = lan ? "0.0.0.0" : "127.0.0.1";
        // After an update restart, wait for the previous process to free the same port
        // instead of hopping to 43124 and opening a second window.
        boolean preferExact = Instance.isReplaceMode();
        long deadline = System.currentTimeMillis() + (preferExact ? 15_000 : 0);
        while (http == null) {
            try {
                http = HttpServer.create(new InetSocketAddress(bind, port), 0);
                chosen = port;
            } catch (IOException e) {
                last = e;
                if (System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                break;
            }
        }
        if (http == null && !preferExact) {
            for (int candidate = port + 1; candidate < port + 20; candidate++) {
                try {
                    http = HttpServer.create(new InetSocketAddress(bind, candidate), 0);
                    chosen = candidate;
                    break;
                } catch (IOException e) {
                    last = e;
                }
            }
        }
        if (http == null) throw last == null ? new IOException("No free port") : last;
        Server server = new Server(http, chosen, projects, runner, new Debugger(), new Tests(), new Checker(), github,
                new Updates(settings), new Terminal(), settings, findWeb(), lan);
        http.createContext("/", server::handle);
        http.setExecutor(Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable, "lark-http");
            thread.setDaemon(true);
            return thread;
        }));
        http.start();
        Instance.writePort(chosen);
        return server;
    }

    public int port() {
        return port;
    }

    public void stop() {
        try {
            terminal.shutdown();
        } catch (Exception ignored) {
            // Best-effort.
        }
        http.stop(0);
    }

    private void handle(HttpExchange exchange) {
        try {
            String host = exchange.getRequestHeaders().getFirst("Host");
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if (!allowedClient(exchange, host) && !publicDownload(path, method)) {
                sendJson(exchange, 403, new Json.Obj().put("ok", false)
                        .put("error", lan
                                ? "Open the Phone URL with the pair code from the Lark Mobile window."
                                : "Lark only answers on localhost."));
                return;
            }
            if (downloadName(path) != null && ("GET".equals(method) || "HEAD".equals(method))) {
                sendDownload(exchange, method, downloadName(path));
            } else if ("/update.json".equals(path) && ("GET".equals(method) || "HEAD".equals(method))) {
                sendUpdateFeed(exchange, method);
            } else             if (path.startsWith("/api/")) {
                if (!method.equals("GET") && !method.equals("HEAD")) {
                    String mark = exchange.getRequestHeaders().getFirst("X-Lark");
                    if (!"1".equals(mark)) {
                        sendJson(exchange, 400, new Json.Obj().put("ok", false).put("error", "Missing the Lark header."));
                        return;
                    }
                    if (!mutationOriginOk(exchange)) {
                        sendJson(exchange, 403, new Json.Obj().put("ok", false)
                                .put("error", "Blocked cross-origin request to the local Lark server."));
                        return;
                    }
                }
                api(exchange, method, path);
            } else {
                staticFile(exchange, path);
            }
        } catch (RequestFail e) {
            try {
                sendJson(exchange, e.status, new Json.Obj().put("ok", false).put("error", e.getMessage()));
            } catch (IOException ignored) {
                // The browser went away.
            }
        } catch (IOException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("Broken pipe") || message.contains("Connection reset")) return;
            e.printStackTrace(System.err);
        } catch (Exception e) {
            e.printStackTrace(System.err);
            try {
                sendJson(exchange, 500, new Json.Obj().put("ok", false).put("error", "Something went wrong inside Lark."));
            } catch (IOException ignored) {
                // Already failing.
            }
        }
    }

    private void api(HttpExchange exchange, String method, String path) throws IOException {
        String[] parts = path.split("/");
        if ("GET".equals(method) && "/api/health".equals(path)) {
            sendJson(exchange, 200, new Json.Obj()
                    .put("ok", true)
                    .put("version", Settings.VERSION)
                    .put("edition", editionName())
                    .put("channel", channelName())
                    .put("mobile", Mobile.isMobile())
                    .put("lan", lan)
                    .put("focus", focusSeq())
                    .put("uiFresh", uiFresh()));
            return;
        }
        // UI poll: marks the editor as present and returns the current focus token.
        if ("GET".equals(method) && "/api/focus".equals(path)) {
            touchUi();
            sendJson(exchange, 200, new Json.Obj()
                    .put("ok", true)
                    .put("focus", focusSeq())
                    .put("uiFresh", true));
            return;
        }
        // Second launch / tray Open: bump focus so the open tab brings itself forward.
        // OS raise is done by the caller (Instance) so it can reopen if nothing is visible.
        if ("POST".equals(method) && "/api/focus".equals(path)) {
            long seq = bumpFocus();
            sendJson(exchange, 200, new Json.Obj()
                    .put("ok", true)
                    .put("focus", seq)
                    .put("uiFresh", uiFresh()));
            return;
        }
        if ("GET".equals(method) && "/api/mobile/info".equals(path)) {
            boolean loopback = isLoopbackPeer(exchange) && localHost(exchange.getRequestHeaders().getFirst("Host"));
            boolean paired = pairOk(exchange);
            boolean sharePair = loopback || paired;
            Json.Arr urls = new Json.Arr();
            if (sharePair) {
                for (String ip : Mobile.lanAddresses()) {
                    urls.add("http://" + ip + ":" + port + "/?pair=" + Mobile.pairToken());
                }
            }
            sendJson(exchange, 200, new Json.Obj()
                    .put("ok", true)
                    .put("edition", "mobile")
                    .put("version", Settings.VERSION)
                    .put("lan", lan && Mobile.lanEnabled())
                    .put("pair", sharePair ? Mobile.pairToken() : "")
                    .put("local", sharePair
                            ? ("http://127.0.0.1:" + port + "/?pair=" + Mobile.pairToken())
                            : ("http://127.0.0.1:" + port + "/"))
                    .put("urls", urls));
            return;
        }
        if ("/api/settings".equals(path)) {
            if ("GET".equals(method)) {
                sendJson(exchange, 200, settingsJson());
                return;
            }
            if ("PUT".equals(method)) {
                Json.Obj body = body(exchange);
                if (body.has("theme")) {
                    String theme = body.str("theme", "slate");
                    if ("ink".equals(theme)) theme = "desk";
                    if ("paper".equals(theme)) theme = "daylight";
                    if ("tide".equals(theme)) theme = "moss";
                    if ("copper".equals(theme)) theme = "ember";
                    if ("chalk".equals(theme)) theme = "fog";
                    if (!Settings.THEMES.contains(theme)) throw new RequestFail(400, "Unknown theme.");
                    settings.theme = theme;
                }
                if (body.has("lastProject")) settings.lastProject = body.str("lastProject", "");
                if (body.has("fontSize")) {
                    int size = (int) body.longVal("fontSize");
                    settings.fontSize = Settings.clamp(size, 11, 28, settings.fontSize);
                }
                if (body.has("zoom")) {
                    int zoom = (int) body.longVal("zoom");
                    settings.zoom = Settings.clamp(zoom, 80, 160, settings.zoom);
                }
                if (body.has("fontFamily")) {
                    String family = body.str("fontFamily", "jetbrains");
                    if (!Settings.FONT_FAMILIES.contains(family)) throw new RequestFail(400, "Unknown font.");
                    settings.fontFamily = family;
                }
                if (body.has("ligatures")) settings.ligatures = body.bool("ligatures");
                if (body.has("indentSize")) {
                    int indent = (int) body.longVal("indentSize");
                    if (indent != 2 && indent != 4 && indent != 8) throw new RequestFail(400, "Indent must be 2, 4, or 8.");
                    settings.indentSize = indent;
                }
                if (body.has("indentWithTabs")) settings.indentWithTabs = body.bool("indentWithTabs");
                if (body.has("lineWrapping")) settings.lineWrapping = body.bool("lineWrapping");
                if (body.has("lineNumbers")) settings.lineNumbers = body.bool("lineNumbers");
                if (body.has("trayOutlines")) settings.trayOutlines = body.bool("trayOutlines");
                if (body.has("autoSave")) settings.autoSave = body.bool("autoSave");
                if (body.has("autoSaveDelayMs")) {
                    int delay = (int) body.longVal("autoSaveDelayMs");
                    if (delay != 300 && delay != 800 && delay != 1500) {
                        throw new RequestFail(400, "Autosave delay must be 300, 800, or 1500 ms.");
                    }
                    settings.autoSaveDelayMs = delay;
                }
                if (body.has("formatOnSave")) settings.formatOnSave = body.bool("formatOnSave");
                if (body.has("updateFeed")) {
                    String feed = body.str("updateFeed", "").trim();
                    if (!feed.isBlank() && !Updates.allowedFeedUrl(feed)) {
                        throw new RequestFail(400, "Update feed must be HTTPS on GitHub or the official Lark release host.");
                    }
                    // Only the machine running Lark can retarget the update feed.
                    if (!feed.equals(settings.updateFeed == null ? "" : settings.updateFeed)
                            && !isLoopbackPeer(exchange)) {
                        throw new RequestFail(403, "Change the update feed from this computer, not over Wi‑Fi.");
                    }
                    settings.updateFeed = feed;
                }
                settings.save();
                sendJson(exchange, 200, settingsJson());
                return;
            }
        }
        if ("/api/update/check".equals(path) && "GET".equals(method)) {
            sendJson(exchange, 200, updates.check());
            return;
        }
        if ("/api/update/install".equals(path) && "POST".equals(method)) {
            Json.Obj body = body(exchange);
            sendJson(exchange, 200, updates.install(body.str("download", ""), isLoopbackPeer(exchange)));
            return;
        }
        if ("/api/update/skip".equals(path) && "POST".equals(method)) {
            sendJson(exchange, 200, updates.skip(body(exchange).str("version", "")));
            return;
        }
        if ("/api/update/restart".equals(path) && "POST".equals(method)) {
            sendJson(exchange, 200, updates.restart());
            return;
        }
        if ("/api/recovery".equals(path) && "GET".equals(method)) {
            sendJson(exchange, 200, recovery.snapshot());
            return;
        }
        if ("/api/recovery".equals(path) && ("PUT".equals(method) || "POST".equals(method))) {
            sendJson(exchange, 200, recovery.save(body(exchange)));
            return;
        }
        if ("/api/recovery".equals(path) && "DELETE".equals(method)) {
            sendJson(exchange, 200, recovery.clear());
            return;
        }
        if ("/api/recovery/apply".equals(path) && "POST".equals(method)) {
            Json.Obj body = body(exchange);
            String id = body.str("projectId", "").trim();
            if (id.isBlank()) id = recovery.snapshot().str("projectId", "");
            sendJson(exchange, 200, recovery.applyToProject(projects, id));
            return;
        }
        if ("/api/projects".equals(path) && "GET".equals(method)) {
            Json.Arr list = new Json.Arr();
            for (Json.Obj project : projects.list()) list.add(project);
            sendJson(exchange, 200, new Json.Obj().put("ok", true).put("projects", list));
            return;
        }
        if ("/api/templates".equals(path) && "GET".equals(method)) {
            sendJson(exchange, 200, new Json.Obj().put("ok", true).put("templates", Projects.templateCatalog()));
            return;
        }
        if ("/api/projects".equals(path) && "POST".equals(method)) {
            Json.Obj body = body(exchange);
            String template = body.str("template", "empty");
            if (!Projects.TEMPLATES.contains(template)) template = "empty";
            String id = projects.create(body.str("name", ""), template);
            sendJson(exchange, 200, new Json.Obj().put("ok", true).put("id", id));
            return;
        }
        if ("/api/check".equals(path) && "POST".equals(method)) {
            Json.Obj body = body(exchange);
            sendJson(exchange, 200, checker.check(projects.projectDir(body.str("project", ""))));
            return;
        }
        if ("/api/import".equals(path) && "POST".equals(method)) {
            String type = exchange.getRequestHeaders().getFirst("Content-Type");
            if (type != null && type.startsWith("application/zip")) {
                byte[] data = exchange.getRequestBody().readNBytes(8_000_001);
                if (data.length > 8_000_000) throw new RequestFail(400, "That zip is too large.");
                String name = query(exchange, "name");
                String id = projects.importZip(name, new ByteArrayInputStream(data));
                sendJson(exchange, 200, new Json.Obj().put("ok", true).put("id", id));
                return;
            }
            Json.Obj body = body(exchange);
            Json.Arr arr = body.arr("files");
            if (arr == null) throw new RequestFail(400, "Missing files.");
            java.util.ArrayList<Projects.TextFile> files = new java.util.ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                if (!(arr.get(i) instanceof Json.Obj file)) continue;
                files.add(new Projects.TextFile(file.str("path", ""), file.str("content", "")));
            }
            String id = projects.importFiles(body.str("name", "Imported"), files);
            sendJson(exchange, 200, new Json.Obj().put("ok", true).put("id", id));
            return;
        }
        if ("/api/run".equals(path) && "POST".equals(method)) {
            Json.Obj body = body(exchange);
            String args = body.str("args", "");
            String stdin = body.str("stdin", "");
            if (args.length() > 2_000) throw new RequestFail(400, "Those arguments are too long.");
            if (stdin.length() > 100_000) throw new RequestFail(400, "That input is too long.");
            String id = runner.start(projects.projectDir(body.str("project", "")),
                    body.str("mainClass", ""), args, stdin);
            sendJson(exchange, 200, new Json.Obj().put("ok", true).put("id", id));
            return;
        }
        if ("/api/scratch/run".equals(path) && "POST".equals(method)) {
            Json.Obj body = body(exchange);
            String args = body.str("args", "");
            String stdin = body.str("stdin", "");
            if (args.length() > 2_000) throw new RequestFail(400, "Those arguments are too long.");
            if (stdin.length() > 100_000) throw new RequestFail(400, "That input is too long.");
            String id = runner.startScratch(body.str("source", ""), body.str("mainClass", "ScratchPad"), args, stdin);
            sendJson(exchange, 200, new Json.Obj().put("ok", true).put("id", id));
            return;
        }
        if (parts.length == 4 && "api".equals(parts[1]) && "run".equals(parts[2]) && "GET".equals(method)) {
            sendJson(exchange, 200, runner.snapshot(decode(parts[3])));
            return;
        }
        if (parts.length == 5 && "api".equals(parts[1]) && "run".equals(parts[2]) && "stop".equals(parts[4])
                && "POST".equals(method)) {
            runner.stop(decode(parts[3]));
            sendJson(exchange, 200, new Json.Obj().put("ok", true));
            return;
        }
        if (parts.length == 5 && "api".equals(parts[1]) && "run".equals(parts[2]) && "input".equals(parts[4])
                && "POST".equals(method)) {
            Json.Obj body = body(exchange);
            runner.writeInput(decode(parts[3]), body.str("text", ""));
            sendJson(exchange, 200, new Json.Obj().put("ok", true));
            return;
        }
        if ("/api/debug".equals(path) && "POST".equals(method)) {
            Json.Obj body = body(exchange);
            java.util.ArrayList<String> bps = new java.util.ArrayList<>();
            Json.Arr arr = body.arr("breakpoints");
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    Json.Value v = arr.get(i);
                    if (v instanceof Json.Str s) bps.add(s.value);
                    else if (v instanceof Json.Obj o) {
                        String file = o.str("file", "");
                        long line = o.longVal("line");
                        if (!file.isBlank() && line > 0) bps.add(file + ":" + line);
                    }
                }
            }
            sendJson(exchange, 200, debugger.start(
                    projects.projectDir(body.str("project", "")),
                    body.str("mainClass", ""),
                    body.str("args", ""),
                    bps));
            return;
        }
        if (parts.length == 4 && "api".equals(parts[1]) && "debug".equals(parts[2]) && "GET".equals(method)) {
            sendJson(exchange, 200, debugger.snapshot(decode(parts[3])));
            return;
        }
        if (parts.length == 5 && "api".equals(parts[1]) && "debug".equals(parts[2]) && "command".equals(parts[4])
                && "POST".equals(method)) {
            Json.Obj body = body(exchange);
            sendJson(exchange, 200, debugger.command(decode(parts[3]), body.str("command", "")));
            return;
        }
        if (parts.length == 5 && "api".equals(parts[1]) && "debug".equals(parts[2]) && "stop".equals(parts[4])
                && "POST".equals(method)) {
            debugger.stop(decode(parts[3]));
            sendJson(exchange, 200, new Json.Obj().put("ok", true));
            return;
        }
        if ("/api/tests/discover".equals(path) && "GET".equals(method)) {
            String projectId = query(exchange, "project");
            if (projectId == null || projectId.isBlank()) throw new RequestFail(400, "Missing project.");
            sendJson(exchange, 200, tests.discover(projects.projectDir(projectId)));
            return;
        }
        if ("/api/tests".equals(path) && "POST".equals(method)) {
            Json.Obj body = body(exchange);
            sendJson(exchange, 200, tests.start(
                    projects.projectDir(body.str("project", "")),
                    body.str("className", ""),
                    body.str("method", "")));
            return;
        }
        if (parts.length == 4 && "api".equals(parts[1]) && "tests".equals(parts[2]) && "GET".equals(method)) {
            sendJson(exchange, 200, tests.snapshot(decode(parts[3])));
            return;
        }
        if ("/api/terminal".equals(path) && "POST".equals(method)) {
            Json.Obj body = body(exchange);
            String projectId = body.str("project", "");
            Path cwd = projectId.isBlank()
                    ? settings.projectsDir()
                    : projects.projectDir(projectId);
            sendJson(exchange, 200, terminal.start(cwd));
            return;
        }
        if (parts.length == 4 && "api".equals(parts[1]) && "terminal".equals(parts[2]) && "GET".equals(method)) {
            sendJson(exchange, 200, terminal.snapshot(decode(parts[3])));
            return;
        }
        if (parts.length == 5 && "api".equals(parts[1]) && "terminal".equals(parts[2]) && "input".equals(parts[4])
                && "POST".equals(method)) {
            Json.Obj body = body(exchange);
            terminal.write(decode(parts[3]), body.str("text", ""));
            sendJson(exchange, 200, new Json.Obj().put("ok", true));
            return;
        }
        if (parts.length == 5 && "api".equals(parts[1]) && "terminal".equals(parts[2]) && "stop".equals(parts[4])
                && "POST".equals(method)) {
            sendJson(exchange, 200, terminal.stop(decode(parts[3])));
            return;
        }
        if ("/api/github/token".equals(path) && "POST".equals(method)) {
            sendJson(exchange, 200, github.saveToken(body(exchange).str("token", "")));
            return;
        }
        if ("/api/github/repos".equals(path) && "GET".equals(method)) {
            sendJson(exchange, 200, github.repos());
            return;
        }
        if ("/api/github/push".equals(path) && "POST".equals(method)) {
            Json.Obj body = body(exchange);
            sendJson(exchange, 200, github.push(projects, body.str("project", ""), body.str("owner", ""),
                    body.str("repo", ""), body.str("branch", "main"), body.str("message", ""), body.bool("create")));
            return;
        }
        if ("/api/github/pull".equals(path) && "POST".equals(method)) {
            Json.Obj body = body(exchange);
            sendJson(exchange, 200, github.pull(projects, body.str("owner", ""), body.str("repo", ""),
                    body.str("branch", "main")));
            return;
        }
        if ("/api/github/login".equals(path) && "POST".equals(method)) {
            sendJson(exchange, 200, github.beginLogin("http://127.0.0.1:" + port));
            return;
        }
        if ("/api/github/status".equals(path) && "GET".equals(method)) {
            sendJson(exchange, 200, github.status());
            return;
        }
        if ("/api/github/begin".equals(path) && "GET".equals(method)) {
            sendHtml(exchange, github.beginPage(query(exchange, "state")));
            return;
        }
        if (path.startsWith("/api/github/created/") && "GET".equals(method)) {
            sendHtml(exchange, github.createdPage(decode(path.substring("/api/github/created/".length())), query(exchange, "code")));
            return;
        }
        if ("/api/github/oauth".equals(path) && "GET".equals(method)) {
            sendHtml(exchange, github.oauthPage(query(exchange, "code"), query(exchange, "state"),
                    query(exchange, "error"), query(exchange, "error_description")));
            return;
        }
        if (parts.length >= 4 && "api".equals(parts[1]) && "projects".equals(parts[2])) {
            String id = decode(parts[3]);
            String tail = parts.length >= 5 ? parts[4] : "";
            if (parts.length == 4 && "GET".equals(method)) {
                sendJson(exchange, 200, new Json.Obj().put("ok", true).put("project", projects.read(id)));
                return;
            }
            if (parts.length == 4 && "PUT".equals(method)) {
                Json.Obj body = body(exchange);
                projects.updateMeta(id, body.str("name"), body.str("mainClass"), body.arr("runConfigs"),
                        body.has("name"), body.has("mainClass"), body.has("runConfigs"));
                sendJson(exchange, 200, new Json.Obj().put("ok", true));
                return;
            }
            if (parts.length == 4 && "DELETE".equals(method)) {
                projects.delete(id);
                sendJson(exchange, 200, new Json.Obj().put("ok", true));
                return;
            }
            if ("file".equals(tail) && parts.length == 5 && "PUT".equals(method)) {
                Json.Obj body = body(exchange);
                projects.writeFile(id, body.str("path", ""), body.str("content", ""));
                sendJson(exchange, 200, new Json.Obj().put("ok", true));
                return;
            }
            if ("file".equals(tail) && parts.length == 5 && "DELETE".equals(method)) {
                String rel = query(exchange, "path");
                if (rel == null) throw new RequestFail(400, "Missing a path.");
                projects.deletePath(id, rel);
                sendJson(exchange, 200, new Json.Obj().put("ok", true));
                return;
            }
            if ("mkdir".equals(tail) && "POST".equals(method)) {
                projects.mkdir(id, body(exchange).str("path", ""));
                sendJson(exchange, 200, new Json.Obj().put("ok", true));
                return;
            }
            if ("move".equals(tail) && "POST".equals(method)) {
                Json.Obj body = body(exchange);
                projects.move(id, body.str("from", ""), body.str("to", ""));
                sendJson(exchange, 200, new Json.Obj().put("ok", true));
                return;
            }
            if ("export".equals(tail) && "GET".equals(method)) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                projects.exportZip(id, buf);
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + id + ".zip\"");
                send(exchange, 200, "application/zip", buf.toByteArray());
                return;
            }
        }
        sendJson(exchange, 404, new Json.Obj().put("ok", false).put("error", "No such API."));
    }

    private Json.Obj settingsJson() {
        return new Json.Obj()
                .put("ok", true)
                .put("theme", settings.theme)
                .put("fontSize", settings.fontSize)
                .put("zoom", settings.zoom)
                .put("fontFamily", settings.fontFamily == null ? "jetbrains" : settings.fontFamily)
                .put("ligatures", settings.ligatures)
                .put("indentSize", settings.indentSize)
                .put("indentWithTabs", settings.indentWithTabs)
                .put("lineWrapping", settings.lineWrapping)
                .put("lineNumbers", settings.lineNumbers)
                .put("trayOutlines", settings.trayOutlines)
                .put("autoSave", settings.autoSave)
                .put("autoSaveDelayMs", settings.autoSaveDelayMs)
                .put("formatOnSave", settings.formatOnSave)
                .put("tokenSet", settings.githubToken != null && !settings.githubToken.isBlank())
                .put("githubLogin", settings.githubLogin == null ? "" : settings.githubLogin)
                .put("lastProject", settings.lastProject == null ? "" : settings.lastProject)
                .put("updateFeed", settings.updateFeed == null ? "" : settings.updateFeed)
                .put("javaVersion", System.getProperty("java.version", ""))
                .put("projectsDir", settings.projectsDir().toString())
                .put("version", Settings.VERSION)
                .put("edition", editionName())
                .put("channel", channelName())
                .put("mobile", Mobile.isMobile())
                .put("lan", lan);
    }

    private static String editionName() {
        if (Mobile.isMobile()) return "mobile";
        if (Desktop.isDesktop()) return "desktop";
        if (Executable.isExecutable()) return "executable";
        return "portable";
    }

    private static String channelName() {
        if (Mobile.isMobile()) return Mobile.CHANNEL;
        if (Desktop.isDesktop()) return Desktop.CHANNEL;
        if (Executable.isExecutable()) return Executable.CHANNEL;
        return "portable";
    }

    private boolean allowedClient(HttpExchange exchange, String host) {
        // Authorize by TCP peer, never by the spoofable Host header.
        // Loopback peers must also send a loopback Host for our port, which blocks
        // DNS rebinding (a web page whose domain resolves to 127.0.0.1).
        if (isLoopbackPeer(exchange)) return localHost(host) && hostPortOk(host);
        if (!lan || !Mobile.isMobile()) return false;
        if (!pairAttemptAllowed(exchange)) return false;
        boolean ok = pairOk(exchange);
        if (!ok) notePairFail(exchange);
        return ok;
    }

    private static boolean isLoopbackPeer(HttpExchange exchange) {
        try {
            InetSocketAddress remote = exchange.getRemoteAddress();
            if (remote == null) return false;
            InetAddress addr = remote.getAddress();
            return addr != null && addr.isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Browser pages on other origins must not drive mutating API calls.
     * Empty Origin (same-origin / non-browser) is allowed; loopback Origins are allowed;
     * paired Mobile LAN clients may use their phone Origin when the pair token is valid.
     */
    private boolean mutationOriginOk(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.isBlank()) return true;
        try {
            java.net.URI uri = java.net.URI.create(origin.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]") || host.equals("::1")) {
                return true;
            }
            // Mobile LAN: only when already paired for this request.
            return lan && Mobile.isMobile() && pairOk(exchange);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean pairAttemptAllowed(HttpExchange exchange) {
        String key = peerKey(exchange);
        long[] slot = pairFails.get(key);
        if (slot == null) return true;
        long windowStart = slot[0];
        long count = slot[1];
        long now = System.currentTimeMillis();
        if (now - windowStart > 60_000L) return true;
        return count < 20;
    }

    private void notePairFail(HttpExchange exchange) {
        String key = peerKey(exchange);
        long now = System.currentTimeMillis();
        pairFails.compute(key, (k, prev) -> {
            if (prev == null || now - prev[0] > 60_000L) return new long[]{now, 1};
            return new long[]{prev[0], prev[1] + 1};
        });
    }

    private static String peerKey(HttpExchange exchange) {
        try {
            InetSocketAddress remote = exchange.getRemoteAddress();
            if (remote != null && remote.getAddress() != null) return remote.getAddress().getHostAddress();
        } catch (Exception ignored) {}
        return "unknown";
    }

    private static boolean pairOk(HttpExchange exchange) {
        String token = Mobile.pairToken();
        if (token == null || token.isBlank()) return false;
        String pair = exchange.getRequestHeaders().getFirst("X-Lark-Pair");
        if (pair == null || pair.isBlank()) pair = query(exchange, "pair");
        if (pair == null) return false;
        return constantTimeEquals(token, pair.trim());
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) return false;
        int diff = 0;
        for (int i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
        return diff == 0;
    }

    private static String downloadName(String path) {
        if ("/lark.zip".equals(path)) return "lark.zip";
        if ("/lark-windows.zip".equals(path)) return "lark-windows.zip";
        if ("/lark-mac.zip".equals(path)) return "lark-mac.zip";
        if ("/lark-linux.zip".equals(path)) return "lark-linux.zip";
        return null;
    }

    private void sendUpdateFeed(HttpExchange exchange, String method) throws IOException {
        Path file = findRelease("update.json");
        byte[] data;
        if (file != null) {
            data = Files.readAllBytes(file);
        } else {
            String json = new Json.Obj()
                    .put("version", Settings.VERSION)
                    .put("notes", "Current Lark build on this machine.")
                    .put("downloads", new Json.Obj()
                            .put("windows", "/lark-windows.zip")
                            .put("mac", "/lark-mac.zip")
                            .put("linux", "/lark-linux.zip")
                            .put("any", "/lark.zip"))
                    .write();
            data = json.getBytes(StandardCharsets.UTF_8);
        }
        if ("HEAD".equals(method)) {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        send(exchange, 200, "application/json; charset=utf-8", data);
    }

    /** A portable copy: the jar plus launchers for Windows, Mac, and Linux. */
    private void sendDownload(HttpExchange exchange, String method, String name) throws IOException {
        Path zip = findRelease(name);
        if (zip == null) {
            send(exchange, 404, "text/plain; charset=utf-8",
                    (name + " is not packed on this computer yet.").getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] data = Files.readAllBytes(zip);
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name + "\"");
        if ("HEAD".equals(method)) {
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        send(exchange, 200, "application/zip", data);
    }

    private void staticFile(HttpExchange exchange, String path) throws IOException {
        if (path.equals("/")) path = "/index.html";
        if (path.contains("..")) {
            send(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String rel = path.startsWith("/") ? path.substring(1) : path;
        byte[] data = readWeb(rel);
        if (data == null) {
            send(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        // Stamp asset URLs so browsers cannot keep a stale app.js after an update.
        if (rel.endsWith(".html")) {
            String html = new String(data, StandardCharsets.UTF_8)
                    .replace("__LARK_V__", Settings.VERSION);
            data = html.getBytes(StandardCharsets.UTF_8);
        }
        send(exchange, 200, mime(rel), data);
    }

    private byte[] readWeb(String rel) throws IOException {
        String root = uiRootName();
        if (webDir != null) {
            Path file = webDir.resolve(rel).normalize();
            if (file.startsWith(webDir) && Files.isRegularFile(file)) return Files.readAllBytes(file);
            // Mobile can reuse desktop vendor/fonts when developing from source.
            if (Mobile.isMobile()) {
                Path shared = Path.of("web").toAbsolutePath().normalize().resolve(rel).normalize();
                Path webRoot = Path.of("web").toAbsolutePath().normalize();
                if (shared.startsWith(webRoot) && Files.isRegularFile(shared)) return Files.readAllBytes(shared);
            }
        }
        try (InputStream in = Lark.class.getResourceAsStream("/" + root + "/" + rel)) {
            if (in != null) return in.readAllBytes();
        }
        if (Mobile.isMobile()) {
            try (InputStream in = Lark.class.getResourceAsStream("/web/" + rel)) {
                if (in != null) return in.readAllBytes();
            }
        }
        return null;
    }

    private static String uiRootName() {
        return Mobile.isMobile() ? "web-mobile" : "web";
    }

    private static Path findWeb() {
        // Packaged launches (java -jar … / Start Lark) must use the UI packed in
        // the jar. A leftover web/ folder next to an unzip — or beside the run
        // copy — used to serve stale app.js and resurrect bugs like "on is not defined".
        try {
            var location = Lark.class.getProtectionDomain().getCodeSource().getLocation();
            if (location != null) {
                Path path = Path.of(location.toURI());
                if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")) {
                    return null;
                }
            }
        } catch (Exception ignored) {
            // Fall through to the source-tree web/ folder for java -cp builds.
        }
        String install = System.getProperty("lark.install");
        if (install != null && !install.isBlank()) return null;
        if ("jar".equalsIgnoreCase(System.getProperty("lark.ui", ""))) return null;

        String folder = uiRootName();
        Path cwd = Path.of(folder).toAbsolutePath().normalize();
        if (Files.isRegularFile(cwd.resolve("index.html"))) return cwd;
        if (Mobile.isMobile()) {
            Path desktopWeb = Path.of("web").toAbsolutePath().normalize();
            if (Files.isRegularFile(desktopWeb.resolve("index.html"))) return desktopWeb;
        }
        return null;
    }

    private static Path findRelease(String name) {
        Path cwd = Path.of("release", name).toAbsolutePath().normalize();
        if (Files.isRegularFile(cwd)) return cwd;
        try {
            var location = Lark.class.getProtectionDomain().getCodeSource().getLocation();
            if (location != null) {
                Path parent = Path.of(location.toURI()).getParent();
                Path beside = parent.resolve("release").resolve(name);
                if (Files.isRegularFile(beside)) return beside.normalize();
                Path sibling = parent.resolve(name);
                if (Files.isRegularFile(sibling)) return sibling.normalize();
            }
        } catch (Exception ignored) {
            // Missing release files are fine for a portable copy.
        }
        return null;
    }

    private static String mime(String rel) {
        if (rel.endsWith(".html")) return "text/html; charset=utf-8";
        if (rel.endsWith(".css")) return "text/css; charset=utf-8";
        if (rel.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (rel.endsWith(".lua")) return "text/plain; charset=utf-8";
        if (rel.endsWith(".svg")) return "image/svg+xml";
        if (rel.endsWith(".png")) return "image/png";
        if (rel.endsWith(".woff2")) return "font/woff2";
        if (rel.endsWith(".woff")) return "font/woff";
        if (rel.endsWith(".map")) return "application/json";
        if (rel.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    private static Json.Obj body(HttpExchange exchange) throws IOException {
        byte[] data = exchange.getRequestBody().readNBytes(5_000_001);
        if (data.length == 0) throw new RequestFail(400, "That request was empty.");
        if (data.length > 5_000_000) throw new RequestFail(400, "That request is too large.");
        return Json.parseObj(new String(data, StandardCharsets.UTF_8));
    }

    private static String query(HttpExchange exchange, String key) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) return null;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
            if (!name.equals(key)) continue;
            return URLDecoder.decode(eq < 0 ? "" : part.substring(eq + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /** The zip and its save page can be opened from a normal browser. Everything else stays on this machine. */
    private static boolean publicDownload(String path, String method) {
        if (!"GET".equals(method) && !"HEAD".equals(method)) return false;
        return downloadName(path) != null || "/update.json".equals(path)
                || "/download.html".equals(path) || "/favicon.svg".equals(path) || "/favicon.png".equals(path);
    }

    private boolean hostPortOk(String host) {
        String value = host.trim();
        int bracket = value.lastIndexOf(']');
        int colon = value.lastIndexOf(':');
        if (colon < 0 || colon < bracket) return true; // no port given (browsers omit :80)
        try {
            return Integer.parseInt(value.substring(colon + 1)) == port;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static boolean localHost(String host) {
        if (host == null || host.isBlank()) return false;
        String value = host.toLowerCase(Locale.ROOT);
        String name = value;
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            name = end > 0 ? value.substring(0, end + 1) : value;
        } else {
            int colon = value.lastIndexOf(':');
            if (colon > 0) name = value.substring(0, colon);
        }
        return name.equals("127.0.0.1") || name.equals("localhost") || name.equals("[::1]") || name.equals("::1");
    }

    private static void sendJson(HttpExchange exchange, int status, Json.Value value) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", value.write().getBytes(StandardCharsets.UTF_8));
    }

    private static void sendHtml(HttpExchange exchange, String html) throws IOException {
        send(exchange, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] data) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        exchange.getResponseHeaders().set("Cross-Origin-Resource-Policy", "same-origin");
        exchange.getResponseHeaders().set("Cross-Origin-Opener-Policy", "same-origin");
        // Local IDE UI — first-party scripts only. Fengari needs 'unsafe-eval' for Lua.
        if (type != null && (type.startsWith("text/html") || type.startsWith("application/javascript")
                || type.contains("text/css"))) {
            // unsafe-eval: Fengari (Lua VM) compiles tokenizer chunks with Function/eval.
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; "
                            + "font-src 'self' data:; img-src 'self' data:; "
                            + "connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; "
                            + "object-src 'none'; upgrade-insecure-requests");
        }
        exchange.sendResponseHeaders(status, data.length);
        exchange.getResponseBody().write(data);
        exchange.close();
    }
}
