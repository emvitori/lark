package lark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Preferences stored in the home folder. The GitHub token never leaves this machine except for GitHub. */
public final class Settings {
    /** Four-part build (3.9.7.1) — stay on 3.x until an intentional Lark 4.0. */
    public static final String VERSION = "3.9.7.35";
    /** Default public update feed — always the latest GitHub release JSON companion. */
    public static final String DEFAULT_UPDATE_FEED =
            "https://github.com/TreyvoPrime/lark/releases/latest/download/update.json";
    /** Desktop-channel companion feed (same JSON; downloads.desktop-* keys preferred). */
    public static final String DEFAULT_DESKTOP_UPDATE_FEED = DEFAULT_UPDATE_FEED;
    public static final Set<String> THEMES = Set.of(
            "desk", "slate", "studio", "daylight", "moss", "ember", "fog",
            "blossom", "candy", "midnight", "citrus", "harbor",
            "graphite", "navy", "terminal", "parchment", "ultraviolet", "forest", "monochrome", "amber",
            "lagoon", "rosewood", "nova", "atelier",
            "obsidian", "glacier",
            "cedar", "inkwell", "porcelain", "hearth", "canopy",
            "prism", "volt", "flare", "reef", "magma"
    );
    public static final Set<String> FONT_FAMILIES = Set.of(
            "jetbrains", "fira", "source", "plex", "roboto", "space",
            "inconsolata", "ubuntu", "cousine", "sharetech", "vt323",
            "cascadia", "consolas", "system"
    );
    private static final Map<String, String> THEME_ALIASES = Map.of(
            "ink", "desk",
            "paper", "daylight",
            "tide", "moss",
            "copper", "ember",
            "chalk", "fog",
            "kawaii", "blossom",
            "pink", "blossom"
    );

    /** Slate is the first-install default (quiet dark desk). */
    public String theme = "slate";
    public int fontSize = 14;
    public int zoom = 100;
    public String fontFamily = "jetbrains";
    public boolean ligatures = true;
    public int indentSize = 4;
    public boolean indentWithTabs = false;
    public boolean lineWrapping = false;
    /** Show CodeMirror line numbers in the gutter (default on). */
    public boolean lineNumbers = true;
    /**
     * Explorer file tiles with a border around each row. On by default; can be
     * turned off in Settings for a quieter tray that keeps the vertical tree line.
     */
    public boolean trayOutlines = true;
    public boolean autoSave = true;
    public int autoSaveDelayMs = 800;
    public boolean formatOnSave = false;
    public String githubToken = "";
    public String githubRefreshToken = "";
    public long githubTokenExpiresAt;
    public String githubLogin = "";
    public String githubClientId = "";
    public String githubClientSecret = "";
    public String githubAppSlug = "";
    public String lastProject = "";
    public String updateFeed = "";
    public String skippedUpdate = "";
    public long lastUpdateCheck;
    public boolean initialized;

    private final Path file;

    private Settings(Path file) {
        this.file = file;
    }

    public static Path home() {
        String override = System.getenv("LARK_HOME");
        if (override != null && !override.isBlank()) return Path.of(override);
        return Path.of(System.getProperty("user.home"), ".lark");
    }

