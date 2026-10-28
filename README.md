# Lark 3

Lark is a focused Java IDE: multi-file projects, live compile feedback, brace guide, small refactors, debugger, JUnit, and GitHub clone/push. Lighter than IntelliJ, more useful than a browser text box — deliberately not bloated.

## Downloads (three tracks)

| | **Lark Desktop** (default) | **Lark Executable** | **Lark Mobile** |
| --- | --- | --- | --- |
| **What it is** | Setup wizard install | Unzip → double-click **Lark.exe** | Touch-first edition |
| **GitHub file** | `LarkDesktopSetup.exe` | `lark-executable-windows.zip` | `lark-mobile-windows.zip` |
| **How to start** | Start Menu → **Lark Desktop** | **Lark.exe** only | **Start Lark Mobile** |
| **UI** | Full desktop IDE (app window) | Full desktop IDE | Bottom-tab Mobile UI |
| **Best for** | Everyday PC, Start Menu | Labs / USB / no `.bat` | Tablet + phone pair |

Download site: **[treyvoprime.github.io/lark-download](https://lark.rocks/)**  
Releases: **[github.com/TreyvoPrime/lark/releases/latest](https://github.com/TreyvoPrime/lark/releases/latest)**

### Lark Desktop (default — Setup install)

```text
https://github.com/TreyvoPrime/lark/releases/latest/download/LarkDesktopSetup.exe
```

- Run **LarkDesktopSetup.exe** — welcome → folder → install → finish / launch
- Installs to `%LOCALAPPDATA%\Programs\Lark` (no admin password)
- Start Menu + Desktop shortcuts; Add/Remove Programs entry
- After install you mainly see **Lark Desktop.exe** — `app\` and `jdk\` are hidden runtime
- Optional payload zip: `lark-desktop-windows.zip` (same files, no wizard)

Mac / Linux payload zips ship `start-lark-desktop.sh` with the same buried `app/` + `jdk/` layout.

### Lark Executable

```text
https://github.com/TreyvoPrime/lark/releases/latest/download/lark-executable-windows.zip
```

- Extract All → double-click **Lark.exe**
- No `.bat` / `.ps1` required
- `app\` (jar) and `jdk\` stay beside the exe and are soft-hidden on Windows
- Same IDE as Desktop; own update channel (`executable`)

### Lark Mobile (separate edition)

```text
https://github.com/TreyvoPrime/lark/releases/latest/download/lark-mobile-windows.zip
https://github.com/TreyvoPrime/lark/releases/latest/download/lark-mobile-mac.zip
https://github.com/TreyvoPrime/lark/releases/latest/download/lark-mobile-linux.zip
```

- Extract → run **Start Lark Mobile**
- Keep the console open — it prints a **Phone URL** with a pair code
- On your phone (same Wi‑Fi), open that URL for the Mobile UI

Do not open launchers from inside a zip preview / Temp folder.

## What’s new in 3.9.7.35

- Sharper app icon at every size.

## Build from source

```bash
./package.sh
./packaging/make-release.sh
```

Requires JDK 17+, Go (Windows launchers), NSIS (`makensis`) for `LarkDesktopSetup.exe`, and network once to cache portable JDKs.

## License

See [LICENSE](LICENSE).
