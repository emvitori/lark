package lark;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Checks a remote update feed and stages a new lark.jar beside the running copy. */
public final class Updates {
    private final Settings settings;
    private volatile HttpClient http;

    public Updates(Settings settings) {
        this.settings = settings;
    }

    private HttpClient http() {
        HttpClient client = http;
        if (client != null) return client;
        synchronized (this) {
            if (http == null) {
                http = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
            }
            return http;
        }
    }

    public Json.Obj check() {
        String feed = resolveFeed();
        boolean desktop = Desktop.isDesktop();
        boolean executable = Executable.isExecutable() && !desktop;
        String edition = desktop ? "desktop" : executable ? "executable" : Mobile.isMobile() ? "mobile" : "portable";
        Json.Obj result = new Json.Obj()
                .put("ok", true)
                .put("current", Settings.VERSION)
                .put("feed", feed == null ? "" : feed)
                .put("available", false)
                .put("edition", edition)
                .put("prompt", desktop || executable);
        if (feed == null || feed.isBlank()) {
            return result.put("message", "No update feed is set.");
        }
        try {
            Api remote = get(normalizeFeed(feed));
            if (remote.status < 200 || remote.status >= 300 || remote.obj == null) {
                return result.put("message", "Couldn't read the update feed (" + remote.status + ").");
            }
            FeedInfo info = parseFeed(feed, remote.obj);
            String latest = info.version;
            String notes = info.notes;
            String skipped = settings.skippedUpdate == null ? "" : settings.skippedUpdate;
            boolean newer = compareVersions(latest, Settings.VERSION) > 0;
            // Desktop prompts every launch until updated; Executable soft-skips once.
            boolean skippedThis = newer && latest.equals(skipped) && !desktop;
            String download = info.download;
            result.put("available", newer && !skippedThis)
                    .put("latest", latest)
                    .put("notes", notes)
                    .put("download", download == null ? "" : download)
                    .put("sha256", info.sha256 == null ? "" : info.sha256)
                    .put("skipped", skippedThis)
                    .put("page", info.page == null ? "" : info.page)
                    .put("required", desktop && newer);
            if (!newer) result.put("message", "You're up to date — still on Lark " + Settings.VERSION + ".");
            else if (skippedThis) result.put("message", "Lark " + latest + " is still waiting whenever you want it.");
            else if (desktop) result.put("message", "Lark Desktop " + latest + " is ready. Updating takes a minute.");
            else if (executable) result.put("message", "Lark " + latest + " is ready to download.");
            else result.put("message", "Lark " + latest + " is ready when you are.");
            settings.lastUpdateCheck = System.currentTimeMillis();
            settings.save();
            return result;
        } catch (RequestFail e) {
            return result.put("message", e.getMessage());
        }
    }

    private record FeedInfo(String version, String notes, String download, String page, String sha256) {}

    /** Accepts Lark update.json or a GitHub Releases /latest (or repo) URL. */
    private FeedInfo parseFeed(String feed, Json.Obj obj) {
        if (obj.has("tag_name") || obj.has("assets")) {
            String tag = obj.str("tag_name", obj.str("name", "")).trim();
            if (tag.startsWith("v") || tag.startsWith("V")) tag = tag.substring(1);
            String notes = obj.str("body", obj.str("name", ""));
            String page = obj.str("html_url", "");
            Json.Obj downloads = assetsToDownloads(obj.arr("assets"));
            String download = pickDownload(feed, downloads);
            return new FeedInfo(tag, notes, download, page, "");
        }
        String latest = obj.str("version", "").trim();
        String notes = obj.str("notes", "");
        String download = pickDownload(feed, obj.obj("downloads"));
        String page = obj.str("page", "");
        String sha256 = pickSha256(obj, download);
        return new FeedInfo(latest, notes, download, page, sha256);
    }

    private static String pickSha256(Json.Obj feed, String downloadUrl) {
        if (feed == null || downloadUrl == null || downloadUrl.isBlank()) return "";
        Json.Obj hashes = feed.obj("sha256");
        if (hashes == null) return feed.str("sha256", "").trim();
        String base = downloadUrl;
        int slash = Math.max(downloadUrl.lastIndexOf('/'), downloadUrl.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < downloadUrl.length()) base = downloadUrl.substring(slash + 1);
        String byName = hashes.str(base, "").trim();
        if (!byName.isBlank()) return byName;
        // Channel keys matching downloads map
        Json.Obj downloads = feed.has("downloads") ? feed.obj("downloads") : null;
        for (String key : new String[]{
                "any", "windows", "mac", "linux", "desktop", "desktop-windows", "desktop-mac", "desktop-linux",
                "mobile", "mobile-windows", "mobile-mac", "mobile-linux",
                "executable", "executable-windows"
        }) {
            String url = downloads == null ? "" : downloads.str(key, "");
            if (!url.isBlank() && (downloadUrl.equals(url) || downloadUrl.endsWith("/" + base))) {
                String h = hashes.str(key, "").trim();
                if (!h.isBlank()) return h;
            }
        }
        return hashes.str("any", hashes.str("windows", "")).trim();
    }

