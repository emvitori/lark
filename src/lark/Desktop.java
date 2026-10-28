package lark;

import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;

/**
 * Lark Desktop — default installed edition (Setup wizard). Opens in its own
 * app window (Chrome/Edge --app mode), keeps a system tray exit control, and
 * uses the desktop update channel. Runtime lives under {@code app/} + {@code jdk/}.
 */
public final class Desktop {
    static final String CHANNEL = "desktop";

    private Desktop() {}

    public static void main(String[] args) {
        System.setProperty("lark.desktop", "true");
        System.setProperty("lark.channel", CHANNEL);
        if (System.getProperty("lark.ui") == null || System.getProperty("lark.ui").isBlank()) {
            System.setProperty("lark.ui", "jar");
        }
        try {
            boot(args);
        } catch (Throwable t) {
            System.err.println();
            System.err.println("========== Lark Desktop failed to start ==========");
            if (JdkHealth.looksLikeCorruptJdk(t)) {
                System.err.println(JdkHealth.message(t));
            } else {
                t.printStackTrace(System.err);
            }
            System.err.println("==================================================");
            System.err.flush();
            try {
                System.in.read();
            } catch (IOException ignored) {
                // Ignore.
            }
            System.exit(1);
        }
    }

    /** True when this JVM is the desktop edition (also recognizes retired enterprise markers). */
    public static boolean isDesktop() {
        if (Boolean.parseBoolean(System.getProperty("lark.desktop", "false"))) return true;
        if (Boolean.parseBoolean(System.getProperty("lark.enterprise", "false"))) return true;
        String channel = System.getProperty("lark.channel", "");
        if (CHANNEL.equalsIgnoreCase(channel) || "enterprise".equalsIgnoreCase(channel)) return true;
        String env = System.getenv("LARK_CHANNEL");
        if (env != null) {
            String e = env.trim();
            if (CHANNEL.equalsIgnoreCase(e) || "enterprise".equalsIgnoreCase(e)) return true;
        }
        try {
            java.nio.file.Path dir = Updates.installDir();
            if (dir != null) {
                if (java.nio.file.Files.isRegularFile(dir.resolve("desktop.marker"))) return true;
                if (java.nio.file.Files.isRegularFile(dir.resolve("app").resolve("desktop.marker"))) return true;
                // Retired Enterprise installs upgrade on the Desktop channel.
                if (java.nio.file.Files.isRegularFile(dir.resolve("enterprise.marker"))) return true;
                if (java.nio.file.Files.isRegularFile(dir.resolve("app").resolve("enterprise.marker"))) return true;
            }
        } catch (Throwable ignored) {
            // Install dir may not be ready yet.
        }
        return false;
    }

    private static void boot(String[] args) throws Exception {
        int port = Instance.defaultPort();
        boolean browser = true;
        boolean replace = "1".equals(System.getenv("LARK_REPLACE"));
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--help", "-h" -> {
                    help();
                    return;
                }
                case "--no-browser" -> browser = false;
                case "--replace" -> replace = true;
                case "--desktop" -> {
                    // Already in desktop mode.
                }
                case "--port" -> {
                    if (i + 1 >= args.length) {
                        help();
                        return;
                    }
                    port = Integer.parseInt(args[++i]);
                }
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    help();
                    return;
                }
            }
        }

        Instance.configure(CHANNEL, replace);
        Integer restartPort = Instance.consumeRestartPort();
        if (restartPort != null) {
            port = restartPort;
            Instance.configure(CHANNEL, true);
        } else {
            String envPort = System.getenv("LARK_PORT");
            if (envPort != null && !envPort.isBlank()) {
                try {
                    port = Integer.parseInt(envPort.trim());
                    Instance.configure(CHANNEL, true);
                } catch (NumberFormatException ignored) {
                    // keep parsed --port
                }
            }
        }

        System.out.println("Lark Desktop " + Settings.VERSION + " starting...");
        System.out.flush();
        JdkHealth.verifyOrThrow();

        if (!Instance.acquireOrFocus(browser)) {
            return;
        }

        Settings settings = Settings.load();
        try {
            Updates.refreshDesktopLaunchers(Updates.installDir());
        } catch (Throwable t) {
            System.out.println("Note: could not refresh launchers (" + t.getMessage() + ")");
        }

        Projects projects = new Projects(settings);
        projects.ensureSample();
        Server server = Server.start(port, projects, new Runner(), new GitHub(settings), settings);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        String url = "http://127.0.0.1:" + server.port();
        System.out.println();
        System.out.println("====================================");
        System.out.println("  Lark Desktop is running");
        System.out.println("  " + url);
        System.out.println("  Close from the tray icon to quit.");
        System.out.println("====================================");
        System.out.flush();

        try {
            WinTaskbar.stampKnownShortcuts();
        } catch (Throwable ignored) {
            // Best-effort taskbar identity.
        }
        installTray(url);
        if (browser) {
            System.out.println("Opening Lark Desktop window...");
            System.out.flush();
            AppWindow.openWithRetry(url);
        }
        new CountDownLatch(1).await();
    }

    private static void help() {
        System.out.println("""
                Lark Desktop — default installed Java IDE (Setup wizard)

                Usage:
                  java -cp lark.jar lark.Desktop [--port 43123] [--no-browser]

                Installs under %LOCALAPPDATA%\\Programs\\Lark (Windows) via
                LarkDesktopSetup.exe. Updates prompt in-app and require SHA-256.
                """);
    }

    private static void installTray(String url) {
        if (!SystemTray.isSupported()) return;
        try {
            SystemTray tray = SystemTray.getSystemTray();
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Open Lark");
            open.addActionListener(e -> Instance.focusOrOpen(url));
            MenuItem quit = new MenuItem("Quit Lark Desktop");
            quit.addActionListener(e -> System.exit(0));
            menu.add(open);
            menu.addSeparator();
            menu.add(quit);
            TrayIcon icon = new TrayIcon(trayImage(), "Lark Desktop " + Settings.VERSION, menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> Instance.focusOrOpen(url));
            tray.add(icon);
        } catch (AWTException | IllegalArgumentException e) {
            System.out.println("Note: system tray unavailable (" + e.getMessage() + ")");
        }
    }

    private static Image trayImage() {
        // Installs serve web/ from inside lark.jar, so load the icon as a resource first.
        try (java.io.InputStream in = Lark.class.getResourceAsStream("/web/favicon.png")) {
            if (in != null) {
                BufferedImage logo = ImageIO.read(in);
                if (logo != null) return logo;
            }
        } catch (IOException ignored) {
        }
        try {
            File png = new File("web/favicon.png");
            if (png.isFile()) {
                BufferedImage logo = ImageIO.read(png);
                if (logo != null) return logo;
            }
        } catch (IOException ignored) {
            // fall back to the drawn icon
        }
        int size = 64;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x1b1916));
        g.fillRoundRect(2, 2, size - 4, size - 4, 16, 16);
        g.setColor(new Color(0xe3a45a));
        g.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D wing = new Path2D.Float();
        wing.moveTo(14, 42);
        wing.curveTo(24, 36, 30, 22, 32, 14);
        wing.curveTo(34, 22, 40, 36, 50, 42);
        g.draw(wing);
        g.drawLine(22, 44, 42, 44);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        g.dispose();
        return img;
    }
}