    public static Settings load() {
        Path file = home().resolve("settings.json");
        Settings settings = new Settings(file);
        if (!Files.isRegularFile(file)) return settings;
        try {
            Json.Obj obj = Json.parseObj(Files.readString(file));
            String theme = obj.str("theme", "slate");
            theme = THEME_ALIASES.getOrDefault(theme, theme);
            settings.theme = THEMES.contains(theme) ? theme : "slate";
            int loadedFont = (int) obj.longVal("fontSize");
            settings.fontSize = loadedFont > 0 ? clamp(loadedFont, 11, 28, 14) : 14;
            int loadedZoom = (int) obj.longVal("zoom");
            settings.zoom = loadedZoom > 0 ? clamp(loadedZoom, 80, 160, 100) : 100;
            String family = obj.str("fontFamily", "jetbrains");
            settings.fontFamily = FONT_FAMILIES.contains(family) ? family : "jetbrains";
            if (obj.has("ligatures")) settings.ligatures = obj.bool("ligatures");
            int indent = (int) obj.longVal("indentSize");
            settings.indentSize = indent == 2 || indent == 4 || indent == 8 ? indent : 4;
            if (obj.has("indentWithTabs")) settings.indentWithTabs = obj.bool("indentWithTabs");
            if (obj.has("lineWrapping")) settings.lineWrapping = obj.bool("lineWrapping");
            if (obj.has("lineNumbers")) settings.lineNumbers = obj.bool("lineNumbers");
            if (obj.has("trayOutlines")) settings.trayOutlines = obj.bool("trayOutlines");
            if (obj.has("autoSave")) settings.autoSave = obj.bool("autoSave");
            int delay = (int) obj.longVal("autoSaveDelayMs");
            settings.autoSaveDelayMs = delay == 300 || delay == 800 || delay == 1500 ? delay : 800;
            if (obj.has("formatOnSave")) settings.formatOnSave = obj.bool("formatOnSave");
            settings.githubToken = obj.str("githubToken", "");
            settings.githubRefreshToken = obj.str("githubRefreshToken", "");
            settings.githubTokenExpiresAt = obj.longVal("githubTokenExpiresAt");
            settings.githubLogin = obj.str("githubLogin", "");
            settings.githubClientId = obj.str("githubClientId", "");
            settings.githubClientSecret = obj.str("githubClientSecret", "");
            settings.githubAppSlug = obj.str("githubAppSlug", "");
            settings.lastProject = obj.str("lastProject", "");
            settings.updateFeed = obj.str("updateFeed", "");
            settings.skippedUpdate = obj.str("skippedUpdate", "");
            settings.lastUpdateCheck = obj.longVal("lastUpdateCheck");
            settings.initialized = obj.bool("initialized");
        } catch (RuntimeException | IOException ignored) {
            // A broken settings file should not stop the editor from opening.
        }
        return settings;
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Json.Obj obj = new Json.Obj()
                    .put("theme", theme)
                    .put("fontSize", fontSize)
                    .put("zoom", zoom)
                    .put("fontFamily", fontFamily == null ? "jetbrains" : fontFamily)
                    .put("ligatures", ligatures)
                    .put("indentSize", indentSize)
                    .put("indentWithTabs", indentWithTabs)
                    .put("lineWrapping", lineWrapping)
                    .put("lineNumbers", lineNumbers)
                    .put("trayOutlines", trayOutlines)
                    .put("autoSave", autoSave)
                    .put("autoSaveDelayMs", autoSaveDelayMs)
                    .put("formatOnSave", formatOnSave)
                    .put("githubToken", githubToken == null ? "" : githubToken)
                    .put("githubRefreshToken", githubRefreshToken == null ? "" : githubRefreshToken)
                    .put("githubTokenExpiresAt", githubTokenExpiresAt)
                    .put("githubLogin", githubLogin == null ? "" : githubLogin)
                    .put("githubClientId", githubClientId == null ? "" : githubClientId)
                    .put("githubClientSecret", githubClientSecret == null ? "" : githubClientSecret)
                    .put("githubAppSlug", githubAppSlug == null ? "" : githubAppSlug)
                    .put("lastProject", lastProject == null ? "" : lastProject)
                    .put("updateFeed", updateFeed == null ? "" : updateFeed)
                    .put("skippedUpdate", skippedUpdate == null ? "" : skippedUpdate)
                    .put("lastUpdateCheck", lastUpdateCheck)
                    .put("initialized", initialized);
            Files.writeString(file, obj.write());
            try {
                Files.setPosixFilePermissions(file, EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Windows has no POSIX permissions.
            }
        } catch (IOException e) {
            throw new RequestFail(500, "Couldn't save settings.");
        }
    }

    public void clearGithubSession() {
        githubToken = "";
        githubRefreshToken = "";
        githubTokenExpiresAt = 0;
        githubLogin = "";
    }

    public Path projectsDir() {
        return home().resolve("projects");
    }

    public static int clamp(int value, int min, int max, int fallback) {
        if (value < min || value > max) return fallback;
        return value;
    }
}