    /** True for HTTPS update feeds on GitHub / official release hosts. */
    public static boolean allowedFeedUrl(String feed) {
        if (feed == null || feed.isBlank()) return true;
        try {
            URI uri = URI.create(normalizeFeed(feed.trim()));
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme)) return false;
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return host.equals("github.com")
                    || host.equals("api.github.com")
                    || host.equals("objects.githubusercontent.com")
                    || host.equals("release-assets.githubusercontent.com")
                    || host.endsWith(".github.com")
                    || host.equals("treyvoprime.github.io");
        } catch (Exception e) {
            return false;
        }
    }

    /** Where an update download may start: TreyvoPrime/lark or LarkDevelopment/Lark release assets over HTTPS, or localhost for packaging. */
    static boolean allowedDownloadUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            // Relative/local update feeds during packaging are http://127.0.0.1 only.
            if ("http".equals(scheme)) {
                return host.equals("127.0.0.1") || host.equals("localhost");
            }
            if (!"https".equals(scheme) || uri.getRawUserInfo() != null) return false;
            if (uri.getPort() != -1 && uri.getPort() != 443) return false;
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            return host.equals("github.com")
                    && releasePathOk(path)
                    && !path.contains("/../") && !path.endsWith("/..")
                    && !path.contains("%2e") && !path.contains("%2E");
        } catch (Exception e) {
            return false;
        }
    }

    /** Release assets from the official repos: TreyvoPrime/lark and LarkDevelopment/Lark. */
    private static boolean releasePathOk(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        return p.startsWith("/treyvoprime/lark/releases/download/")
                || p.startsWith("/larkdevelopment/lark/releases/download/");
    }

    /** Where a release download may end up after redirects: the pinned URL itself or GitHub's release asset CDN. */
    static boolean allowedRedirectUrl(String url) {
        if (allowedDownloadUrl(url)) return true;
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equals(scheme)
                    && (host.equals("objects.githubusercontent.com")
                        || host.equals("release-assets.githubusercontent.com"));
        } catch (Exception e) {
            return false;
        }
    }

    private static Json.Obj assetsToDownloads(Json.Arr assets) {
        Json.Obj downloads = new Json.Obj();
        if (assets == null) return downloads;
        for (int i = 0; i < assets.size(); i++) {
            if (!(assets.get(i) instanceof Json.Obj asset)) continue;
            String name = asset.str("name", "").toLowerCase(Locale.ROOT);
            String url = asset.str("browser_download_url", "");
            if (url.isBlank()) continue;
            if (name.contains("mobile") && name.contains("update")) {
                downloads.put("mobile", url);
                if (name.contains("windows")) downloads.put("mobile-windows", url);
                else if (name.contains("mac") || name.contains("darwin")) downloads.put("mobile-mac", url);
                else if (name.contains("linux")) downloads.put("mobile-linux", url);
            } else if (name.contains("mobile") && name.contains("windows")) {
                downloads.put("mobile-windows", url);
            } else if (name.contains("mobile") && (name.contains("mac") || name.contains("darwin"))) {
                downloads.put("mobile-mac", url);
            } else if (name.contains("mobile") && name.contains("linux")) {
                downloads.put("mobile-linux", url);
            } else if (name.contains("desktop") && name.contains("update")) {
                downloads.put("desktop", url);
                if (name.contains("windows")) downloads.put("desktop-windows", url);
                else if (name.contains("mac") || name.contains("darwin")) downloads.put("desktop-mac", url);
                else if (name.contains("linux")) downloads.put("desktop-linux", url);
            } else if (name.contains("desktop") && name.contains("windows")) {
                downloads.put("desktop-windows", url);
            } else if (name.contains("desktop") && (name.contains("mac") || name.contains("darwin"))) {
                downloads.put("desktop-mac", url);
            } else if (name.contains("desktop") && name.contains("linux")) {
                downloads.put("desktop-linux", url);
            } else if (name.contains("windows")) downloads.put("windows", url);
            else if (name.contains("mac") || name.contains("darwin")) downloads.put("mac", url);
            else if (name.contains("linux")) downloads.put("linux", url);
            else if (name.equals("lark.zip") || name.endsWith("/lark.zip")) downloads.put("any", url);
            else if (name.contains("lark") && name.endsWith(".zip") && !downloads.has("any")) downloads.put("any", url);
        }
        return downloads;
    }

    static String normalizeFeed(String feed) {
        if (feed == null) return "";
        String value = feed.trim();
        if (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        // https://github.com/owner/repo  -> releases/latest API
        if (value.matches("(?i)https://github\\.com/[^/]+/[^/]+")) {
            return "https://api.github.com/repos/" + value.substring("https://github.com/".length()) + "/releases/latest";
        }
        if (value.matches("(?i)https://github\\.com/[^/]+/[^/]+/releases(/latest)?")) {
            String path = value.substring("https://github.com/".length());
            String[] parts = path.split("/");
            return "https://api.github.com/repos/" + parts[0] + "/" + parts[1] + "/releases/latest";
        }
        return value;
    }

    public Json.Obj install(String downloadUrl) {
        return install(downloadUrl, true);
    }

    /**
     * @param allowCustomUrl when false (Wi‑Fi / paired clients), only the feed's download URL is used.
     */
    public Json.Obj install(String downloadUrl, boolean allowCustomUrl) {
        Json.Obj checked = check();
        String feedDownload = checked.str("download", "");
        String expectedSha = checked.str("sha256", "");
        if (downloadUrl == null || downloadUrl.isBlank()) {
            downloadUrl = feedDownload;
            if (!checked.bool("available") || downloadUrl.isBlank()) {
                throw new RequestFail(400, checked.str("message", "No update is ready to install."));
            }
        } else if (!allowCustomUrl) {
            // Paired phones cannot point install at an arbitrary zip.
            downloadUrl = feedDownload;
            if (downloadUrl == null || downloadUrl.isBlank()) {
                throw new RequestFail(403, "Updates must use the official download from the update feed.");
            }
        } else if (!allowedDownloadUrl(downloadUrl)) {
            // Even loopback clients must use an official Lark release asset; the feed's SHA-256 is still required below.
            throw new RequestFail(400, "Update URLs must be TreyvoPrime/lark or LarkDevelopment/Lark GitHub release downloads.");
        }
        if (!allowedDownloadUrl(downloadUrl)) {
            throw new RequestFail(400, "Update downloads must be HTTPS TreyvoPrime/lark or LarkDevelopment/Lark GitHub release assets (or localhost for packaging).");
        }
        // Public releases must carry SHA-256 — refuse unsigned remote updates.
        if (!isLocalhostUrl(downloadUrl) && (expectedSha == null || expectedSha.isBlank())) {
            throw new RequestFail(400, "Update feed is missing SHA-256 hashes. Refusing to install.");
        }
        Path installDir = installDir();
        if (installDir == null) {
            throw new RequestFail(500, "Lark couldn't find its install folder.");
        }
        Path zip = null;
        Path target = jarFile(installDir);
        Path extracted = target.resolveSibling("lark.jar.extracted");
        Path pending = target.resolveSibling("lark.jar.pending");
        Path bak = target.resolveSibling("lark.jar.bak");
        try {
            Files.createDirectories(target.getParent());
            zip = Files.createTempFile("lark-update-", ".zip");
            downloadTo(downloadUrl, zip);
            if (expectedSha == null || expectedSha.isBlank()) {
                throw new RequestFail(400, "Update feed has no SHA-256 for this download, so Lark won't install it.");
            }
            String actual = sha256Hex(zip);
            if (!expectedSha.trim().equalsIgnoreCase(actual)) {
                throw new RequestFail(400, "Update zip failed integrity check (SHA-256 mismatch).");
            }
            // Seed launchers from the running jar first, then let the update zip
            // overwrite them. Never refresh AFTER extract — that would clobber the
            // new launchers with the old ones still loaded in memory.
            boolean desktop = Desktop.isDesktop();
            boolean executable = Executable.isExecutable() && !desktop;
            if (desktop) refreshDesktopLaunchers(installDir);
            else if (executable) refreshExecutableMarkers(installDir);
            else if (Mobile.isMobile()) refreshMobileLaunchers(installDir);
            else refreshLaunchers(installDir);
            extractUpdate(zip, extracted, installDir);
            try {
                Files.move(extracted, pending, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(extracted, pending, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!Files.isRegularFile(pending)) {
                throw new RequestFail(500, "The update downloaded, but Lark couldn't stage the new jar.");
            }
            // Windows Start Lark runs a COPY under %LOCALAPPDATA%\Lark\run, so the
            // install jar is usually free to replace while Lark is open. If AV or
            // Explorer still holds it, leave .pending for the launcher's retry loop.
            boolean applied = tryApplyPending(pending, target, bak);
            if (!applied) {
                for (int i = 0; i < 20 && !applied; i++) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    applied = tryApplyPending(pending, target, bak);
                }
            }
            if (applied) {
                return new Json.Obj()
                        .put("ok", true)
                        .put("pending", false)
                        .put("applied", true)
                        .put("canRestart", true)
                        .put("message", "All set — restart Lark and you'll be on the new version.");
            }
            return new Json.Obj()
                    .put("ok", true)
                    .put("pending", true)
                    .put("applied", false)
                    .put("canRestart", true)
                    .put("message", "Download finished. Restart Lark and it'll wrap up the install as it comes back.");
        } catch (RequestFail e) {
            throw e;
        } catch (Exception e) {
            throw new RequestFail(502, e.getMessage() == null ? "Couldn't install that update." : e.getMessage());
        } finally {
            if (zip != null) {
                try {
                    Files.deleteIfExists(zip);
                } catch (IOException ignored) {
                    // Temp cleanup is best-effort.
                }
            }
            try {
                Files.deleteIfExists(extracted);
            } catch (IOException ignored) {
                // Best-effort.
            }
        }
    }

    /** Spawns the native or script launcher, then exits so Windows can finish any jar swap. */
    public Json.Obj restart() {
        Path dir = installDir();
        if (dir == null) throw new RequestFail(500, "Lark couldn't find its install folder.");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        boolean desktop = Desktop.isDesktop();
        boolean executable = Executable.isExecutable() && !desktop;
        boolean mobile = Mobile.isMobile();
        Integer livePort = Instance.readPort();
        int handoffPort = livePort == null ? Instance.defaultPort() : livePort;
        Instance.prepareRestart(handoffPort);
        try {
            if (desktop) refreshDesktopLaunchers(dir);
            if (executable) refreshExecutableMarkers(dir);
            if (mobile) refreshMobileLaunchers(dir);
            if (windows) {
                Path desktopExe = dir.resolve("Lark Desktop.exe");
                Path executableExe = dir.resolve("Lark.exe");
                if (desktop && Files.isRegularFile(desktopExe)) {
                    return restartNativeExe(desktopExe, dir, "Restarting Lark Desktop…");
                }
                if (executable && Files.isRegularFile(executableExe)) {
                    return restartNativeExe(executableExe, dir, "Restarting Lark Executable…");
                }
                Path script;
                if (mobile) {
                    Path vbs = dir.resolve("Start Lark Mobile.vbs");
                    Path bat = dir.resolve("Start Lark Mobile.bat");
                    Path finish = dir.resolve("finish-update.bat");
                    if (Files.isRegularFile(vbs)) script = vbs;
                    else if (Files.isRegularFile(bat)) script = bat;
                    else script = Files.isRegularFile(finish) ? finish : dir.resolve("Start Lark.bat");
                } else if (desktop) {
                    Path appVbs = appDir(dir).resolve("Start Lark Desktop.vbs");
                    Path bat = dir.resolve("Start Lark Desktop.bat");
                    if (Files.isRegularFile(appVbs)) script = appVbs;
                    else if (Files.isRegularFile(bat)) script = bat;
                    else script = dir.resolve("Launch Lark Desktop.bat");
                } else {
                    Path finish = dir.resolve("finish-update.bat");
                    Path start = dir.resolve("Start Lark.bat");
                    script = Files.isRegularFile(finish) ? finish : start;
                }
                if (!Files.isRegularFile(script)) {
                    throw new RequestFail(500, "A Lark launcher is missing next to the install.");
                }
                new ProcessBuilder("cmd.exe", "/c", "start", "", script.toAbsolutePath().toString())
                        .directory(dir.toFile())
                        .start();
            } else {
                Path start;
                if (mobile) {
                    start = dir.resolve("start-lark-mobile.sh");
                    if (!Files.isRegularFile(start)) start = dir.resolve("Start Lark Mobile.command");
                } else if (desktop) {
                    start = dir.resolve("start-lark-desktop.sh");
                    if (!Files.isRegularFile(start)) start = dir.resolve("Launch Lark Desktop.command");
                } else {
                    start = dir.resolve("start-lark.sh");
                    if (!Files.isRegularFile(start)) start = dir.resolve("Start Lark.command");
                }
                if (!Files.isRegularFile(start)) {
                    throw new RequestFail(500, "A Lark launcher is missing next to the install.");
                }
                new ProcessBuilder("/bin/sh", start.toAbsolutePath().toString())
                        .directory(dir.toFile())
                        .start();
            }
        } catch (RequestFail e) {
            throw e;
        } catch (Exception e) {
            throw new RequestFail(500, e.getMessage() == null ? "Couldn't restart Lark." : e.getMessage());
        }
        // Free the instance lock early so the new process can claim it while we exit.
        Instance.releaseQuietly();
        Thread stopper = new Thread(() -> {
            try {
                Thread.sleep(700);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "lark-restart");
        stopper.setDaemon(true);
        stopper.start();
        String msg = mobile ? "Restarting Lark Mobile — hang tight…"
                : desktop ? "Restarting Lark Desktop — back in a moment…"
                : executable ? "Restarting Lark — back in a moment…"
                : "Restarting Lark — back in a moment…";
        return new Json.Obj()
                .put("ok", true)
                .put("message", msg);
    }

    private static Json.Obj restartNativeExe(Path exe, Path dir, String message) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(exe.toAbsolutePath().toString())
                .directory(dir.toFile());
        pb.environment().put("LARK_REPLACE", "1");
        Integer port = Instance.readPort();
        if (port != null) pb.environment().put("LARK_PORT", Integer.toString(port));
        pb.start();
        Instance.releaseQuietly();
        Thread stopper = new Thread(() -> {
            try {
                Thread.sleep(700);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "lark-restart");
        stopper.setDaemon(true);
        stopper.start();
        return new Json.Obj().put("ok", true).put("message", message);
    }

    private static boolean tryApplyPending(Path pending, Path target, Path bak) {
        if (!Files.isRegularFile(pending)) return Files.isRegularFile(target) && !Files.isRegularFile(pending);
        try {
            Files.deleteIfExists(bak);
            if (Files.isRegularFile(target)) {
                Files.move(target, bak, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(bak);
            return Files.isRegularFile(target) && !Files.isRegularFile(pending);
        } catch (IOException locked) {
            if (!Files.isRegularFile(target) && Files.isRegularFile(bak)) {
                try {
                    Files.move(bak, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // Pending remains for the next Start Lark.
                }
            }
            return false;
        }
    }

    /** Writes Start Lark launchers from embedded jar resources into the install folder. */
    static void refreshLaunchers(Path installDir) {
        if (installDir == null || !Files.isDirectory(installDir)) return;
        writeLaunchResource("lark/launch/StartLark.bat", installDir.resolve("Start Lark.bat"));
        writeLaunchResource("lark/launch/StartLark.vbs", installDir.resolve("Start Lark.vbs"));
        writeLaunchResource("lark/launch/finish-update.bat", installDir.resolve("finish-update.bat"));
        writeLaunchResource("lark/launch/StopLarkClean.bat", installDir.resolve("Stop Lark and Clean Cache.bat"));
        writeLaunchResource("lark/launch/WipeLark.bat", installDir.resolve("Wipe Lark From This PC.bat"));
        writeLaunchResource("lark/launch/start-lark.sh", installDir.resolve("start-lark.sh"));
        writeLaunchResource("lark/launch/start-lark.sh", installDir.resolve("Start Lark.command"));
        chmodLaunchers(installDir, "start-lark.sh", "Start Lark.command");
    }

    /** Ensures Desktop channel marker under app/; shell helpers for unix payloads. */
    static void refreshDesktopLaunchers(Path installDir) {
        if (installDir == null || !Files.isDirectory(installDir)) return;
        Path app = appDir(installDir);
        try {
            Files.createDirectories(app);
        } catch (IOException ignored) {
            // Best-effort.
        }
        boolean setupInstall = Files.isRegularFile(installDir.resolve("Lark Desktop.exe"));
        // Setup installs: only refresh unix helpers + marker — do not scatter .bat at root.
        writeLaunchResource("lark/launch/start-lark-desktop.sh", installDir.resolve("start-lark-desktop.sh"));
        writeLaunchResource("lark/launch/start-lark-desktop.sh", installDir.resolve("Launch Lark Desktop.command"));
        writeLaunchResource("lark/launch/StampLarkAumid.ps1", app.resolve("Stamp-LarkAumid.ps1"));
        if (!setupInstall) {
            writeLaunchResource("lark/launch/StartDesktop.bat", installDir.resolve("Start Lark Desktop.bat"));
            writeLaunchResource("lark/launch/StartDesktop.vbs", app.resolve("Start Lark Desktop.vbs"));
        }
        try {
            if (!Files.isRegularFile(app.resolve("desktop.marker"))) {
                Files.writeString(app.resolve("desktop.marker"), "desktop\n");
            }
        } catch (IOException ignored) {
            // Best-effort channel marker.
        }
        chmodLaunchers(installDir, "start-lark-desktop.sh", "Launch Lark Desktop.command", "Start Lark Desktop.command");
    }

    /** Keeps Executable channel markers under app/. */
    static void refreshExecutableMarkers(Path installDir) {
        if (installDir == null || !Files.isDirectory(installDir)) return;
        Path app = appDir(installDir);
        try {
            Files.createDirectories(app);
            if (!Files.isRegularFile(app.resolve("executable.marker"))) {
                Files.writeString(app.resolve("executable.marker"), "executable\n");
            }
        } catch (IOException ignored) {
            // Best-effort.
        }
    }

    /** Writes Lark Mobile launchers (touch UI + phone pair). */
    static void refreshMobileLaunchers(Path installDir) {
        if (installDir == null || !Files.isDirectory(installDir)) return;
        writeLaunchResource("lark/launch/StartMobile.bat", installDir.resolve("Start Lark Mobile.bat"));
        writeLaunchResource("lark/launch/StartMobile.vbs", installDir.resolve("Start Lark Mobile.vbs"));
        writeLaunchResource("lark/launch/start-lark-mobile.sh", installDir.resolve("start-lark-mobile.sh"));
        writeLaunchResource("lark/launch/start-lark-mobile.sh", installDir.resolve("Start Lark Mobile.command"));
        try {
            if (!Files.isRegularFile(installDir.resolve("mobile.marker"))) {
                Files.writeString(installDir.resolve("mobile.marker"), "mobile\n");
            }
        } catch (IOException ignored) {
            // Best-effort channel marker.
        }
        chmodLaunchers(installDir, "start-lark-mobile.sh", "Start Lark Mobile.command");
    }

    private static void chmodLaunchers(Path installDir, String... names) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);
            for (String name : names) {
                Path path = installDir.resolve(name);
                if (Files.isRegularFile(path)) {
                    try {
                        Files.setPosixFilePermissions(path, perms);
                    } catch (UnsupportedOperationException ignored) {
                        // Windows.
                    }
                }
            }
        } catch (IOException ignored) {
            // Best-effort chmod.
        }
    }

    private static void writeLaunchResource(String resource, Path target) {
        try (InputStream in = Updates.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) return;
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Keep whatever launcher is already on disk.
        }
    }

    static Path installDir() {
        String prop = System.getProperty("lark.install");
        if (prop != null && !prop.isBlank()) {
            Path dir = Path.of(prop).toAbsolutePath().normalize();
            if (Files.isDirectory(dir)) return dir;
        }
        String env = System.getenv("LARK_INSTALL");
        if (env != null && !env.isBlank()) {
            Path dir = Path.of(env).toAbsolutePath().normalize();
            if (Files.isDirectory(dir)) return dir;
        }
        try {
            var location = Lark.class.getProtectionDomain().getCodeSource().getLocation();
            if (location != null) {
                Path path = Path.of(location.toURI());
                if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")) {
                    Path parent = path.getParent();
                    // Run copies live under %LOCALAPPDATA%\Lark\run — prefer -Dlark.install.
                    if (parent != null && "app".equalsIgnoreCase(parent.getFileName().toString())
                            && parent.getParent() != null) {
                        return parent.getParent();
                    }
                    return parent;
                }
            }
        } catch (Exception ignored) {
            // Fall through to the working directory.
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    /** Desktop runtime folder (jar + helpers). Created on demand. */
    static Path appDir(Path installDir) {
        if (installDir == null) return Path.of("app");
        return installDir.resolve("app");
    }

    /**
     * Resolves the install jar. Prefers app/lark.jar (unified Desktop layout),
     * then root lark.jar for older trees. Always contained under installDir.
     */
    static Path jarFile(Path installDir) {
        if (installDir == null) throw new RequestFail(500, "Lark couldn't find its install folder.");
        Path root = installDir.toAbsolutePath().normalize();
        Path appJar = root.resolve("app").resolve("lark.jar");
        Path rootJar = root.resolve("lark.jar");
        Path chosen;
        if (Desktop.isDesktop() || Files.isDirectory(root.resolve("app")) || Files.isRegularFile(appJar)) {
            chosen = appJar;
        } else if (Files.isRegularFile(rootJar)) {
            chosen = rootJar;
        } else {
            chosen = Desktop.isDesktop() ? appJar : rootJar;
        }
        return ensureInsideInstall(root, chosen);
    }

    static Path ensureInsideInstall(Path installDir, Path candidate) {
        Path root = installDir.toAbsolutePath().normalize();
        Path path = candidate.toAbsolutePath().normalize();
        if (!path.startsWith(root)) {
            throw new RequestFail(400, "Refusing a path outside the Lark install folder.");
        }
        try {
            if (Files.exists(path) && Files.isSymbolicLink(path)) {
                Path real = path.toRealPath();
                Path rootReal = root.toRealPath();
                if (!real.startsWith(rootReal)) {
                    throw new RequestFail(400, "Refusing a jar symlink that escapes the install folder.");
                }
            }
        } catch (RequestFail e) {
            throw e;
        } catch (IOException ignored) {
            // Existence checks are best-effort before the file is created.
        }
        return path;
    }

    static boolean isLocalhostUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]");
        } catch (Exception e) {
            return false;
        }
    }

    public Json.Obj skip(String version) {
        settings.skippedUpdate = version == null ? "" : version.trim();
        settings.save();
        return new Json.Obj().put("ok", true).put("skippedUpdate", settings.skippedUpdate);
    }

    public Json.Obj saveFeed(String feed) {
        settings.updateFeed = feed == null ? "" : feed.trim();
        settings.save();
        return new Json.Obj().put("ok", true).put("updateFeed", settings.updateFeed);
    }

    String resolveFeed() {
        if (settings.updateFeed != null && !settings.updateFeed.isBlank()) return settings.updateFeed.trim();
        String env = System.getenv("LARK_UPDATE_FEED");
        if (env != null && !env.isBlank()) return env.trim();
        Path beside = installDir();
        if (beside != null) {
            for (Path file : List.of(
                    beside.resolve("update-feed.url"),
                    beside.resolve("app").resolve("update-feed.url"))) {
                if (!Files.isRegularFile(file)) continue;
                try {
                    String line = Files.readString(file).trim().lines().findFirst().orElse("").trim();
                    if (!line.isBlank()) {
                        settings.updateFeed = line;
                        settings.save();
                        return line;
                    }
                } catch (IOException ignored) {
                    // Try the next candidate.
                }
            }
        }
        // Always fall back to the public GitHub Releases feed so updates stay available.
        return Settings.DEFAULT_UPDATE_FEED;
    }

    static int compareVersions(String a, String b) {
        int[] left = parts(a);
        int[] right = parts(b);
        // Four-part versions (3.9.7.1) stay under 4.0 without burning through 3.x minors.
        for (int i = 0; i < 4; i++) {
            if (left[i] != right[i]) return Integer.compare(left[i], right[i]);
        }
        return 0;
    }

    private static int[] parts(String version) {
        int[] out = new int[4];
        if (version == null) return out;
        String clean = version.trim();
        if (clean.startsWith("v") || clean.startsWith("V")) clean = clean.substring(1);
        String[] bits = clean.split("[^0-9]+");
        for (int i = 0; i < Math.min(4, bits.length); i++) {
            if (bits[i].isBlank()) continue;
            try {
                out[i] = Integer.parseInt(bits[i]);
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

    private String pickDownload(String feed, Json.Obj downloads) {
        if (downloads == null) return null;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String key = "linux";
        if (os.contains("win")) key = "windows";
        else if (os.contains("mac")) key = "mac";
        String url = "";
        if (Mobile.isMobile()) {
            url = downloads.str("mobile-" + key, "");
            if (url.isBlank()) url = downloads.str("mobile", "");
        } else if (Desktop.isDesktop()) {
            url = downloads.str("desktop-" + key, "");
            if (url.isBlank()) url = downloads.str("desktop", "");
        } else if (Executable.isExecutable()) {
            url = downloads.str("executable-" + key, "");
            if (url.isBlank()) url = downloads.str("executable", "");
        }
        if (url.isBlank()) url = downloads.str(key, "");
        if (url.isBlank()) url = downloads.str("any", "");
        if (url.isBlank()) return null;
        return absolutize(feed, url);
    }

    private static String absolutize(String feed, String url) {
        try {
            return URI.create(feed).resolve(url).toString();
        } catch (Exception e) {
            return url;
        }
    }

    private void downloadTo(String url, Path target) throws IOException, InterruptedException {
        if (!allowedDownloadUrl(url)) {
            throw new RequestFail(400, "Blocked download host.");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(3))
                .header("User-Agent", "Lark-IDE/" + Settings.VERSION)
                .GET()
                .build();
        HttpResponse<InputStream> response = http().send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RequestFail(502, "Download failed (" + response.statusCode() + ").");
        }
        URI finalUri = response.uri();
        if (finalUri != null && !allowedRedirectUrl(finalUri.toString())) {
            throw new RequestFail(400, "Update redirect pointed at a blocked host.");
        }
        try (InputStream in = response.body()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) digest.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    /** Pulls lark.jar and refreshes launchers from a release zip (Desktop app/ layout aware). */
    private void extractUpdate(Path zip, Path jarOut, Path installDir) throws IOException {
        Files.deleteIfExists(jarOut);
        boolean foundJar = false;
        Path root = installDir.toAbsolutePath().normalize();
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                while (name.startsWith("./")) name = name.substring(2);
                if (name.isBlank() || name.contains("..")) continue;
                String base = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
                if ("lark.jar".equals(base)) {
                    Files.createDirectories(jarOut.getParent());
                    Files.copy(zin, jarOut, StandardCopyOption.REPLACE_EXISTING);
                    foundJar = true;
                    continue;
                }
                if ("Lark Desktop.exe".equals(base) || "Lark.exe".equals(base)) {
                    // Windows launchers lock their own image while running. Stage beside
                    // the install; the native launcher renames .pending into place on boot.
                    Path staged = ensureInsideInstall(root, root.resolve(base + ".pending"));
                    Files.copy(zin, staged, StandardCopyOption.REPLACE_EXISTING);
                    continue;
                }
                boolean underApp = name.startsWith("app/");
                boolean topLevel = !name.contains("/");
                boolean allowed = "Start Lark.bat".equals(base) || "Start Lark.vbs".equals(base)
                        || "Start Lark Desktop.bat".equals(base) || "Start Lark Desktop.vbs".equals(base)
                        || "Start Lark Desktop.command".equals(base)
                        || "finish-update.bat".equals(base)
                        || "Stop Lark and Clean Cache.bat".equals(base)
                        || "Wipe Lark From This PC.bat".equals(base)
                        || "start-lark.sh".equals(base) || "Start Lark.command".equals(base)
                        || "Launch Lark Desktop.bat".equals(base) || "Launch Lark Desktop.vbs".equals(base)
                        || "start-lark-desktop.sh".equals(base) || "Launch Lark Desktop.command".equals(base)
                        || "Install Lark Desktop.ps1".equals(base) || "Uninstall Lark Desktop.ps1".equals(base)
                        || "Install Lark Desktop.command".equals(base)
                        || "desktop.marker".equals(base)
                        || "executable.marker".equals(base)
                        || "lark-icon.ico".equals(base) || "lark-icon.png".equals(base)
                        || "README.txt".equals(base) || "update-feed.url".equals(base);
                if (!allowed) continue;
                Path dest;
                boolean buryRuntime = Desktop.isDesktop() || Executable.isExecutable();
                if (underApp) {
                    dest = ensureInsideInstall(root, root.resolve(name));
                } else if (topLevel && ("Start Lark Desktop.bat".equals(base)
                        || "Start Lark Desktop.command".equals(base)
                        || "start-lark-desktop.sh".equals(base)
                        || "Launch Lark Desktop.bat".equals(base)
                        || "Launch Lark Desktop.command".equals(base)
                        || "Start Lark.bat".equals(base)
                        || "Start Lark.command".equals(base)
                        || "start-lark.sh".equals(base)
                        || "README.txt".equals(base)
                        || "Lark Desktop.exe".equals(base)
                        || "Lark.exe".equals(base))) {
                    dest = ensureInsideInstall(root, root.resolve(base));
                } else if (buryRuntime) {
                    // Markers, icons, feed URL belong under app/ for slim installs.
                    dest = ensureInsideInstall(root, appDir(root).resolve(base));
                } else {
                    dest = ensureInsideInstall(root, root.resolve(base));
                }
                if (dest.getParent() != null) Files.createDirectories(dest.getParent());
                Files.copy(zin, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (!foundJar) {
            throw new RequestFail(400, "That update zip didn't contain lark.jar.");
        }
    }

    private Api get(String url) {
        try {
            boolean github = url.contains("api.github.com");
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Lark-IDE/" + Settings.VERSION)
                    .GET();
            if (github) {
                builder.header("Accept", "application/vnd.github+json");
                builder.header("X-GitHub-Api-Version", "2022-11-28");
            } else {
                builder.header("Accept", "application/json");
            }
            HttpResponse<String> response = http().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return Api.parse(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RequestFail(502, "The update check was interrupted.");
        } catch (IOException e) {
            throw new RequestFail(502, "Couldn't reach the update feed.");
        }
    }

    private record Api(int status, Json.Obj obj) {
        static Api parse(int status, String raw) {
            Json.Obj obj = null;
            if (raw != null && !raw.isBlank()) {
                try {
                    Json.Value value = Json.parse(raw);
                    if (value instanceof Json.Obj o) obj = o;
                } catch (RuntimeException ignored) {
                    // Keep status only.
                }
            }
            return new Api(status, obj);
        }
    }
}
